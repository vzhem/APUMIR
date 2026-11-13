package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.receipt.DeliveryAckWire
import com.vladimir.messenger.data.reply.DirectReplyWire
import com.vladimir.messenger.domain.model.MessageStatus

/** An unknown sealed self-echo may itself be an ACK and is not safe to acknowledge. */
internal fun canAcknowledgeSelfEcho(sealed: Boolean, opened: String?): Boolean =
    (!sealed || opened != null) && !DirectReplyWire.isPacket(opened) && !DeliveryAckWire.isPacket(opened)

/** A decrypted CF duplicate is not necessarily signed by its claimed sender. */
internal fun canAcknowledgeCfDuplicate(
    isOutgoing: Boolean,
    storedSender: String,
    claimedSender: String,
    sealed: Boolean,
    openedByExpectedSender: () -> Boolean,
): Boolean = !isOutgoing && storedSender == claimedSender && (!sealed || openedByExpectedSender())

/** Distinguish a repeated text from either a current or accidentally stored ACK. */
internal sealed interface CfDuplicateAction {
    data object Ignore : CfDuplicateAction
    data object AcknowledgeText : CfDuplicateAction
    data class ApplyAck(val messageId: String?) : CfDuplicateAction
}

internal fun classifyCfDuplicate(
    isOutgoing: Boolean,
    storedSender: String,
    storedText: String,
    claimedSender: String,
    incomingText: String,
    sealed: Boolean,
    openedByExpectedSender: () -> Boolean,
): CfDuplicateAction {
    if (!canAcknowledgeCfDuplicate(isOutgoing, storedSender, claimedSender, sealed, openedByExpectedSender)) {
        return CfDuplicateAction.Ignore
    }
    val ackText = if (DeliveryAckWire.isPacket(incomingText)) incomingText else storedText
    return if (DeliveryAckWire.isPacket(ackText)) CfDuplicateAction.ApplyAck(DeliveryAckWire.messageId(ackText))
    else CfDuplicateAction.AcknowledgeText
}

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
 * The legacy mesh receipt may clean copies on old peers, so an existing row is
 * not enough: the just-opened envelope must also match its claimed sender.
 * This is only a node-ID check, not proof against an unpinned legacy identity.
 * Ordinary compatible ACKs remain available for an unsealed/legacy message.
 */
internal suspend fun acknowledgeStoredIncomingText(
    messageId: String,
    senderId: String,
    chatId: String,
    inserted: Boolean,
    load: suspend (String) -> MessageEntity?,
    openedByExpectedSender: () -> Boolean,
    sendAck: (Boolean) -> Unit,
): Boolean {
    if (!storedIncomingText(messageId, senderId, chatId, inserted, load)) return false
    sendAck(openedByExpectedSender())
    return true
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
