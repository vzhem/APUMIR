package com.vladimir.messenger.data.chatclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ChatClearWireTest {
    @Test fun roundTripExactIds() {
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("one", "two|,тест"))
        assertEquals(batch, ChatClearWire.decode(ChatClearWire.encode(batch)))
    }

    @Test fun acknowledgmentIsStrictAndLegacyDeleteSeesOnlyThreeFields() {
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("id"))
        val packet = ChatClearWire.encode(batch)
        val ack = ChatClearWire.encodeAck(batch.operation)
        assertEquals(3, packet.split('|').size)
        assertEquals(3, ack.split('|').size)
        assertEquals(batch.operation, ChatClearWire.decodeAck(ack))
        assertNull(ChatClearWire.decodeAck(ack + "junk"))
    }

    @Test fun rejectsMalformedAndOversizedPackets() {
        val id = UUID.randomUUID()
        assertNull(ChatClearWire.decode("APUDEL1|clear2|$id."))
        assertNull(ChatClearWire.decode("APUDEL1|clear2|$id.YQ,YQ"))
        assertNull(ChatClearWire.decode("APUDEL1|clear2|$id.YQ=="))
        assertNull(ChatClearWire.decode("APUDEL1|clear2|$id.${List(33) { "YQ" }.joinToString(",")}"))
        assertNull(ChatClearWire.decode("APUDEL1|clear2|$id._w"))
        assertNull(ChatClearWire.decode("x".repeat(8193)))
    }
}
