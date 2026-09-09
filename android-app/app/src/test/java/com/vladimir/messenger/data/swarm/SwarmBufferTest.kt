package com.vladimir.messenger.data.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Буфер кусков без манифеста: предел записей, срок жизни, выборка по условию. */
class SwarmBufferTest {

    private var now = 1_000L
    private val buffer = SwarmBuffer<String>(capacity = 3, ttlMs = 60_000L, clock = { now })

    @Test
    fun takeReturnsMatchingInArrivalOrderAndRemovesThem() {
        buffer.put("a", "topic1:a")
        buffer.put("b", "topic2:b")
        buffer.put("c", "topic1:c")
        val taken = buffer.take { it.startsWith("topic1") }
        assertEquals(listOf("topic1:a", "topic1:c"), taken)
        assertEquals(1, buffer.size())
        assertTrue(buffer.contains("b"))
        assertFalse(buffer.contains("a"))
    }

    @Test
    fun capacityEvictsEldest() {
        buffer.put("a", "1")
        buffer.put("b", "2")
        buffer.put("c", "3")
        buffer.put("d", "4")
        assertEquals(3, buffer.size())
        assertFalse(buffer.contains("a"))
        assertTrue(buffer.contains("d"))
    }

    @Test
    fun ttlExpiresOldEntries() {
        buffer.put("a", "1")
        now += 30_000L
        buffer.put("b", "2")
        now += 30_001L
        // «a» старше минуты, «b» - ещё нет.
        assertFalse(buffer.contains("a"))
        assertTrue(buffer.contains("b"))
        assertEquals(listOf("2"), buffer.take { true })
    }

    @Test
    fun repeatedKeyReplacesValueAndRefreshesTime() {
        buffer.put("a", "old")
        now += 50_000L
        buffer.put("a", "new")
        now += 20_000L
        assertTrue(buffer.contains("a"))
        assertEquals(listOf("new"), buffer.take { true })
    }
}
