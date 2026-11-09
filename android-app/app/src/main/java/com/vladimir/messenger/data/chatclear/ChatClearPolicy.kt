package com.vladimir.messenger.data.chatclear

import java.util.UUID

/** Pure planning and authorization; never authorizes an unauthenticated destructive command. */
internal object ChatClearPolicy {
    fun batches(ids: List<String>, newId: () -> UUID = UUID::randomUUID): List<ChatClearWire.Batch> =
        ids.distinct().chunked(ChatClearWire.MAX_IDS).map { part ->
            ChatClearWire.Batch(newId(), part).also { ChatClearWire.encode(it) }
        }

    /** Select only IDs actually belonging to this local conversation, never a whole chat by timestamp. */
    fun permittedIds(
        authenticated: Boolean,
        senderId: String,
        chatPeerId: String,
        batch: ChatClearWire.Batch,
        localMessageChats: Map<String, String>,
        localChatId: String,
    ): List<String> {
        if (!authenticated || senderId.isBlank() || senderId != chatPeerId || localChatId.isBlank()) return emptyList()
        return batch.ids.filter { localMessageChats[it] == localChatId }
    }
}
