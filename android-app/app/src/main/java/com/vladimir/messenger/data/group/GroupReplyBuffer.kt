package com.vladimir.messenger.data.group

import com.vladimir.messenger.data.swarm.SwarmBuffer

/** Bounded early-reply buffer; the same message ID in different groups is distinct. */
internal class GroupReplyBuffer<T>(capacity: Int, ttlMs: Long, clock: () -> Long) {
    private data class Entry<T>(val groupId: String, val messageId: String, val value: T)
    private val entries = SwarmBuffer<Entry<T>>(capacity, ttlMs, clock)

    fun put(groupId: String, messageId: String, value: T) {
        // Length prefix makes the key unambiguous even when IDs contain delimiters.
        entries.put("${groupId.length}:$groupId$messageId", Entry(groupId, messageId, value))
    }

    fun take(groupId: String, messageId: String): List<T> =
        entries.take { it.groupId == groupId && it.messageId == messageId }.map { it.value }
}
