package com.vladimir.messenger.data.group

import com.vladimir.messenger.util.NodeIds

/**
 * Pure, deterministic policy for pairwise remote push in groups and channels.
 *
 * Groups and channels deliver content as per-member `APUGRP1` envelopes over the
 * same pairwise encrypted transport and capability push Worker used for personal
 * chats. Two rules keep the Worker budget, battery and privacy intact:
 *
 *  1. Only a real head message/post/comment (`GroupWire.Packet.Message`, non-blank
 *     and never an `APUIMGP1:` photo/text continuation chunk) may request a push
 *     wake, and only at an explicit send/relay site (`wakeRequested = true`).
 *     Typing, edits, deletes, poll votes, roster syncs, view/comment counters,
 *     backfill wants and global gossip never wake sleeping phones.
 *  2. Correspondent epoch offers and missing-pin `File HELLO` handshakes run only
 *     for packets belonging to a shared group or channel — never for cross-peer
 *     directory, nickname, avatar, birthday, capability, APK or feature-pack
 *     gossip — and every fanout pass is strictly capped.
 */
object GroupPushWakePolicy {

    /** Matches `InlineImage.PART_MARKER` without pulling Android bitmap classes. */
    internal const val INLINE_PART_PREFIX = "APUIMGP1:"

    /** Max epoch-ready unreachable peers woken in a single group/channel fanout. */
    const val MAX_WAKES_PER_FANOUT = 32

    /** Max pinned peers missing an epoch asked for their epoch in one fanout. */
    const val MAX_EPOCH_REQUESTS_PER_FANOUT = 8

    /** Max unpinned members sent a throttled `File HELLO` in one fanout. */
    const val MAX_HELLOS_PER_FANOUT = 8

    /** Max members offered our own epoch (15-min throttled) in one fanout. */
    const val MAX_ANNOUNCEMENTS_PER_FANOUT = 16

    /** Max concurrent outbound HTTP `/v1/wake` calls from one fanout. */
    const val MAX_CONCURRENT_WAKES = 4

    /** Single follow-up retry delay when `/v1/wake` returned `Unavailable`. */
    const val UNAVAILABLE_RETRY_DELAY_MS = 20_000L

    /** Fast prefix reject for high-volume global gossip before full packet parse. */
    private val NON_GROUP_KINDS: Set<String> = setOf(
        GroupWire.KIND_DIRECTORY,
        GroupWire.KIND_NICK,
        GroupWire.KIND_TYPING,
        GroupWire.KIND_WHOIS,
        GroupWire.KIND_AVAT,
        GroupWire.KIND_BIRTHDAY,
        GroupWire.KIND_EMOJI_STATUS,
        GroupWire.KIND_CAPABILITIES,
        GroupWire.KIND_JOIN_STATUS,
        GroupWire.KIND_UPDATE_PACK,
        GroupWire.KIND_UPDATE_WANT,
        GroupWire.KIND_UPDATE_NONE,
        GroupWire.KIND_UPDATE_ASK,
        GroupWire.KIND_UPDATE_PATCH_PACK,
        GroupWire.KIND_UPDATE_PATCH_WANT,
        GroupWire.KIND_FEATURE_PACK,
        GroupWire.KIND_FEATURE_PACK_WANT,
        GroupWire.KIND_FEATURE_PACK_NONE,
        GroupWire.KIND_FEATURE_PACK_ASK,
    )

    enum class CandidateState {
        EPOCH_READY,
        PINNED_NO_EPOCH,
        EPOCH_COOLDOWN,
        UNPINNED,
    }

    data class FanoutPlan(
        val wakePeers: List<String>,
        val requestEpochPeers: List<String>,
        val helloPeers: List<String>,
        val announcePeers: List<String>,
    ) {
        val isEmpty: Boolean
            get() = wakePeers.isEmpty() &&
                requestEpochPeers.isEmpty() &&
                helloPeers.isEmpty() &&
                announcePeers.isEmpty()

        companion object {
            val EMPTY = FanoutPlan(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    /** True only for a non-blank head `Message` packet (group text, post, comment, poll/file/SOS head). */
    fun shouldWakeForEnvelope(envelope: String): Boolean {
        if (!envelope.startsWith("${GroupWire.PREFIX}|${GroupWire.KIND_MESSAGE}|")) return false
        val packet = GroupWire.parse(envelope) as? GroupWire.Packet.Message ?: return false
        return shouldWakeForMessageText(packet.text)
    }

    fun shouldWakeForMessageText(text: String): Boolean =
        text.isNotBlank() && !text.startsWith(INLINE_PART_PREFIX)

    /**
     * True for valid group/channel packets sent to fellow members/admins;
     * false for global gossip (`dir`, `who`, `nick`, `avat`, `bday`, `emst`,
     * `cap`, `typ`, `up*`, `fp*`), sub-protocol overlays, and malformed strings.
     */
    fun isSharedGroupCorrespondentPacket(envelope: String): Boolean {
        val kind = extractKind(envelope) ?: return false
        if (kind in NON_GROUP_KINDS) return false
        val parsed = GroupWire.parse(envelope) ?: return false
        return groupIdOf(parsed).isNotBlank()
    }

    /** Group id carried by an inbound shared-group packet, or null for global gossip. */
    fun sharedGroupId(packet: GroupWire.Packet): String? = groupIdOf(packet).takeIf { it.isNotBlank() }

    /** Plans bounded wakes, epoch requests, epoch offers and `File HELLO`s after a fanout. */
    suspend fun planFanout(
        ownNodeId: String?,
        allRecipients: List<String>,
        failedRecipients: List<String>,
        wakeRequested: Boolean,
        envelope: String,
        maxWakes: Int = MAX_WAKES_PER_FANOUT,
        maxEpochRequests: Int = MAX_EPOCH_REQUESTS_PER_FANOUT,
        maxHellos: Int = MAX_HELLOS_PER_FANOUT,
        maxAnnouncements: Int = MAX_ANNOUNCEMENTS_PER_FANOUT,
        stateOf: suspend (String) -> CandidateState,
    ): FanoutPlan {
        if (!isSharedGroupCorrespondentPacket(envelope)) return FanoutPlan.EMPTY
        val canWake = wakeRequested && shouldWakeForEnvelope(envelope)
        val own = ownNodeId?.trim().orEmpty()

        fun normalize(ids: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            for (raw in ids) {
                val id = raw.trim()
                if (NodeIds.isNodeId(id) && id != own) seen.add(id)
            }
            return seen.toList()
        }

        val orderedAll = normalize(allRecipients)
        val orderedFailed = normalize(failedRecipients)
        if (orderedAll.isEmpty() && orderedFailed.isEmpty()) return FanoutPlan.EMPTY

        val cachedState = HashMap<String, CandidateState>()
        suspend fun inspect(peerId: String): CandidateState =
            cachedState.getOrPut(peerId) { stateOf(peerId) }

        val wakes = ArrayList<String>()
        val epochRequests = ArrayList<String>()
        val hellos = ArrayList<String>()
        val announces = ArrayList<String>()
        val helloSeen = HashSet<String>()

        if (canWake) {
            val wakeLimit = maxWakes.coerceAtLeast(0)
            val reqLimit = maxEpochRequests.coerceAtLeast(0)
            val helloLimit = maxHellos.coerceAtLeast(0)
            for (peerId in orderedFailed) {
                val needMore = wakes.size < wakeLimit ||
                    epochRequests.size < reqLimit ||
                    hellos.size < helloLimit
                if (!needMore) break
                when (inspect(peerId)) {
                    CandidateState.EPOCH_READY ->
                        if (wakes.size < wakeLimit) wakes.add(peerId)
                    CandidateState.PINNED_NO_EPOCH ->
                        if (epochRequests.size < reqLimit) epochRequests.add(peerId)
                    CandidateState.EPOCH_COOLDOWN -> Unit
                    CandidateState.UNPINNED ->
                        if (hellos.size < helloLimit && helloSeen.add(peerId)) hellos.add(peerId)
                }
            }
        }

        // Bootstrap unpinned group/channel peers and refresh our epoch offer
        // (15-min throttled inside PushEpochPublish) on both content and
        // membership/topic envelopes without sending any wake for the latter.
        val helloLimit = maxHellos.coerceAtLeast(0)
        val announceLimit = maxAnnouncements.coerceAtLeast(0)
        val requestedSet = epochRequests.toHashSet()
        for (peerId in orderedAll) {
            if (hellos.size >= helloLimit && announces.size >= announceLimit) break
            when (inspect(peerId)) {
                CandidateState.UNPINNED -> {
                    if (hellos.size < helloLimit && helloSeen.add(peerId)) hellos.add(peerId)
                }
                CandidateState.EPOCH_READY,
                CandidateState.PINNED_NO_EPOCH,
                CandidateState.EPOCH_COOLDOWN -> {
                    if (announces.size < announceLimit && peerId !in requestedSet) {
                        announces.add(peerId)
                    }
                }
            }
        }

        return FanoutPlan(
            wakePeers = wakes,
            requestEpochPeers = epochRequests,
            helloPeers = hellos,
            announcePeers = announces,
        )
    }

    private fun extractKind(envelope: String): String? {
        val prefix = "${GroupWire.PREFIX}|"
        if (!envelope.startsWith(prefix)) return null
        val end = envelope.indexOf('|', prefix.length)
        if (end <= prefix.length) return null
        return envelope.substring(prefix.length, end)
    }

    private fun groupIdOf(packet: GroupWire.Packet): String = when (packet) {
        is GroupWire.Packet.Message -> packet.groupId
        is GroupWire.Packet.TopicCreated -> packet.groupId
        is GroupWire.Packet.TopicUpdated -> packet.groupId
        is GroupWire.Packet.JoinRequest -> packet.groupId
        is GroupWire.Packet.JoinDecision -> packet.groupId
        is GroupWire.Packet.JoinInfo -> packet.groupId
        is GroupWire.Packet.Pin -> packet.groupId
        is GroupWire.Packet.Roster -> packet.groupId
        is GroupWire.Packet.GroupDeleted -> packet.groupId
        is GroupWire.Packet.GroupInfo -> packet.groupId
        is GroupWire.Packet.Topics -> packet.groupId
        is GroupWire.Packet.TopicsRequest -> packet.groupId
        is GroupWire.Packet.RosterRequest -> packet.groupId
        is GroupWire.Packet.Kick -> packet.groupId
        is GroupWire.Packet.Edit -> packet.groupId
        is GroupWire.Packet.MessageDelete -> packet.groupId
        is GroupWire.Packet.MemberRestrict -> packet.groupId
        is GroupWire.Packet.PostsRequest -> packet.groupId
        is GroupWire.Packet.PostManifest -> packet.manifest.groupId
        is GroupWire.Packet.PostKeys -> packet.groupId
        is GroupWire.Packet.PostKeysRequest -> packet.groupId
        is GroupWire.Packet.PieceWant -> packet.groupId
        is GroupWire.Packet.ManifestsRequest -> packet.groupId
        is GroupWire.Packet.FileWant -> packet.groupId
        is GroupWire.Packet.FileHave -> packet.groupId
        is GroupWire.Packet.FileNone -> packet.groupId
        is GroupWire.Packet.Peers -> packet.groupId
        is GroupWire.Packet.CountersRequest -> packet.groupId
        is GroupWire.Packet.Counters -> packet.groupId
        is GroupWire.Packet.CommentsRequest -> packet.groupId
        is GroupWire.Packet.CommentIds -> packet.groupId
        is GroupWire.Packet.CommentCounts -> packet.groupId
        is GroupWire.Packet.OwnerClaim -> packet.groupId
        is GroupWire.Packet.Reply -> packet.groupId
        is GroupWire.Packet.Poll -> packet.groupId
        is GroupWire.Packet.PollVote -> packet.groupId
        is GroupWire.Packet.PollClose -> packet.groupId
        is GroupWire.Packet.Wallpaper -> packet.groupId
        is GroupWire.Packet.WallpaperRequest -> packet.groupId
        is GroupWire.Packet.MemberListVisibility -> packet.groupId
        is GroupWire.Packet.MemberListVisibilityRequest -> packet.groupId
        is GroupWire.Packet.QuietHoursRequest -> packet.groupId
        is GroupWire.Packet.QuietHours -> packet.groupId
        is GroupWire.Packet.SlowMode -> packet.groupId
        is GroupWire.Packet.SosSettings -> packet.groupId
        is GroupWire.Packet.SosText -> packet.groupId
        // Global gossip, typing indicators, and unauthenticated status queries never disclose epochs.
        is GroupWire.Packet.Typing,
        is GroupWire.Packet.JoinStatusQuery,
        is GroupWire.Packet.WhoIs,
        is GroupWire.Packet.Directory,
        is GroupWire.Packet.Nick,
        is GroupWire.Packet.UpdatePack,
        is GroupWire.Packet.UpdateWant,
        is GroupWire.Packet.UpdateNone,
        is GroupWire.Packet.UpdateAsk,
        is GroupWire.Packet.FeaturePackHave,
        is GroupWire.Packet.FeaturePackWant,
        is GroupWire.Packet.FeaturePackNone,
        is GroupWire.Packet.FeaturePackAsk,
        is GroupWire.Packet.UpdatePatchPack,
        is GroupWire.Packet.UpdatePatchWant,
        is GroupWire.Packet.Capabilities,
        is GroupWire.Packet.Avatar,
        is GroupWire.Packet.Birthday,
        is GroupWire.Packet.EmojiStatus -> ""
    }
}
