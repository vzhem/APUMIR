package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.domain.model.MessageStatus

/**
 * A transport event is not a durable inbox commit. A repeated message may be
 * acknowledged only when the matching incoming Room row already exists.
 */
internal suspend fun storedIncomingText(
    messageId: String,
    senderId: String,
    chatId: String,
    inserted: Boolean,
    load: suspend (String) -> MessageEntity?,
): Boolean {
    if (inserted) return true
    val row = load(messageId) ?: return false
    return !row.isFromMe && row.senderId == senderId && row.chatId == chatId
}

/**
 * Only release native origin custody after Room committed an outgoing ACK.
 * Retry cleanup for a duplicate ACK after a crash between the Room commit and
 * native cleanup, without changing diagnostics or downgrading READ.
 */
internal suspend fun applyOutgoingDeliveryAck(
    messageId: String,
    update: suspend (String) -> Boolean,
    load: suspend (String) -> MessageEntity?,
    release: (String) -> Unit,
): Boolean {
    val changed = update(messageId)
    val alreadyCommitted = if (changed) true else load(messageId)?.let { row ->
        row.isFromMe && (row.status == MessageStatus.DELIVERED.name || row.status == MessageStatus.READ.name)
    } == true
    if (alreadyCommitted) release(messageId)
    return changed
}
