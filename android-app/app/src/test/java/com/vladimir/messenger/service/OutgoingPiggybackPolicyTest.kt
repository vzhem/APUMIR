package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingPiggybackPolicyTest {

    @Test
    fun `idle by default and opens 15 second window on user outgoing send`() {
        val policy = OutgoingPiggybackPolicy(windowMs = 15_000L, minFlushSpacingMs = 10_000L)
        assertFalse(policy.isActive(1_000L))
        assertEquals(0L, policy.remainingMs(1_000L))

        assertTrue(policy.noteUserOutgoing(1_000L))
        assertTrue(policy.isActive(1_000L))
        assertTrue(policy.isActive(15_999L))
        assertFalse(policy.isActive(16_000L))
        assertEquals(10_000L, policy.remainingMs(6_000L))
    }

    @Test
    fun `rapid user sends extend window without redundant back-to-back flushes unless dirty`() {
        val policy = OutgoingPiggybackPolicy(windowMs = 15_000L, minFlushSpacingMs = 10_000L)
        assertTrue(policy.noteUserOutgoing(10_000L))
        // Immediate second send extends window to 27_000L, but skips redundant flush.
        assertFalse(policy.noteUserOutgoing(12_000L))
        assertTrue(policy.isActive(26_999L))
        assertFalse(policy.isActive(27_000L))

        // Marking auxiliary state dirty forces the next user send to flush immediately.
        policy.markDirty()
        assertTrue(policy.noteUserOutgoing(14_000L))

        // Spacing elapsed also allows another flush pass.
        assertTrue(policy.noteUserOutgoing(25_000L))
    }

    @Test
    fun `out-of-order earlier timestamp never shrinks an already open window`() {
        val policy = OutgoingPiggybackPolicy(windowMs = 15_000L, minFlushSpacingMs = 10_000L)
        policy.noteUserOutgoing(20_000L)
        policy.noteUserOutgoing(5_000L)
        assertTrue(policy.isActive(34_999L))
        assertFalse(policy.isActive(35_000L))
    }
}
