package com.vladimir.messenger.data.repository

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.MessageEntity

/**
 * The direct-message row, pending-notification flag, preview and unread count
 * commit together. A duplicate or missing chat changes none of them; a storage
 * error or coroutine cancellation rolls the transaction back so a sender may
 * retry. This is not a cross-transport tombstone, identity check, notification
 * display guarantee, or a substitute for authenticating the sender.
 *
 * [suppressed] reads the existing cancellation/clear stores and must not call
 * back into Room or suspend. Those stores are separate from Room: their writers
 * must still persist their tombstone before scheduling idempotent Room cleanup.
 * Identity changes and database restore are not serialized by this class.
 */
internal class IncomingMessageStore(private val database: AppDatabase) {
    suspend fun save(
        message: MessageEntity,
        preview: String,
        countUnread: Boolean,
        suppressed: () -> Boolean,
    ): Boolean {
        require(!message.isFromMe)
        return database.withTransaction {
            val messages = database.messageDao()
            if (messages.insertMessageIgnore(message) == -1L) return@withTransaction false
            if (suppressed() || database.chatDao().recordIncoming(
                    message.chatId, preview, message.timestamp, if (countUnread) 1 else 0,
                ) != 1
            ) {
                // The row was inserted by this transaction only. Do not delete
                // a pre-existing row when a different sender reused the ID.
                check(messages.deleteByIdChatAndSender(message.id, message.chatId, message.senderId) == 1)
                return@withTransaction false
            }
            true
        }
    }
}
