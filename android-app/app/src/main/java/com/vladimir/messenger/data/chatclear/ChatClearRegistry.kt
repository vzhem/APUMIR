package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import java.io.File

/** Shared identity-scoped tombstone store for all direct-message ingestion paths. */
internal object ChatClearRegistry {
    @Volatile private var instance: ChatClearTombstones? = null

    fun store(context: Context): ChatClearTombstones = instance ?: synchronized(this) {
        instance ?: ChatClearTombstones(context.applicationContext).also { instance = it }
    }

    fun isCleared(context: Context, peer: String, messageId: String): Boolean {
        if (peer.isBlank() || messageId.isBlank()) return false
        // No old tombstones exist until the database is first created. Avoid opening
        // another database on every incoming message for users without this feature.
        if (instance == null && !File(context.noBackupFilesDir, "chat_clear.sqlite").exists()) return false
        val owner = DeferredInboxStore.owner(context)
        check(owner.isNotBlank()) { "Chat clear identity not ready" }
        return store(context).contains(owner, peer, messageId)
    }
}
