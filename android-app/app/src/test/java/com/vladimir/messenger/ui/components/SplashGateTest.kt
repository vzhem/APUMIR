package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило обложки: при запуске приложения она есть даже при тёплом ядре, при
 * повороте и возврате из фона - нет (как в Telegram).
 */
class SplashGateTest {

    @Test
    fun `launch shows the cover even when the core is already warm`() {
        val gate = SplashGate()
        assertTrue(gate.onScreenCreated(0L))
    }

    @Test
    fun `rotating the screen does not replay the cover`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        assertFalse(gate.onScreenCreated(1_100L))
    }

    @Test
    fun `returning from the background goes straight to the chats`() {
        val gate = SplashGate()
        gate.onScreenCreated(0L)
        assertFalse(gate.onScreenCreated(30 * 60_000L))
    }

    @Test
    fun `the cover is shown exactly once per process`() {
        val gate = SplashGate()
        var shown = 0
        repeat(5) { attempt ->
            if (gate.onScreenCreated(attempt * 1_000L)) shown++
        }
        assertEquals(1, shown)
    }

    @Test
    fun `the first screen time is recorded for diagnostics`() {
        val gate = SplashGate()
        assertNull(gate.lastScreenCreatedAtMillis)
        gate.onScreenCreated(4_200L)
        assertEquals(4_200L, gate.lastScreenCreatedAtMillis)
    }
}
