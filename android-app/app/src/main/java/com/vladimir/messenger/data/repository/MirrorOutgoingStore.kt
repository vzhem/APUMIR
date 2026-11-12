package com.vladimir.messenger.data.repository

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus

/**
 * Stages paired-device outgoing rows without replacing another Room row. The
 * clear store is separate from Room: its writer must commit suppression before
 * scheduling chat-scoped cleanup. These checks are not a network barrier and
 * cannot revoke packets already sent or protect an identity change/restore.
 */
internal sealed interface MirrorStage {
    data object Inserted : MirrorStage
    data class Existing(val message: MessageEntity) : MirrorStage
    data object Rejected : MirrorStage
}

internal class MirrorOutgoingStore(
    private val database: AppDatabase,
    private val isCleared: (peer: String, messageId: String) -> Boolean,
) {
    /** Only a newly inserted row may start an initial send. */
    suspend fun stageNew(message: MessageEntity, peer: String, preview: String): MirrorStage =
        database.withTransaction {
            if (!eligible(message, peer)) return@withTransaction MirrorStage.Rejected
            val messages = database.messageDao()
            if (messages.insertMessageIgnore(message) == -1L) {
                val current = messages.getMessageById(message.id)
                return@withTransaction if (current != null && sameMessage(current, message, peer) && !suppressed(message, peer)) {
                    MirrorStage.Existing(current)
                } else MirrorStage.Rejected
            }
            if (suppressed(message, peer)) {
                // This transaction inserted the row; never delete an existing
                // winner just because a conflicting frame reused its ID.
                check(messages.deleteByIdChatAndSender(message.id, message.chatId, message.senderId) == 1)
                return@withTransaction MirrorStage.Rejected
            }
            database.chatDao().updateLastMessage(message.chatId, preview, message.timestamp)
            MirrorStage.Inserted
        }

    /**
     * An explicit retry may recreate a missing paired row, but an existing row
     * remains authoritative. A reused ID from a different chat, recipient or
     * body cannot be turned into a resend of that row.
     */
    suspend fun prepareRetry(candidate: MessageEntity, peer: String): MessageEntity? =
        database.withTransaction {
            if (candidate.status !in RETRYABLE || !eligible(candidate, peer)) return@withTransaction null
            val messages = database.messageDao()
            val inserted = if (messages.getMessageById(candidate.id) == null) {
                messages.insertMessageIgnore(candidate) != -1L
            } else false
            val current = messages.getMessageById(candidate.id) ?: return@withTransaction null
            if (!sameMessage(current, candidate, peer) || current.status !in RETRYABLE) return@withTransaction null
            if (suppressed(candidate, peer)) {
                if (inserted) check(messages.deleteByIdChatAndSender(candidate.id, candidate.chatId, candidate.senderId) == 1)
                return@withTransaction null
            }
            current
        }

    /** Apply an echo only to the same local message; a late echo cannot undo a receipt. */
    suspend fun applyEcho(candidate: MessageEntity, peer: String, preview: String) =
        database.withTransaction {
            if (!eligible(candidate, peer)) return@withTransaction
            val messages = database.messageDao()
            val inserted = messages.insertMessageIgnore(candidate) != -1L
            if (inserted) {
                if (suppressed(candidate, peer)) {
                    check(messages.deleteByIdChatAndSender(candidate.id, candidate.chatId, candidate.senderId) == 1)
                } else {
                    database.chatDao().updateLastMessage(candidate.chatId, preview, candidate.timestamp)
                }
                return@withTransaction
            }
            val current = messages.getMessageById(candidate.id) ?: return@withTransaction
            if (!sameMessage(current, candidate, peer) || suppressed(candidate, peer)) return@withTransaction
            // A delayed pending/queued echo must not downgrade a successful
            // direct send. The DAO also excludes DELIVERED and READ updates.
            if (current.status == MessageStatus.SENT.name &&
                candidate.status in setOf(MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name)
            ) return@withTransaction
            messages.updateOutgoingRetryResult(candidate.id, candidate.status, MessageChannel.LOCAL.name)
            Unit
        }

    private suspend fun eligible(message: MessageEntity, peer: String): Boolean {
        if (!message.isFromMe || message.senderId != "self" || message.id.isBlank() ||
            message.chatId.isBlank() || peer.isBlank() || '\u0000' in message.id
        ) return false
        val recipient = OutboxRecipient.normalize(peer)
        if (!recipient.startsWith("pk_") || OutboxRecipient.forMessage(message, peer) != recipient) return false
        if (database.chatDao().getChatById(message.chatId)?.contactId != peer) return false
        return !suppressed(message, peer)
    }

    private fun suppressed(message: MessageEntity, peer: String): Boolean {
        val recipient = OutboxRecipient.forMessage(message, peer)
        return isCleared(peer, message.id) || (recipient != peer && isCleared(recipient, message.id))
    }

    private fun sameMessage(current: MessageEntity, candidate: MessageEntity, peer: String): Boolean =
        current.isFromMe && current.chatId == candidate.chatId && current.content == candidate.content &&
            OutboxRecipient.forMessage(current, peer) == OutboxRecipient.forMessage(candidate, peer)

    private companion object {
        val RETRYABLE = setOf(
            MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name,
            MessageStatus.SENT.name, MessageStatus.FAILED.name,
        )
    }
}
