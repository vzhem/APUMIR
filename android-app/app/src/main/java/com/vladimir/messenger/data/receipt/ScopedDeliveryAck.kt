package com.vladimir.messenger.data.receipt

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.repository.OutboxRecipient
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.util.NodeIds

internal enum class ScopedDeliveryAck { REJECTED, MISSING, ALREADY_COMMITTED, UPDATED }

/**
 * Match a claimed transport sender against the recipient of a personal outgoing
 * row and update it in one Room transaction. Legacy rows without a saved
 * recipient fall back to their current chat contact, just as retries do.
 * Neither that fallback nor a matching claimed node ID authenticates the peer.
 * Identity replacement, other databases, and native/network cleanup are outside
 * this transaction. Old MQTT ACK events have no sender and cannot use this gate.
 */
internal suspend fun applyPeerScopedDeliveryAck(
    database: AppDatabase,
    messageId: String,
    senderId: String,
    update: suspend (String) -> Boolean,
): ScopedDeliveryAck {
    if (messageId.isBlank() || '\u0000' in messageId || !NodeIds.isNodeId(senderId)) return ScopedDeliveryAck.REJECTED
    return database.withTransaction {
        val row = database.messageDao().getMessageById(messageId) ?: return@withTransaction ScopedDeliveryAck.MISSING
        if (!row.isFromMe || row.channel == "GROUP" || !row.topicId.isNullOrBlank()) return@withTransaction ScopedDeliveryAck.REJECTED
        if (database.groupDao().getGroupById(row.chatId) != null) return@withTransaction ScopedDeliveryAck.REJECTED
        val chat = database.chatDao().getChatById(row.chatId) ?: return@withTransaction ScopedDeliveryAck.REJECTED
        if (OutboxRecipient.forMessage(row, chat.contactId) != senderId) return@withTransaction ScopedDeliveryAck.REJECTED
        when {
            update(messageId) -> ScopedDeliveryAck.UPDATED
            row.status == MessageStatus.DELIVERED.name || row.status == MessageStatus.READ.name -> ScopedDeliveryAck.ALREADY_COMMITTED
            else -> ScopedDeliveryAck.REJECTED
        }
    }
}
