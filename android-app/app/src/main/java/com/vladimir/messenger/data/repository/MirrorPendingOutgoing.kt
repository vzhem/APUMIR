package com.vladimir.messenger.data.repository

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorPendingBatch
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException

/**
 * Rebuild a shadow's pending frames from the saved parent, not from a new
 * composer value. The checks suppress stale/deleted rows and local clears
 * before handing a frame to the mirror channel. They are only preflights:
 * deletion, identity replacement or a clear can race the later network send.
 * Neither an offered frame nor the parent's status proves sidecar delivery.
 */
internal class MirrorPendingOutgoing(
    private val countAtStart: suspend () -> Int,
    private val load: suspend (OutboxPosition?, Int) -> List<MessageEntity>,
    private val find: suspend (String) -> MessageEntity?,
    private val chatName: suspend (String) -> String?,
    private val isPersonalChat: suspend (String) -> Boolean,
    private val isCleared: (String, String) -> Boolean,
    private val preflight: suspend (String, String, String) -> Boolean,
    private val recordFailure: (Exception) -> Unit,
) {
    private val scan = FairOutboxScan<MessageEntity>(maxAttemptsPerCycle = MAX_ROWS)

    /** Visit one bounded page per call; failures remain eligible in a later pass. */
    suspend fun page(limit: Int): MirrorPendingBatch {
        if (limit <= 0) return MirrorPendingBatch(emptyList(), moreToScan = false)
        val frames = ArrayList<MirrorRow>()
        val progress = scan.run(
            pageSize = limit.coerceAtMost(MAX_ROWS),
            countAtStart = countAtStart,
            load = { after, size ->
                load(after, size).map { row ->
                    OutboxCandidate(OutboxPosition(row.timestamp, row.id), row.chatId, row)
                }
            },
            attempt = { snapshot ->
                val frame = checkedFrame(snapshot)
                if (frame == null) null else { frames += frame; true }
            },
        )
        return MirrorPendingBatch(frames, progress.moreToScan)
    }

    private suspend fun checkedFrame(snapshot: MessageEntity): MirrorRow? {
        try {
            val row = find(snapshot.id)?.takeIf { it == snapshot } ?: return null
            if (!row.isFromMe || row.status !in PENDING || row.channel == "GROUP" ||
                !row.topicId.isNullOrBlank()
            ) return null
            if (!isPersonalChat(row.chatId)) return null
            val name = chatName(row.chatId) ?: return null
            val frame = row.toPendingMirrorRow(name)
            if (!MirrorOutgoingPolicy.accepts(frame) ||
                !mirrorPeerPermitted(frame.recipientId, frame.id, isCleared) ||
                !preflight(frame.id, frame.chatId, OutboxRecipient.normalize(frame.recipientId))
            ) return null
            return frame
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(error)
            return null
        }
    }

    private companion object {
        const val MAX_ROWS = 50
        val PENDING = setOf(MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name)
    }
}

/** Do not copy orphaned quote text from a legacy row without a reply target. */
internal fun MessageEntity.toPendingMirrorRow(contactName: String): MirrorRow {
    val target = replyToId?.takeIf { it.isNotBlank() }.orEmpty()
    return MirrorRow(
        id = id,
        chatId = chatId,
        contactName = contactName,
        senderId = "self",
        content = content,
        timestamp = timestamp,
        mine = true,
        recipientId = recipientId,
        status = status,
        replyToId = target,
        replyAuthor = if (target.isNotEmpty()) replyAuthor else "",
        replyText = if (target.isNotEmpty()) replyText else "",
        textColor = textColor,
        senderNameColor = senderNameColor,
    )
}

internal fun roomMirrorPendingOutgoing(
    context: Context,
    messages: MessageDao,
    chats: ChatDao,
    groups: GroupDao,
    guard: AutomaticOutboxSender,
): MirrorPendingOutgoing = MirrorPendingOutgoing(
    countAtStart = messages::countPendingMirrorOutgoing,
    load = { after, limit ->
        if (after == null) messages.pendingMirrorOutgoing(limit)
        else messages.pendingMirrorOutgoingAfter(after.timestamp, after.id, limit)
    },
    find = messages::getMessageById,
    chatName = { chats.getChatById(it)?.contactName },
    isPersonalChat = { groups.getGroupById(it) == null },
    isCleared = { peer, id -> ChatClearRegistry.isCleared(context, peer, id) },
    preflight = guard::preflight,
    recordFailure = { error -> Log.w("MirrorPendingOutgoing", "Pending frame preflight failed: ${error.javaClass.simpleName}") },
)
