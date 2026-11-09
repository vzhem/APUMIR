package com.vladimir.messenger.data.chatclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatClearPolicyTest {
    @Test fun batchesAreBoundedAndDistinct() {
        val ids = (1..75).map { "message-$it" } + "message-1"
        val batches = ChatClearPolicy.batches(ids)
        assertEquals(listOf(32, 32, 11), batches.map { it.ids.size })
        assertEquals(ids.distinct(), batches.flatMap { it.ids })
        assertEquals(3, batches.map { it.operation }.distinct().size)
        batches.forEach { assertEquals(it, ChatClearWire.decode(ChatClearWire.encode(it))) }
    }

    @Test fun untrustedOrDifferentPeerCannotDelete() {
        val packet = ChatClearWire.Batch(UUID.randomUUID(), listOf("in-chat", "other-chat", "missing"))
        val rows = mapOf("in-chat" to "chat-a", "other-chat" to "chat-b")
        assertTrue(ChatClearPolicy.permittedIds(false, "peer", "peer", packet, rows, "chat-a").isEmpty())
        assertTrue(ChatClearPolicy.permittedIds(true, "attacker", "peer", packet, rows, "chat-a").isEmpty())
        assertEquals(listOf("in-chat"), ChatClearPolicy.permittedIds(true, "peer", "peer", packet, rows, "chat-a"))
    }
}
