package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupReplyBufferTest {
    @Test fun equalMessageIdsInDifferentGroupsAreIndependent() {
        val buffer = GroupReplyBuffer<String>(10, 1000L) { 0L }
        buffer.put("group-a", "same-id", "quote-a")
        buffer.put("group-b", "same-id", "quote-b")
        assertEquals(listOf("quote-b"), buffer.take("group-b", "same-id"))
        assertEquals(listOf("quote-a"), buffer.take("group-a", "same-id"))
        assertTrue(buffer.take("group-a", "same-id").isEmpty())
    }

    @Test fun scopedKeysCannotCollideWhenIdsContainDelimiters() {
        val buffer = GroupReplyBuffer<String>(10, 1000L) { 0L }
        buffer.put("a|b", "c", "first")
        buffer.put("a", "b|c", "second")
        assertEquals(listOf("first"), buffer.take("a|b", "c"))
        assertEquals(listOf("second"), buffer.take("a", "b|c"))
    }

    @Test fun sameScopeReplacesAndCapacityAndTtlStillApply() {
        var now = 0L
        val buffer = GroupReplyBuffer<String>(2, 100L) { now }
        buffer.put("a", "id", "old")
        buffer.put("a", "id", "new")
        buffer.put("b", "id", "b")
        assertEquals(listOf("new"), buffer.take("a", "id"))
        buffer.put("a", "id", "a")
        buffer.put("c", "id", "c")
        assertTrue(buffer.take("b", "id").isEmpty())
        now = 101L
        assertTrue(buffer.take("a", "id").isEmpty())
        assertTrue(buffer.take("c", "id").isEmpty())
    }
}
