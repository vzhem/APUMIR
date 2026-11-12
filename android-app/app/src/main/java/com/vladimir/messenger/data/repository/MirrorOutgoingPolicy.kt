package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.domain.model.MessageStatus

/** Reject malformed or terminal paired requests before creating a local chat. */
internal object MirrorOutgoingPolicy {
    fun validRecipient(raw: String): Boolean {
        val peer = OutboxRecipient.normalize(raw)
        return peer.startsWith("pk_") && peer.length > 3 && '|' !in peer && peer.toByteArray(Charsets.UTF_8).size <= 128
    }

    fun accepts(row: MirrorRow): Boolean {
        if (!row.mine || row.id.isBlank() || '\u0000' in row.id || '|' in row.id ||
            row.id.toByteArray(Charsets.UTF_8).size > 128 || !validRecipient(row.recipientId)
        ) return false
        return if (row.manualRetry) row.status in setOf(
            MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name,
            MessageStatus.SENT.name, MessageStatus.FAILED.name,
        ) else row.status in setOf(
            // Older mirror frames may omit status; their decoder defaults to SENT.
            MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name, MessageStatus.SENT.name,
        )
    }
}
