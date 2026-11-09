package com.vladimir.messenger.data.chatclear

/**
 * Coordinates durable suppression before destructive cleanup. Cleanup must be scoped
 * to [chatId] and be idempotent: it can run again after a crash or a lost receipt.
 * This coordinator is deliberately not connected to network or UI yet.
 */
internal class ChatClearApply(
    private val ownerId: () -> String,
    private val chatForPeer: suspend (String) -> String?,
    private val tombstone: suspend (owner: String, peer: String, ids: List<String>) -> Boolean,
    private val cleanup: suspend (chatId: String, ids: List<String>) -> Boolean,
) : ChatClearReceiver.Apply {
    override suspend fun apply(peer: String, batch: ChatClearWire.Batch): Boolean {
        val owner = ownerId()
        if (owner.isBlank() || peer.isBlank() || owner == peer) return false
        val chatId = chatForPeer(peer)?.takeIf { it.isNotBlank() } ?: return false
        // Mark ALL IDs, including messages that have not arrived yet. The cleanup
        // implementation must filter by chat ID and never trust a wire chat ID.
        if (!tombstone(owner, peer, batch.ids)) return false
        return cleanup(chatId, batch.ids)
    }
}
