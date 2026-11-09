package com.vladimir.messenger.data.chatclear

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.util.ChatPreviews
import android.content.Context
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Transactional, chat-scoped removal of known message rows and associated chat metadata. */
internal class ChatClearLocalCleanup(
    private val db: AppDatabase,
    private val removePrivateFiles: (FileTransferEntity) -> Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context, db: AppDatabase) :
        this(db, ChatClearPrivateFileCleaner(context)::remove)

    internal val transferDao get() = db.fileTransferDao()

    suspend fun apply(chatId: String, ids: List<String>): Boolean {
        require(chatId.isNotBlank() && ids.size in 1..ChatClearWire.MAX_IDS)
        if (db.chatDao().getChatById(chatId) == null) return false
        val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
        val rows = transferDao.chatTransfersForMessageIds(chatId, ids, limit)
        if (rows.size >= limit) return false
        // Keep rows until private data is removed, so a crash or filesystem error
        // leaves the information needed for an idempotent retry.
        for (row in rows) if (!removePrivateFiles(row)) return false
        return db.withTransaction {
            val chats = db.chatDao()
            if (chats.getChatById(chatId) == null) return@withTransaction false
            val current = transferDao.chatTransfersForMessageIds(chatId, ids, limit)
            if (current.associateBy { it.transferId } != rows.associateBy { it.transferId }) return@withTransaction false
            val messages = db.messageDao()
            db.messageReactionDao().deleteChatMessages(chatId, ids)
            db.directPollDao().deleteVotesForChatMessages(chatId, ids)
            db.directPollDao().deleteForChatMessages(chatId, ids)
            // Exclude shared story/avatar transfers; exported files remain untouched.
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
