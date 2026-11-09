package com.vladimir.messenger.data.chatclear

import java.util.UUID

/** Suppress a late paired-device file announcement by either side's file message ID. */
internal class ChatClearFileMetaGuard(
    private val isCleared: (peer: String, id: String) -> Boolean,
    private val apply: suspend (peer: String, batch: ChatClearWire.Batch) -> Boolean,
) {
    suspend fun suppress(peer: String, groupId: String, name: String, messageId: String, transferId: String): Boolean {
        if (groupId.isNotBlank() || peer.isBlank() || transferId.isBlank() ||
            name.startsWith("apu-story-video-", ignoreCase = true) ||
            name.startsWith("apu-avatar-video-", ignoreCase = true)) return false
        val ids = listOf(messageId, "file-$transferId").filter { it.isNotBlank() }.distinct()
        if (ids.none { isCleared(peer, it) }) return false
        check(apply(peer, ChatClearWire.Batch(UUID.randomUUID(), ids))) { "Cannot finish cleared mirror file cleanup" }
        return true
    }
}
