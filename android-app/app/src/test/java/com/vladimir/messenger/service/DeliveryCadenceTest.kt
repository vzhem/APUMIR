package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ритм доставки. Владелец 2026-10-29: смена аватарки видна сразу, а сообщения
 * идут с задержками. Причина — ожидание в опросе шины и в сливе очереди.
 */
class DeliveryCadenceTest {

    // ── опрос шины событий ────────────────────────────────────────────────

    @Test
    fun `busy poll never waits even when the screen is off`() {
        assertEquals(
            DeliveryCadence.BUSY_POLL_MS,
            DeliveryCadence.pollDelay(busy = true, onScreen = false, idleMs = 10 * 60_000L),
        )
    }

    @Test
    fun `while the app is on screen events are drained four times a second`() {
        assertEquals(
            DeliveryCadence.FOREGROUND_POLL_MS,
            DeliveryCadence.pollDelay(busy = false, onScreen = true, idleMs = 10 * 60_000L),
        )
        assertTrue(DeliveryCadence.FOREGROUND_POLL_MS < DeliveryCadence.IDLE_POLL_MS)
    }

    @Test
    fun `background stays hot for a while after the last packet`() {
        assertEquals(
            DeliveryCadence.BACKGROUND_ACTIVE_POLL_MS,
            DeliveryCadence.pollDelay(busy = false, onScreen = false, idleMs = 3_000L),
        )
    }

    @Test
    fun `silence keeps the old five second step so the battery is untouched`() {
        assertEquals(
            DeliveryCadence.IDLE_POLL_MS,
            DeliveryCadence.pollDelay(
                busy = false,
                onScreen = false,
                idleMs = DeliveryCadence.ACTIVITY_WINDOW_MS + 1,
            ),
        )
        assertEquals(5_000L, DeliveryCadence.IDLE_POLL_MS)
    }

    @Test
    fun `a tap on the screen shortens the step immediately`() {
        DeliveryCadence.setForeground(false)
        val before = DeliveryCadence.pollDelayMs(busy = false)
        DeliveryCadence.setForeground(true)
        try {
            val after = DeliveryCadence.pollDelayMs(busy = false)
            assertTrue("экран смотрят - шаг должен быть короче", after <= before)
            assertTrue(DeliveryCadence.isForeground())
        } finally {
            DeliveryCadence.setForeground(false)
        }
    }

    @Test
    fun `a packet touches the cadence so the next second is short`() {
        DeliveryCadence.setForeground(false)
        DeliveryCadence.touch(nowMs = DeliveryCadence.now())
        assertEquals(
            DeliveryCadence.BACKGROUND_ACTIVE_POLL_MS,
            DeliveryCadence.pollDelayMs(busy = false),
        )
    }

    // ── слив офлайн-очереди ───────────────────────────────────────────────

    @Test
    fun `empty queue is checked cheaply instead of spun`() {
        assertEquals(
            OutboxCadence.EMPTY_CHECK_MS,
            OutboxCadence.nextDelayMs(hasQueued = false, sent = 0, failures = 0, onScreen = true),
        )
    }

    @Test
    fun `a successful drain continues almost immediately`() {
        assertEquals(
            OutboxCadence.DRAIN_MS,
            OutboxCadence.nextDelayMs(hasQueued = true, sent = 3, failures = 4, onScreen = false),
        )
    }

    @Test
    fun `a failed attempt backs off but never beyond a minute on screen`() {
        val delays = (0..10).map { failures ->
            OutboxCadence.nextDelayMs(hasQueued = true, sent = 0, failures = failures, onScreen = true)
        }
        assertEquals(5_000L, delays.first())
        assertEquals(60_000L, delays.last())
        assertTrue("лестница не должна убывать", delays.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `in the background the ladder is slower and capped at two minutes`() {
        val delays = (0..10).map { failures ->
            OutboxCadence.nextDelayMs(hasQueued = true, sent = 0, failures = failures, onScreen = false)
        }
        assertTrue(delays.first() >= 15_000L)
        assertEquals(120_000L, delays.last())
    }

    @Test
    fun `the old one minute wait is gone for the first retry`() {
        val first = OutboxCadence.nextDelayMs(hasQueued = true, sent = 0, failures = 0, onScreen = true)
        assertTrue("первый повтор должен быть раньше минуты, а не через неё", first < 60_000L)
    }

    @Test
    fun `success resets the ladder and failure advances it`() {
        assertEquals(0, OutboxCadence.nextFailures(3, sent = 1))
        assertEquals(4, OutboxCadence.nextFailures(3, sent = 0))
        assertEquals(64, OutboxCadence.nextFailures(64, sent = 0))
    }

    @Test
    fun `foreground flag is restored after the test`() {
        DeliveryCadence.setForeground(false)
        assertFalse(DeliveryCadence.isForeground())
        DeliveryCadence.setForeground(true)
        assertTrue(DeliveryCadence.isForeground())
        DeliveryCadence.setForeground(false)
    }
}
