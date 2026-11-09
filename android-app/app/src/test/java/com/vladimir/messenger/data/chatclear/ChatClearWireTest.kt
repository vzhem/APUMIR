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

    @Test fun rejectsMalformedAndOversizedPackets() {
        val id = UUID.randomUUID()
        assertNull(ChatClearWire.decode("APUCLEAR1|$id|"))
        assertNull(ChatClearWire.decode("APUCLEAR1|$id|YQ,YQ"))
        assertNull(ChatClearWire.decode("APUCLEAR1|$id|YQ=="))
        assertNull(ChatClearWire.decode("APUCLEAR1|$id|${List(33) { "YQ" }.joinToString(",")}"))
        assertNull(ChatClearWire.decode("APUCLEAR1|$id|_w"))
        assertNull(ChatClearWire.decode("x".repeat(8193)))
    }
}
