package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.schedule.DeferredInboxStore

/** Shared identity-scoped tombstone store for all direct-message ingestion paths. */
internal object ChatClearRegistry {
    @Volatile private var instance: ChatClearTombstones? = null

    fun store(context: Context): ChatClearTombstones = instance ?: synchronized(this) {
        instance ?: ChatClearTombstones(context.applicationContext).also { instance = it }
    }

    fun isCleared(context: Context, peer: String, messageId: String): Boolean = ChatClearLookup(
        owner = { DeferredInboxStore.owner(context) },
        // Always use the same synchronized SQLite store, including its first
        // creation. An absent-file shortcut could bypass identity validation
        // and race creation. This is still a preflight, not a network barrier.
        contains = { owner, currentPeer, id -> store(context).contains(owner, currentPeer, id) },
    ).isCleared(peer, messageId)
}
