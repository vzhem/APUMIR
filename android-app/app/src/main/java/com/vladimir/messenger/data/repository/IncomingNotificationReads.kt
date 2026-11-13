package com.vladimir.messenger.data.repository

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.service.IncomingNotificationGate

/**
 * In the same process, serialize a direct-chat read with notification posting.
 * The unread count and currently pending direct rows change in one Room
 * transaction; an arrival committed after it still has its own pending flag.
 * Group/topic reads use the same gate separately; external process/restore
 * and direct deferred-notification paths are not coordinated here.
 */
internal class IncomingNotificationReads(
    private val db: AppDatabase,
    private val cancelChat: (String) -> Unit,
) {
    suspend fun markRead(chatId: String) = IncomingNotificationGate.run {
        db.withTransaction {
            db.chatDao().markAsRead(chatId)
            db.messageDao().clearPendingForReadDirectChat(chatId)
        }
        cancelChat(chatId)
    }

    suspend fun toggleRead(chatId: String) = IncomingNotificationGate.run {
        val markedRead = db.withTransaction {
            val chats = db.chatDao()
            val chat = chats.getChatById(chatId) ?: return@withTransaction false
            if (chat.unreadCount > 0) db.messageDao().clearPendingForReadDirectChat(chatId)
            chats.toggleReadState(chatId)
            chat.unreadCount > 0
        }
        if (markedRead) cancelChat(chatId)
    }
}
