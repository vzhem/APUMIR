package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ритм доставки и простоя. События будят поток сразу (Condvar в Rust), а
 * пустые насосы не должны молотить процессор по таймеру.
 */
class DeliveryCadenceTest {

    @Test
    fun `event wait is a bounded sleep, not a 250ms poll`() {
        assertEquals(5_000L, DeliveryCadence.eventWaitMs())
        assertEquals(DeliveryCadence.EVENT_WAIT_MS, DeliveryCadence.eventWaitMs())
    }

    @Test
    fun `touch marks activity so pumps stay fast for a short window`() {
        DeliveryCadence.setForeground(false)
        DeliveryCadence.touch(nowMs = DeliveryCadence.now())
        assertTrue(DeliveryCadence.idleMs() < DeliveryCadence.ACTIVITY_WINDOW_MS)
    }

    @Test
    fun `foreground flag is restored after the test`() {
        DeliveryCadence.setForeground(false)
        assertFalse(DeliveryCadence.isForeground())
        DeliveryCadence.setForeground(true)
        assertTrue(DeliveryCadence.isForeground())
        DeliveryCadence.setForeground(false)
    }

    // ── простой файловых насосов ─────────────────────────────────────────

    @Test
    fun `active work keeps the short file pump step`() {
        assertEquals(
            ServiceIdleCadence.FILE_PUMP_ACTIVE_MS,
            ServiceIdleCadence.filePumpDelayMs(hadWork = true, recentlyActive = false, onScreen = false),
        )
        assertEquals(
            ServiceIdleCadence.FILE_PUMP_ACTIVE_MS,
            ServiceIdleCadence.filePumpDelayMs(hadWork = false, recentlyActive = true, onScreen = true),
        )
    }

    @Test
    fun `idle foreground checks are rarer than active work`() {
        val idle = ServiceIdleCadence.filePumpDelayMs(hadWork = false, recentlyActive = false, onScreen = true)
        assertEquals(ServiceIdleCadence.FILE_PUMP_IDLE_FOREGROUND_MS, idle)
        assertTrue(idle > ServiceIdleCadence.FILE_PUMP_ACTIVE_MS)
    }

    @Test
    fun `idle background is the rarest safety net`() {
        val idle = ServiceIdleCadence.filePumpDelayMs(hadWork = false, recentlyActive = false, onScreen = false)
        assertEquals(ServiceIdleCadence.FILE_PUMP_IDLE_BACKGROUND_MS, idle)
        assertTrue(idle >= ServiceIdleCadence.FILE_PUMP_IDLE_FOREGROUND_MS)
    }

    @Test
    fun `update barrier is not a 2_5 second spin`() {
        assertTrue(ServiceIdleCadence.UPDATE_BARRIER_MS >= 30_000L)
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
        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a })
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
    fun `unfinished scan continues promptly without escalating the failure ladder`() {
        assertEquals(2_000L, OutboxCadence.nextDelayMs(true, 0, 20, onScreen = true, moreToScan = true))
        assertEquals(5_000L, OutboxCadence.nextDelayMs(true, 0, 20, onScreen = false, moreToScan = true))
        assertEquals(3, OutboxCadence.nextFailures(3, sent = 0, moreToScan = true))
        assertEquals(4, OutboxCadence.nextFailures(3, sent = 0, moreToScan = false))
        assertEquals(0, OutboxCadence.nextFailures(3, sent = 1, moreToScan = true))
    }

    @Test
    fun `success resets the ladder and failure advances it`() {
        assertEquals(0, OutboxCadence.nextFailures(3, sent = 1))
        assertEquals(4, OutboxCadence.nextFailures(3, sent = 0))
        assertEquals(64, OutboxCadence.nextFailures(64, sent = 0))
    }
}
