package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatClearReceiverTest {
    @Test fun rejectsUntrustedAndAcknowledgesOnlyCompletedCleanup() = runTest {
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("one"))
        val wire = ChatClearWire.encode(batch)
        val replies = mutableListOf<Pair<String, String>>()
        var applied = 0
        var ready = false
        val receiver = ChatClearReceiver({ _, _ -> applied++; ready }, { peer, text -> replies.add(peer to text) })
        assertFalse(receiver.receive("peer", true, "hello"))
        assertTrue(receiver.receive("peer", false, wire))
        assertTrue(receiver.receive("", true, wire))
        assertTrue(receiver.receive("peer", true, "APUDEL1|clear2|invalid"))
        assertEquals(0, applied)
        assertTrue(receiver.receive("peer", true, wire))
        assertTrue(replies.isEmpty())
        ready = true
        assertTrue(receiver.receive("peer", true, wire))
        assertEquals(2, applied)
        assertEquals(listOf("peer" to ChatClearWire.encodeAck(batch.operation)), replies)
    }
}
