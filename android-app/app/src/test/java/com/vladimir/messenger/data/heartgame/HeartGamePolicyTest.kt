package com.vladimir.messenger.data.heartgame

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class HeartGamePolicyTest {
    @Test fun intervalIsAlwaysBetween45And55Seconds() {
        val random = Random(2026)
        val values = (1..10_000).map { HeartGamePolicy.nextInterval(random) }
        assertTrue(values.all { it in 45_000L..55_000L })
        assertTrue(values.distinct().size > 100)
    }
    @Test fun aDropCanOnlyBeClaimedOnce() {
        val policy = HeartGamePolicy()
        val ticket = policy.start(50_000)!!
        assertFalse(policy.claim(ticket + 1, 50_001))
        assertTrue(policy.claim(ticket, 50_001))
        assertFalse(policy.claim(ticket, 50_002))
    }
    @Test fun expiryDisableAndBackgroundInvalidateTickets() {
        val policy = HeartGamePolicy()
        val ticket = policy.start(0)!!
        assertFalse(policy.claim(ticket, HeartGamePolicy.FALL_MS))
        val next = policy.start(50_000)!!
        policy.cancel()
        assertFalse(policy.claim(next, 50_001))
        assertNull(policy.start(50_002))
        val third = policy.start(100_000)!!
        assertFalse(policy.claim(next, 100_001))
        assertTrue(policy.claim(third, 100_001))
    }
    @Test fun rapidRestartsDoNotBypassMinimumInterval() {
        val policy = HeartGamePolicy()
        assertNotNull(policy.start(0))
        policy.cancel()
        assertNull(policy.start(44_999))
        assertNotNull(policy.start(45_000))
    }
}
