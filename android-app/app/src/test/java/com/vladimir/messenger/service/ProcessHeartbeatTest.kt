package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessHeartbeatTest {
    private val ready = DeliveryWakePolicy.Conditions(
        enabled = true,
        batteryUnrestricted = true,
        exactAlarmsAllowed = true,
        notificationsAllowed = true,
    )

    @Test
    fun `replacement or reboot starts without trusting the old persisted heartbeat`() {
        val writes = mutableListOf<Long>()
        val oldProcess = ProcessHeartbeat(300_000)
        oldProcess.started(1_000_000) { writes += it }
        assertEquals(listOf(1_000_000L), writes)
        val newProcess = ProcessHeartbeat(300_000)
        assertEquals(0L, newProcess.lastForWake())
        assertTrue(DeliveryWakePolicy.shouldWakeNow(ready, newProcess.lastForWake(), 1_001_000))
    }

    @Test
    fun `if application started a real service before package broadcast it stays live`() {
        val process = ProcessHeartbeat(300_000)
        process.started(1_000_000) {}
        assertFalse(DeliveryWakePolicy.shouldWakeNow(ready, process.lastForWake(), 1_001_000))
        // The protected broadcast checks liveness; it does not clear a new service.
        assertEquals(1_000_000L, process.lastForWake())
    }

    @Test
    fun `stop or refusal makes a fast wake possible and late pulses are ignored`() {
        val process = ProcessHeartbeat(300_000)
        process.started(1_000_000) {}
        process.stopped()
        process.pulse(1_001_000) { throw AssertionError("late pulse must not persist") }
        assertEquals(0L, process.lastForWake())
        assertTrue(DeliveryWakePolicy.shouldWakeNow(ready, process.lastForWake(), 1_002_000))
    }

    @Test
    fun `duplicate start and ordinary pulses do not spam persisted history`() {
        val writes = mutableListOf<Long>()
        val process = ProcessHeartbeat(300_000)
        process.started(1_000_000) { writes += it }
        process.started(1_010_000) { writes += it }
        process.pulse(1_020_000) { writes += it }
        assertEquals(1_020_000L, process.lastForWake())
        assertEquals(listOf(1_000_000L), writes)
        process.pulse(1_300_000) { writes += it }
        assertEquals(listOf(1_000_000L, 1_300_000L), writes)
        process.stopped()
        process.started(1_301_000) { writes += it }
        assertEquals(1_301_000L, writes.last())
    }
}
