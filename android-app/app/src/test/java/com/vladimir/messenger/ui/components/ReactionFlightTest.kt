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
        ReactionFlight.launch("\uD83D\uDE80", 200f, 900f)
        val flight = ReactionFlight.current.value
        assertNotNull(flight)
        ReactionFlight.clear(flight!!.id)
        assertNull(ReactionFlight.current.value)
    }

    @Test
    fun `blank emoji never flies`() {
        ReactionFlight.current.value?.let { ReactionFlight.clear(it.id) }
        ReactionFlight.launch("   ", 200f, 900f)
        assertNull(ReactionFlight.current.value)
    }

    @Test
    fun `flight without a measured position is ignored`() {
        ReactionFlight.current.value?.let { ReactionFlight.clear(it.id) }
        ReactionFlight.launch("\u2764\uFE0F", startXPx = 100f, startYPx = 0f)
        assertNull(ReactionFlight.current.value)
    }

    @Test
    fun `start point is kept as given in pixels`() {
        ReactionFlight.launch("\u2764\uFE0F", startXPx = 320f, startYPx = 1180f)
        val flight = ReactionFlight.current.value!!
        assertTrue(flight.startXPx == 320f)
        assertTrue(flight.startYPx == 1180f)
        ReactionFlight.clear(flight.id)
    }

    @Test
    fun `stale clear does not drop a newer flight`() {
        ReactionFlight.launch("\uD83D\uDD25", 200f, 900f)
        val first = ReactionFlight.current.value!!
        ReactionFlight.launch("\uD83C\uDF89", 200f, 900f)
        val second = ReactionFlight.current.value!!
        ReactionFlight.clear(first.id)
        assertNotNull(ReactionFlight.current.value)
        ReactionFlight.clear(second.id)
    }

    @Test
    fun `every palette emoji has a sane flight style`() {
        for (emoji in ReactionPalette.EMOJI) {
            val style = ReactionFlight.styleFor(emoji)
            assertTrue("подъём вне экрана: $emoji", style.riseFraction in 0.1f..0.95f)
            assertTrue("слишком быстрый полёт: $emoji", style.durationMs in 1500..3000)
            assertTrue("значок должен расти: $emoji", style.growTo >= 1f)
        }
    }
}
