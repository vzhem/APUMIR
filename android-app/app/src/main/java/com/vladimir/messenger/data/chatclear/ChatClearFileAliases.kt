package com.vladimir.messenger.data.chatclear

import com.vladimir.messenger.data.local.dao.FileTransferDao

/** Resolve sender message IDs and recipient placeholders without trusting a wire chat ID. */
internal class ChatClearFileAliases(private val transfers: FileTransferDao) {
    suspend fun relatedIds(chatId: String, ids: List<String>): List<String> {
        val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
        val first = transfers.chatTransfersForClear(chatId, ids, limit)
        require(first.size < limit) { "Too many related transfers" }
        val messageIds = first.map { it.messageId }.distinct()
        val result = linkedSetOf<String>()
        for (part in messageIds.chunked(ChatClearWire.MAX_IDS)) {
            val rows = transfers.chatTransfersForMessageIds(chatId, part, limit)
            require(rows.size < limit) { "Too many related transfers" }
            for (row in rows) {
                result.add(row.messageId)
                result.add("file-${row.transferId}")
                require(result.size < limit) { "Too many related file IDs" }
            }
        }
        return result.toList()
    }
}
