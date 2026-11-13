package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupReplyBufferTest {
    @Test fun equalMessageIdsInDifferentGroupsAndIdentitiesAreIndependent() {
        val buffer = GroupReplyBuffer<String>(10, 1000L) { 0L }
        buffer.put("owner-a", "group-a", "same-id", "author", "quote-a")
        buffer.put("owner-a", "group-b", "same-id", "author", "quote-b")
        buffer.put("owner-b", "group-a", "same-id", "author", "quote-c")
        assertEquals(listOf("quote-b"), buffer.take("owner-a", "group-b", "same-id"))
        assertEquals(listOf("quote-a"), buffer.take("owner-a", "group-a", "same-id"))
        assertEquals(listOf("quote-c"), buffer.take("owner-b", "group-a", "same-id"))
        assertTrue(buffer.take("owner-a", "group-a", "same-id").isEmpty())
    }

    @Test fun scopedKeysCannotCollideWhenIdsContainDelimiters() {
        val buffer = GroupReplyBuffer<String>(10, 1000L) { 0L }
        buffer.put("owner", "a|b", "c", "sender", "first")
        buffer.put("owner", "a", "b|c", "sender", "second")
        buffer.put("owner", "a", "b|c", "sender-2", "third")
        assertEquals(listOf("first"), buffer.take("owner", "a|b", "c"))
        assertEquals(listOf("second", "third"), buffer.take("owner", "a", "b|c"))
    }

    @Test fun sameScopeReplacesButDifferentSendersDoNotAndCapacityAndTtlStillApply() {
        var now = 0L
        val buffer = GroupReplyBuffer<String>(2, 100L) { now }
        buffer.put("owner", "a", "id", "alice", "old")
        buffer.put("owner", "a", "id", "alice", "new")
        buffer.put("owner", "a", "id", "mallory", "other")
        assertEquals(listOf("new", "other"), buffer.take("owner", "a", "id"))
        buffer.put("owner", "a", "id", "alice", "a")
        buffer.put("owner", "b", "id", "alice", "b")
        buffer.put("owner", "c", "id", "alice", "c")
        assertTrue(buffer.take("owner", "a", "id").isEmpty())
        now = 101L
        assertTrue(buffer.take("owner", "b", "id").isEmpty())
        assertTrue(buffer.take("owner", "c", "id").isEmpty())
    }
}
