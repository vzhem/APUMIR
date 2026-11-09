package com.vladimir.messenger.data.chatclear

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClearMirrorReplayTest {
    @Test fun cursorAdvancesOnlyOnMatchingPairedReceipt() {
        val replay = ChatClearMirrorReplay<String>()
        replay.selectOwner("owner")
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("old", "late"))
        replay.stage("friend", batch, "cursor")
        assertNull(replay.cursor)
        assertFalse(replay.acknowledge("other-owner", "friend", batch.operation))
        assertFalse(replay.acknowledge("owner", "other", batch.operation))
        assertFalse(replay.acknowledge("owner", "friend", UUID.randomUUID()))
        assertEquals(batch, replay.pending?.batch) // retry uses the same operation
        assertTrue(replay.acknowledge("owner", "friend", batch.operation))
        assertEquals("cursor", replay.cursor)
        assertNull(replay.pending)
        assertFalse(replay.acknowledge("owner", "friend", batch.operation))
        replay.restartCycle()
        assertNull(replay.cursor)
    }

    @Test fun identityChangeDiscardsInMemoryProgressAndRestartsFromDurableStore() {
        val replay = ChatClearMirrorReplay<String>()
        assertTrue(replay.selectOwner("one"))
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("id"))
        replay.stage("peer", batch, "cursor")
        assertTrue(replay.selectOwner("two"))
        assertNull(replay.pending)
        assertNull(replay.cursor)
        assertFalse(replay.acknowledge("one", "peer", batch.operation))
        assertFalse(replay.selectOwner("two"))
    }

    @Test fun mirrorReceiptUsesSeparateStrictControlFormat() {
        val id = UUID.randomUUID()
        val receipt = ChatClearWire.encodeMirrorAck(id)
        assertEquals(id, ChatClearWire.decodeMirrorAck(receipt))
        assertNull(ChatClearWire.decodeAck(receipt))
        assertNull(ChatClearWire.decodeMirrorAck(receipt + "x"))
        assertEquals(3, receipt.split('|').size)
        assertTrue(ChatClearWire.isControl(receipt))
    }
}
