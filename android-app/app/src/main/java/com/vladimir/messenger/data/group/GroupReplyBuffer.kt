package com.vladimir.messenger.data.group

import com.vladimir.messenger.data.swarm.SwarmBuffer

/** Bounded early-reply buffer, separated by local identity, group, message and claimed sender. */
internal class GroupReplyBuffer<T>(capacity: Int, ttlMs: Long, clock: () -> Long) {
    private data class Entry<T>(val owner: String, val groupId: String, val messageId: String, val value: T)
    private val entries = SwarmBuffer<Entry<T>>(capacity, ttlMs, clock)

    fun put(owner: String, groupId: String, messageId: String, sender: String, value: T) {
        // Length prefixes make the key unambiguous even when IDs contain delimiters.
        val key = "${owner.length}:$owner${groupId.length}:$groupId${messageId.length}:$messageId$sender"
        entries.put(key, Entry(owner, groupId, messageId, value))
    }

    fun take(owner: String, groupId: String, messageId: String): List<T> =
        entries.take { it.owner == owner && it.groupId == groupId && it.messageId == messageId }.map { it.value }
}
