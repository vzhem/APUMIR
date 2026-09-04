package com.vladimir.messenger.ui.components

import com.vladimir.messenger.data.reaction.ReactionPalette
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полёт реакции - чистая логика без Android, поэтому проверяется обычным
 * JVM-тестом: старт, границы координат, замена одного полёта другим и уборка.
 */
class ReactionFlightTest {

    @Test
    fun `launch stores a flight and clear removes it`() {
        ReactionFlight.launch("\uD83D\uDE80")
        val flight = ReactionFlight.current.value
        assertNotNull(flight)
        ReactionFlight.clear(flight!!.id)
        assertNull(ReactionFlight.current.value)
    }

    @Test
    fun `blank emoji never flies`() {
        ReactionFlight.current.value?.let { ReactionFlight.clear(it.id) }
        ReactionFlight.launch("   ")
        assertNull(ReactionFlight.current.value)
    }

    @Test
    fun `start point stays on screen`() {
        ReactionFlight.launch("\u2764\uFE0F", startXFraction = -5f, startYFraction = 12f)
        val flight = ReactionFlight.current.value!!
        assertTrue(flight.startXFraction in 0f..1f)
        assertTrue(flight.startYFraction in 0f..1f)
        ReactionFlight.clear(flight.id)
    }

    @Test
    fun `stale clear does not drop a newer flight`() {
        ReactionFlight.launch("\uD83D\uDD25")
        val first = ReactionFlight.current.value!!
        ReactionFlight.launch("\uD83C\uDF89")
        val second = ReactionFlight.current.value!!
        ReactionFlight.clear(first.id)
        assertNotNull(ReactionFlight.current.value)
        ReactionFlight.clear(second.id)
    }

    @Test
    fun `every palette emoji has a sane flight style`() {
        for (emoji in ReactionPalette.EMOJI) {
            val style = ReactionFlight.styleFor(emoji)
            assertTrue("подъём вне экрана: $emoji", style.riseFraction in 0.1f..1f)
            assertTrue("слишком долгий полёт: $emoji", style.durationMs in 400..2500)
            assertTrue("значок должен расти: $emoji", style.growTo >= 1f)
        }
    }
}
