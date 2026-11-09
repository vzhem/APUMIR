package com.vladimir.messenger.data.chatclear

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase

/** Capture the local messages and both file ID forms at one database boundary. */
internal class ChatClearSnapshot(
    private val db: AppDatabase,
    private val afterMessages: suspend () -> Unit = {},
) {
    data class Result(val peer: String, val ids: List<String>)

    suspend fun capture(chatId: String): Result = db.withTransaction {
        val chat = db.chatDao().getChatById(chatId) ?: error("Chat not found")
        require(chat.contactId.isNotBlank())
        val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
        val messages = db.messageDao().chatMessageIdsForClear(chatId, limit)
        afterMessages() // Test seam: concurrent writes must not enter this snapshot.
        val ids = (messages + db.fileTransferDao().chatTransferMessageIdsForClear(chatId, limit) +
            db.fileTransferDao().chatTransferAliasesForClear(chatId, limit)).distinct()
        require(ids.size < limit) { "Chat clear snapshot exceeds queue capacity" }
        Result(chat.contactId, ids)
    }
}
