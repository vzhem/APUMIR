package com.vladimir.messenger.data.chatclear

/**
 * Receiver boundary. Implementations of [Apply] must durably record the request,
 * tombstone the IDs and finish local cleanup before returning true. A false result
 * deliberately sends no acknowledgement, so delivery can be retried.
 */
internal class ChatClearReceiver(
    private val apply: Apply,
    private val sendAck: suspend (String, String) -> Unit,
) {
    fun interface Apply {
        suspend fun apply(peer: String, batch: ChatClearWire.Batch): Boolean
    }

    /** Returns true even for invalid controls, which must never become chat text. */
    suspend fun receive(peer: String, authenticated: Boolean, text: String): Boolean {
        if (!ChatClearWire.isControl(text)) return false
        if (!authenticated || peer.isBlank()) return true
        val batch = ChatClearWire.decode(text) ?: return true
        if (apply.apply(peer, batch)) sendAck(peer, ChatClearWire.encodeAck(batch.operation))
        return true
    }
}
