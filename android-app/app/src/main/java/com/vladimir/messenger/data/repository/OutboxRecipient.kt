package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity

/** A retry belongs to the original message recipient, not a later chat contact. */
internal object OutboxRecipient {
    fun forMessage(row: MessageEntity, chatContactId: String?): String {
        val raw = row.recipientId.ifBlank { chatContactId.orEmpty() }
        // SQLite text functions and native APIs need not agree on embedded NUL.
        // Never reinterpret a malformed saved address as another contact.
        if ('\u0000' in raw) return ""
        // Match initial/manual sending and support older mirror rows containing
        // an invite link. A nonblank but invalid stored value never falls back to
        // a different person just because the chat now points there.
        return when {
            raw.startsWith("pk_") -> raw
            raw.contains("node=pk_") -> "pk_" + raw.substringAfter("node=pk_").substringBefore("&")
            else -> raw
        }
    }
}
