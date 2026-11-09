package com.vladimir.messenger.data.chatclear

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.util.ChatPreviews
import javax.inject.Inject

/** Transactional, chat-scoped removal of known message rows and associated chat metadata. */
internal class ChatClearLocalCleanup @Inject constructor(private val db: AppDatabase) {
    suspend fun apply(chatId: String, ids: List<String>): Boolean {
        require(chatId.isNotBlank() && ids.size in 1..ChatClearWire.MAX_IDS)
        return db.withTransaction {
            val chats = db.chatDao()
            if (chats.getChatById(chatId) == null) return@withTransaction false
            val messages = db.messageDao()
            db.messageReactionDao().deleteChatMessages(chatId, ids)
            db.directPollDao().deleteVotesForChatMessages(chatId, ids)
            db.directPollDao().deleteForChatMessages(chatId, ids)
            // Only chat-visible transfer records are removed. Shared story/avatar
            // transfers and independently saved files are deliberately untouched.
            db.fileTransferDao().deleteChatMessageTransfers(chatId, ids)
            messages.deleteChatMessages(chatId, ids)
            val last = messages.getLatest(chatId)
            if (last == null) {
                chats.updateLastMessage(chatId, "", 0L)
                chats.markAsRead(chatId)
            } else {
                chats.updateLastMessage(chatId, ChatPreviews.human(last.content) ?: last.content, last.timestamp)
            }
            true
        }
    }
}
