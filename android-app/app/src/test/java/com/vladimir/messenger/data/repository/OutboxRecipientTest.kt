package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxRecipientTest {
    private fun row(recipient: String) = MessageEntity(
        id = "id", chatId = "chat", senderId = "self", content = "body",
        timestamp = 1, isFromMe = true, recipientId = recipient,
    )

    @Test fun persistedRecipientAlwaysWinsOverChangedChatContact() {
        assertEquals("pk_original", OutboxRecipient.forMessage(row("pk_original"), "pk_current"))
        assertEquals("invalid", OutboxRecipient.forMessage(row("invalid"), "pk_current"))
    }

    @Test fun legacyInviteLinksAreParsedWithoutSubstitutingTheChat() {
        assertEquals("pk_original", OutboxRecipient.forMessage(row("apu://add?node=pk_original&name=hi"), "pk_current"))
        assertEquals("pk_original", OutboxRecipient.forMessage(row("apu://add?node=pk_original"), null))
        assertEquals("pk_first", OutboxRecipient.forMessage(row("node=pk_first&node=pk_second"), "pk_current"))
        assertEquals("pk_current", OutboxRecipient.forMessage(row("\t\u2003"), "apu://add?node=pk_current&name=hi"))
        assertEquals("", OutboxRecipient.forMessage(row("  "), null))
        assertEquals("", OutboxRecipient.forMessage(row("pk_original\u0000suffix"), "pk_current"))
        assertEquals("", OutboxRecipient.forMessage(row("node=pk_original\u0000suffix"), "pk_current"))
        assertEquals("", OutboxRecipient.forMessage(row(""), "pk_current\u0000suffix"))
    }
}
