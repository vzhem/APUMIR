package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatClearApplyTest {
    private val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("old", "late"))

    @Test fun tombstonesAreCommittedBeforeCleanupAndUseOnlyLocalChat() = runTest {
        val events = mutableListOf<String>()
        val apply = ChatClearApply(
            ownerId = { "owner" },
            chatForPeer = { if (it == "friend") "local-chat" else null },
            tombstone = { owner, peer, ids -> events.add("$owner/$peer/${ids.joinToString()}"); true },
            cleanup = { chat, ids -> events.add("$chat/${ids.joinToString()}"); true },
        )
        assertFalse(apply.apply("unknown", batch))
        assertTrue(events.isEmpty())
        assertTrue(apply.apply("friend", batch))
        assertEquals(listOf("owner/friend/old, late", "local-chat/old, late"), events)
    }

    @Test fun noCleanupOrAcknowledgementIfTombstonesCannotBePersisted() = runTest {
        var cleaned = false
        val apply = ChatClearApply({ "owner" }, { "local-chat" }, { _, _, _ -> false }, { _, _ -> cleaned = true; true })
        assertFalse(apply.apply("friend", batch))
        assertFalse(cleaned)
    }

    @Test fun allRelatedFileAliasesAreTombstonedBeforeAnyCleanup() = runTest {
        val events = mutableListOf<String>()
        val related = (0..39).map { "alias-$it" }
        val apply = ChatClearApply(
            ownerId = { "owner" }, chatForPeer = { "chat" },
            tombstone = { _, _, ids -> events.add("remember:${ids.size}"); true },
            cleanup = { _, ids -> events.add("cleanup:${ids.size}"); true },
            relatedIds = { chat, ids -> assertEquals("chat", chat); assertEquals(batch.ids, ids); related },
        )
        assertTrue(apply.apply("friend", batch))
        assertEquals(listOf("remember:32", "remember:10", "cleanup:32", "cleanup:10"), events)
    }

    @Test fun failureRecordingRelatedAliasesPreventsAllCleanup() = runTest {
        var calls = 0
        var cleaned = false
        val apply = ChatClearApply(
            ownerId = { "owner" }, chatForPeer = { "chat" },
            tombstone = { _, _, _ -> ++calls < 2 },
            cleanup = { _, _ -> cleaned = true; true },
            relatedIds = { _, _ -> (0..39).map { "alias-$it" } },
        )
        assertFalse(apply.apply("friend", batch))
        assertFalse(cleaned)
    }

    @Test fun failedCleanupCanBeRetriedWithoutAcknowledgingPrematurely() = runTest {
        val tombstones = mutableSetOf<String>()
        var attempts = 0
        val replies = mutableListOf<String>()
        val apply = ChatClearApply({ "owner" }, { "chat" }, { _, _, ids -> tombstones.addAll(ids); true }, { _, _ -> ++attempts > 1 })
        val receiver = ChatClearReceiver(apply) { _, text -> replies.add(text) }
        val packet = ChatClearWire.encode(batch)
        receiver.receive("friend", true, packet)
        assertEquals(setOf("old", "late"), tombstones)
        assertTrue(replies.isEmpty())
        receiver.receive("friend", true, packet)
        assertEquals(listOf(ChatClearWire.encodeAck(batch.operation)), replies)
    }
}
