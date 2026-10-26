package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лестница аварийного будильника (P1-6 аудита).
 *
 * Раньше сервис взводил точный будильник каждые 5 минут при КАЖДОМ старте —
 * 288 пробуждений в сутки в Doze. Теперь настойчивость включается только при
 * отказах старта, и важно, чтобы она росла, а не оставалась пятиминутной:
 * иначе телефон в чужом кармане будет будить себя вечно.
 */
class EmergencyKeepAliveLadderTest {

    private fun minutes(value: Long): Long = value / 60_000L

    @Test
    fun `retry ladder escalates and caps at an hour`() {
        val ladder = EmergencyKeepAlive.RETRY_LADDER_MS.map(::minutes)
        assertEquals(listOf(5L, 15L, 30L, 60L), ladder)
        assertEquals("последняя ступень — предел", 60L, ladder.last())
        assertEquals("ступени обязаны расти", ladder.sorted(), ladder)
    }

    @Test
    fun `safety net while the service is alive is an hour`() {
        assertEquals(60L, minutes(EmergencyKeepAlive.SAFETY_NET_MS))
        assertTrue(
            "страховка не должна будить чаще самого частого повтора",
            EmergencyKeepAlive.SAFETY_NET_MS >= EmergencyKeepAlive.RETRY_LADDER_MS.max(),
        )
    }

    @Test
    fun `ladder is indexed safely for any number of refusals`() {
        // onServiceRefused берёт ступень по числу отказов; после сотни отказов
        // и после первого он обязан остаться в границах массива.
        for (misses in 1..100) {
            val index = (misses - 1).coerceIn(0, EmergencyKeepAlive.RETRY_LADDER_MS.lastIndex)
            assertTrue(index in EmergencyKeepAlive.RETRY_LADDER_MS.indices)
        }
    }
}
