package com.vladimir.messenger.data.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReplyWireTest {
    @Test
    fun roundTripsUtf8AndDelimiterCharacters() {
        val encoded = DirectReplyWire.build(
            messageId = "reply-message-id",
            replyToId = "quoted-message-id",
            replyAuthor = "Анна | 🦊",
            replyText = "Первая строка\nвторая строка",
        )

        val parsed = DirectReplyWire.parse(encoded)
        assertNotNull(parsed)
        assertEquals("reply-message-id", parsed?.messageId)
        assertEquals("quoted-message-id", parsed?.replyToId)
        assertEquals("Анна | 🦊", parsed?.replyAuthor)
        assertEquals("Первая строка вторая строка", parsed?.replyText)
    }

    @Test
    fun malformedReservedPacketsAreRecognizedButRejected() {
        val malformed = "${DirectReplyWire.PREFIX}|not-base64|"

        assertTrue(DirectReplyWire.isPacket(malformed))
        assertNull(DirectReplyWire.parse(malformed))
        assertFalse(DirectReplyWire.isPacket("обычное сообщение"))
    }

    @Test
    fun replyPreviewIsBoundedAndTransportIdIsStable() {
        val preview = DirectReplyWire.preview("x".repeat(300))
        val firstId = DirectReplyWire.transportMessageId("message-1")

        assertEquals(DirectReplyWire.MAX_PREVIEW_CHARS, preview.length)
        assertEquals(firstId, DirectReplyWire.transportMessageId("message-1"))
        assertNotEquals(firstId, DirectReplyWire.transportMessageId("message-2"))
    }
}
