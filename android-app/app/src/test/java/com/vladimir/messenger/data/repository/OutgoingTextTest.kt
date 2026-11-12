package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.reply.DirectReplyWire
import com.vladimir.messenger.data.textcolor.TextColorWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingTextTest {
    @Test fun shadowAndActiveRowsRetainTheSameReplyAndPalette() {
        val reply = DirectReplyWire.Target("quoted-id", "Alice", "quoted\n  text")
        val row = outgoingTextRow("message", "chat", "pk_friend", "answer", 123L, reply, "red", "blue")
        assertEquals("message", row.id)
        assertEquals("chat", row.chatId)
        assertEquals("pk_friend", row.recipientId)
        assertEquals("PENDING", row.status)
        assertEquals("UNKNOWN", row.channel)
        assertEquals(true, row.isFromMe)
        assertEquals("quoted-id", row.replyToId)
        assertEquals("Alice", row.replyAuthor)
        assertEquals("quoted text", row.replyText)
        assertEquals("red", row.textColor)
        assertEquals("blue", row.senderNameColor)
        assertEquals("answer", TextColorWire.decode(TextColorWire.wrap(row.content, row.textColor, row.senderNameColor))!!.text)
    }

    @Test fun nonReplyHasNoQuoteFields() {
        val row = outgoingTextRow("id", "chat", "pk_peer", "text", 1L, null, "", "")
        assertNull(row.replyToId)
        assertEquals("", row.replyAuthor)
        assertEquals("", row.replyText)
    }

    @Test fun pairedColorsWinAndAreNormalizedIncludingAnExplicitDefault() {
        var localReads = 0
        fun local(): Pair<String, String> { localReads++; return "pink" to "green" }
        assertEquals("red" to "blue", outgoingTextColors(true, "red" to "blue", ::local))
        assertEquals("" to "", outgoingTextColors(true, "" to "invalid", ::local))
        assertEquals(0, localReads)
        assertEquals("pink" to "green", outgoingTextColors(false, "red" to "blue", ::local))
        assertEquals("pink" to "green", outgoingTextColors(true, null, ::local))
        assertEquals(2, localReads)
    }
}
