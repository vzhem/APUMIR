package com.vladimir.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRecoveryGateTest {
    @Test fun `cold start and short background do not request discovery`() {
        val gate = ForegroundRecoveryGate()
        assertFalse(gate.foreground(1_000L))
        gate.background(2_000L)
        assertFalse(gate.foreground(61_999L))
        assertFalse(gate.foreground(100_000L))
    }

    @Test fun `long background requests exactly one recovery per return`() {
        val gate = ForegroundRecoveryGate()
        gate.background(1_000L)
        gate.background(30_000L) // duplicate stop cannot hide the long gap
        assertTrue(gate.foreground(61_000L))
        assertFalse(gate.foreground(61_001L))
        gate.background(70_000L)
        assertTrue(gate.foreground(7_270_000L))
    }

    @Test fun `backwards elapsed time cannot cause an unnecessary kick`() {
        val gate = ForegroundRecoveryGate()
        gate.background(100_000L)
        assertFalse(gate.foreground(1L))
        assertFalse(gate.foreground(200_000L))
    }
}
