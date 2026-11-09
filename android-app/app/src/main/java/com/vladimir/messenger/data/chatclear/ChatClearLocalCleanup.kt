package com.vladimir.messenger.data.chatclear

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.util.ChatPreviews
import android.content.Context
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Transactional, chat-scoped removal of known message rows and associated chat metadata. */
internal class ChatClearLocalCleanup(
    private val db: AppDatabase,
    private val rememberFiles: (ChatEntity, List<FileTransferEntity>) -> Boolean = { _, _ -> true },
    private val removePrivateFiles: (FileTransferEntity) -> Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context, db: AppDatabase) :
        this(context, db, ChatClearFileJournal(context))

    internal constructor(context: Context, db: AppDatabase, journal: ChatClearFileJournal) : this(
        db,
        { chat, rows -> journal.remember(DeferredInboxStore.owner(context), chat.contactId, chat.id, rows) },
        ChatClearPrivateFileCleaner(context)::remove,
    )

    internal val transferDao get() = db.fileTransferDao()

    suspend fun apply(chatId: String, ids: List<String>): Boolean {
        require(chatId.isNotBlank() && ids.size in 1..ChatClearWire.MAX_IDS)
        val chat = db.chatDao().getChatById(chatId) ?: return false
        val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
        val rows = transferDao.chatTransfersForMessageIds(chatId, ids, limit)
        if (rows.size >= limit) return false
        // Record first: after Room rows are removed, in-flight writers may still
        // recreate a private file and the journal must be able to clean it again.
        if (!rememberFiles(chat, rows)) return false
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
