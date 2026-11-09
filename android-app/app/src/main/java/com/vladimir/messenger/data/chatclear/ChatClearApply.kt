package com.vladimir.messenger.data.chatclear

/**
 * Coordinates durable suppression before destructive cleanup. Cleanup must be scoped
 * to [chatId] and be idempotent: it can run again after a crash or a lost receipt.
 */
internal class ChatClearApply(
    private val ownerId: () -> String,
    private val chatForPeer: suspend (String) -> String?,
    private val tombstone: suspend (owner: String, peer: String, ids: List<String>) -> Boolean,
    private val cleanup: suspend (chatId: String, ids: List<String>) -> Boolean,
    private val relatedIds: suspend (chatId: String, ids: List<String>) -> List<String> = { _, _ -> emptyList() },
) : ChatClearReceiver.Apply {
    override suspend fun apply(peer: String, batch: ChatClearWire.Batch): Boolean {
        val owner = ownerId()
        if (owner.isBlank() || peer.isBlank() || owner == peer) return false
        val chatId = chatForPeer(peer)?.takeIf { it.isNotBlank() } ?: return false
        // A file may use different message IDs on the sender and receiver. Resolve
        // only transfers in this local chat, and persist every alias before removal.
        val ids = (batch.ids + relatedIds(chatId, batch.ids)).distinct()
        require(ids.size <= ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS) { "Too many related clear IDs" }
        val chunks = ids.chunked(ChatClearWire.MAX_IDS)
        chunks.forEach { ChatClearWire.encode(ChatClearWire.Batch(batch.operation, it)) }
        for (chunk in chunks) if (!tombstone(owner, peer, chunk)) return false
        for (chunk in chunks) if (!cleanup(chatId, chunk)) return false
        return true
    }
}
