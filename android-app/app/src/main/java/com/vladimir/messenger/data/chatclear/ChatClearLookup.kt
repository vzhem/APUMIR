package com.vladimir.messenger.data.chatclear

/**
 * Resolve identity before consulting storage, even on a fresh installation.
 * A failed lookup must not be interpreted as proof that a message is safe.
 */
internal class ChatClearLookup(
    private val owner: () -> String,
    private val contains: (String, String, String) -> Boolean,
) {
    fun isCleared(peer: String, messageId: String): Boolean {
        if (peer.isBlank() || messageId.isBlank()) return false
        val identity = owner()
        check(identity.isNotBlank()) { "Chat clear identity not ready" }
        return contains(identity, peer, messageId)
    }
}
