package com.vladimir.messenger.data.chatclear

import java.util.UUID

/** A peer receipt must not discard the only durable local cleanup work. */
internal class ChatClearAcknowledgements(
    private val outbox: ChatClearOutbox,
    private val ownerId: () -> String,
    private val applyLocal: suspend (ChatClearOutbox.Entry) -> Boolean,
) {
    /** Caller serializes this with enqueue and the delivery pump. */
    suspend fun receive(peer: String, authenticated: Boolean, operation: UUID): Boolean {
        if (!authenticated || peer.isBlank()) return false
        val owner = ownerId()
        if (owner.isBlank()) return false
        val entry = outbox.pending().firstOrNull {
            it.owner == owner && it.peer == peer && it.batch.operation == operation
        } ?: return false
        if (!applyLocal(entry) || ownerId() != owner) return false
        return outbox.acknowledge(true, owner, peer, operation)
    }
}
