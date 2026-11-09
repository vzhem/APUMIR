package com.vladimir.messenger.data.chatclear

import java.util.UUID

/** In-memory replay cursor. The durable tombstone store is the source of truth after restart. */
internal class ChatClearMirrorReplay<Cursor> {
    data class Pending<Cursor>(val peer: String, val batch: ChatClearWire.Batch, val cursor: Cursor)

    var owner: String = ""
        private set
    var cursor: Cursor? = null
        private set
    var pending: Pending<Cursor>? = null
        private set

    fun selectOwner(value: String): Boolean {
        if (owner == value) return false
        owner = value
        cursor = null
        pending = null
        return true
    }

    fun stage(peer: String, batch: ChatClearWire.Batch, cursor: Cursor) {
        require(owner.isNotBlank() && peer.isNotBlank() && pending == null)
        pending = Pending(peer, batch, cursor)
    }

    fun acknowledge(owner: String, peer: String, operation: UUID): Boolean {
        val item = pending ?: return false
        if (this.owner != owner || item.peer != peer || item.batch.operation != operation) return false
        cursor = item.cursor
        pending = null
        return true
    }

    fun restartCycle() {
        check(pending == null)
        cursor = null
    }
}
