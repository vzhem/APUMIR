package com.vladimir.messenger.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило обложки: при каждом открытии приложения она есть, при повороте и
 * коротком возврате из фона - нет. Раньше тёплое ядро отменяло обложку
 * полностью, и её перестали видеть.
 */
class SplashGateTest {

    private val minute = 60_000L

    @Test
    fun `first screen of the process shows the cover even when core is warm`() {
        val gate = SplashGate()
        assertTrue(gate.onScreenCreated(0L))
    }

    @Test
    fun `recreating the screen for a rotate does not replay the cover`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        gate.onBackground(1_000L)
        assertFalse(gate.onScreenCreated(1_100L))
    }

    @Test
    fun `long absence brings the cover back`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        gate.onBackground(5_000L)
        assertTrue(gate.onForeground(5_000L + minute))
    }

    @Test
    fun `switching to another app briefly does not flash the cover`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        gate.onBackground(5_000L)
        assertFalse(gate.onForeground(5_000L + minute - 1))
    }

    @Test
    fun `the cover is shown once per absence`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        gate.onBackground(0L)
        assertTrue(gate.onForeground(2 * minute))
        assertFalse(gate.onForeground(3 * minute))
    }

    @Test
    fun `start of a lifecycle is not treated as a return from background`() {
        val gate = SplashGate()
        assertFalse(gate.onForeground(10L))
        gate.onScreenCreated(10L)
        assertTrue(gate.onScreenCreated(20_000L).not())
    }
}
