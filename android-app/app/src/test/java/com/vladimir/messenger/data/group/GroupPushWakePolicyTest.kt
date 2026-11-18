package com.vladimir.messenger.data.group

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPushWakePolicyTest {

    private fun peer(index: Int): String = "pk_" + "%032x".format(index)
    private val me = peer(1)
    private val groupId = "grp_test_push_1"
    private val topicId = "top_general_1"

    @Test fun wakesOnlyForNonBlankHeadGroupAndChannelMessages() {
        val groupMsg = GroupWire.buildMessage(groupId, topicId, "Привет, группа!", "msg-1", "Alice")
        val channelPostWithPhotos = GroupWire.buildMessage(
            groupId, "post-42", "Новый пост канала\nAPUIMGS1:3", "msg-2", "Канал",
        )
        val photoOnlyPostHead = GroupWire.buildMessage(groupId, "post-43", "APUIMGS1:1", "msg-3", "Канал")
        assertTrue(GroupPushWakePolicy.shouldWakeForEnvelope(groupMsg))
        assertTrue(GroupPushWakePolicy.shouldWakeForEnvelope(channelPostWithPhotos))
        assertTrue(GroupPushWakePolicy.shouldWakeForEnvelope(photoOnlyPostHead))

        val photoPart = GroupWire.buildMessage(groupId, "post-42", "APUIMGP1:1/1/3:AAAA", "part-1", "Канал")
        val textPart = GroupWire.buildMessage(groupId, topicId, "APUIMGP1:0/1/2:msg-1/0:хвост", "part-2", "Alice")
        val blankMsg = GroupWire.buildMessage(groupId, topicId, "   ", "msg-blank", "Alice")
        val topicCreated = GroupWire.buildTopicCreated(groupId, "post-42", "Новый пост")
        val roster = GroupWire.buildRoster(groupId, listOf(GroupWire.RosterEntry(me, "Me", "OWNER")))
        val typing = GroupWire.buildTyping(groupId, topicId, typing = true)
        val edit = GroupWire.buildEdit(groupId, topicId, "msg-1", "Исправлено")
        val delete = GroupWire.buildMessageDelete(groupId, "msg-1", me)
        val pollClose = GroupWire.buildPollClose(groupId, "p:msg-1")
        val whoIs = GroupWire.buildWhoIs(me)

        for (nonWake in listOf(
            photoPart, textPart, blankMsg, topicCreated, roster,
            typing, edit, delete, pollClose, whoIs,
            "обычный личный текст", "APUGRP1|msg|broken", "",
        )) {
            assertFalse("must not wake for: $nonWake", GroupPushWakePolicy.shouldWakeForEnvelope(nonWake))
        }
    }

    @Test fun correspondentClassificationAllowsSharedCommunityPacketsAndRejectsGossip() {
        val shared = listOf(
            GroupWire.buildMessage(groupId, topicId, "Пост", "msg-1", "Alice"),
            GroupWire.buildTopicCreated(groupId, "post-1", "Заголовок"),
            GroupWire.buildRoster(groupId, listOf(GroupWire.RosterEntry(me, "Me", "OWNER"))),
            GroupWire.buildJoinDecision(groupId, peer(2), approved = true),
            GroupWire.buildJoinRequest(groupId, "Bob", "привет", "slug1"),
            GroupWire.buildTopicsRequest(groupId),
            GroupWire.buildRosterRequest(groupId),
            GroupWire.buildPin(groupId, topicId, "msg-1", pinned = true),
            GroupWire.buildPollClose(groupId, "p:msg-1"),
            GroupWire.buildSlowMode(groupId, 10),
        )
        for (envelope in shared) {
            assertTrue("expected shared community packet: $envelope",
                GroupPushWakePolicy.isSharedGroupCorrespondentPacket(envelope))
        }

        val gossip = listOf(
            GroupWire.buildTyping(groupId, topicId, typing = true),
            GroupWire.buildWhoIs(me),
            GroupWire.buildJoinStatusQuery(groupId, peer(2)),
            GroupWire.buildNick(me, "alice", 1_700_000_000_000L, 0),
            GroupWire.buildBirthday(me, 15, 6, 1_700_000_000_000L),
            GroupWire.buildEmojiStatus(me, "🔥", 1_700_000_000_000L, 0),
            GroupWire.buildCapabilities(me, 1_048_576L, 1_700_000_000_000L),
            GroupWire.buildUpdateAsk("11.74.540"),
            GroupWire.buildFeaturePackAsk("stickers", "arm64-v8a"),
            "APUGRP1|dir|grp|1|name|desc|slug|owner|false|5|1700000000000",
            "APUSEAL1|opaque",
            "hello",
        )
        for (envelope in gossip) {
            assertFalse("must never disclose epoch on gossip: $envelope",
                GroupPushWakePolicy.isSharedGroupCorrespondentPacket(envelope))
        }
    }

    @Test fun fanoutPlanPrioritizesAndCapsWakesEpochRequestsHellosAndAnnouncements() = runBlocking {
        val envelope = GroupWire.buildMessage(groupId, topicId, "Срочный пост!", "m-99", "Автор")
        val onlineReady = (2..5).map(::peer)
        val offlineReady = (10..50).map(::peer) // 41 > MAX_WAKES_PER_FANOUT (32)
        val offlineNoEpoch = (60..72).map(::peer) // 13 > MAX_EPOCH_REQUESTS_PER_FANOUT (8)
        val offlineCooldown = listOf(peer(80), peer(81))
        val offlineUnpinned = (90..102).map(::peer) // 13 > MAX_HELLOS_PER_FANOUT (8)

        val stateMap = HashMap<String, GroupPushWakePolicy.CandidateState>()
        (onlineReady + offlineReady).forEach { stateMap[it] = GroupPushWakePolicy.CandidateState.EPOCH_READY }
        offlineNoEpoch.forEach { stateMap[it] = GroupPushWakePolicy.CandidateState.PINNED_NO_EPOCH }
        offlineCooldown.forEach { stateMap[it] = GroupPushWakePolicy.CandidateState.EPOCH_COOLDOWN }
        offlineUnpinned.forEach { stateMap[it] = GroupPushWakePolicy.CandidateState.UNPINNED }

        val all = listOf(me, "not-a-node", onlineReady.first()) +
            onlineReady + offlineReady + offlineNoEpoch + offlineCooldown + offlineUnpinned
        val failed = listOf(me, "CUSTD_123", offlineReady.first()) +
            offlineReady + offlineNoEpoch + offlineCooldown + offlineUnpinned

        val plan = GroupPushWakePolicy.planFanout(
            ownNodeId = me,
            allRecipients = all,
            failedRecipients = failed,
            wakeRequested = true,
            envelope = envelope,
        ) { id -> stateMap.getValue(id) }

        assertEquals(offlineReady.take(GroupPushWakePolicy.MAX_WAKES_PER_FANOUT), plan.wakePeers)
        assertTrue(onlineReady.none { it in plan.wakePeers })
        assertEquals(offlineNoEpoch.take(GroupPushWakePolicy.MAX_EPOCH_REQUESTS_PER_FANOUT), plan.requestEpochPeers)
        assertTrue(offlineCooldown.none { it in plan.wakePeers || it in plan.requestEpochPeers })
        assertEquals(offlineUnpinned.take(GroupPushWakePolicy.MAX_HELLOS_PER_FANOUT), plan.helloPeers)
        assertEquals(GroupPushWakePolicy.MAX_ANNOUNCEMENTS_PER_FANOUT, plan.announcePeers.size)
        assertTrue(me !in plan.wakePeers && me !in plan.helloPeers && me !in plan.announcePeers)
    }

    @Test fun membershipFanoutBootstrapsKeysAndEpochsWithoutSendingAnyWake() = runBlocking {
        val subscriberUnpinned = peer(20)
        val subscriberPinned = peer(21)
        val subscriberNoEpoch = peer(22)
        val roster = GroupWire.buildRoster(
            groupId,
            listOf(
                GroupWire.RosterEntry(me, "Owner", "OWNER"),
                GroupWire.RosterEntry(subscriberUnpinned, "NewSub", "MEMBER"),
                GroupWire.RosterEntry(subscriberPinned, "OldSub", "MEMBER"),
                GroupWire.RosterEntry(subscriberNoEpoch, "MidSub", "MEMBER"),
            ),
        )
        val recipients = listOf(subscriberUnpinned, subscriberPinned, subscriberNoEpoch)
        val plan = GroupPushWakePolicy.planFanout(
            ownNodeId = me,
            allRecipients = recipients,
            failedRecipients = recipients,
            // Even if a caller mistakenly asked to wake, non-message packets never wake.
            wakeRequested = true,
            envelope = roster,
        ) { id ->
            when (id) {
                subscriberUnpinned -> GroupPushWakePolicy.CandidateState.UNPINNED
                subscriberPinned -> GroupPushWakePolicy.CandidateState.EPOCH_READY
                else -> GroupPushWakePolicy.CandidateState.PINNED_NO_EPOCH
            }
        }
        assertTrue(plan.wakePeers.isEmpty())
        assertTrue(plan.requestEpochPeers.isEmpty())
        assertEquals(listOf(subscriberUnpinned), plan.helloPeers)
        assertEquals(listOf(subscriberPinned, subscriberNoEpoch), plan.announcePeers)
    }

    @Test fun perMemberFanoutReportsFailedPeersAndWakeFlagToHook() = runBlocking {
        val online = peer(2)
        val sleeping = peer(3)
        var observedFailed: List<String> = emptyList()
        var observedWake = false
        val delivery = PerMemberFanoutDelivery(
            send = { _, recipient, _ -> recipient == online },
            afterDelivery = { _, _, _, report, wake ->
                observedFailed = report.failed
                observedWake = wake
            },
        )
        val msg = GroupWire.buildMessage(groupId, topicId, "Тест", "m-1", "Alice")
        val report = delivery.deliver(groupId, msg, listOf(online, sleeping), wakeOnUnreachable = true)
        assertEquals(2, report.attempted)
        assertEquals(1, report.delivered)
        assertEquals(listOf(sleeping), report.failed)
        assertEquals(listOf(sleeping), observedFailed)
        assertTrue(observedWake)
    }
}
