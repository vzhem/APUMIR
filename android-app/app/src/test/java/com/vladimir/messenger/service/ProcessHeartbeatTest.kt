package com.vladimir.messenger.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val owner = Any()
        val writes = mutableListOf<Long>()
        val oldProcess = ProcessHeartbeat(300_000)
        oldProcess.created(owner)
        assertTrue(oldProcess.started(owner, 1_000_000) { writes += it })
        assertEquals(listOf(1_000_000L), writes)
        val newProcess = ProcessHeartbeat(300_000)
        assertEquals(0L, newProcess.lastForWake())
        assertTrue(DeliveryWakePolicy.shouldWakeNow(ready, newProcess.lastForWake(), 1_001_000))
    }

    @Test
    fun `service registered before a broadcast remains live`() {
        val owner = Any()
        val process = ProcessHeartbeat(300_000)
        process.created(owner)
        process.started(owner, 1_000_000) {}
        assertFalse(DeliveryWakePolicy.shouldWakeNow(ready, process.lastForWake(), 1_001_000))
        assertEquals(1_000_000L, process.lastForWake())
    }

    @Test
    fun `destroyed service cannot be revived by a late start or pulse`() {
        val owner = Any()
        val process = ProcessHeartbeat(300_000)
        process.created(owner)
        process.started(owner, 1_000_000) {}
        assertTrue(process.stopped(owner))
        assertFalse(process.stopped(owner))
        assertFalse(process.refused(owner))
        assertFalse(process.started(owner, 1_001_000) { throw AssertionError("late start must not persist") })
        process.pulse(owner, 1_001_000) { throw AssertionError("late pulse must not persist") }
        assertEquals(0L, process.lastForWake())
        assertTrue(DeliveryWakePolicy.shouldWakeNow(ready, process.lastForWake(), 1_002_000))
    }

    @Test
    fun `duplicate starts throttle history but a new service forces a write`() {
        val owner = Any()
        val next = Any()
        val writes = mutableListOf<Long>()
        val process = ProcessHeartbeat(300_000)
        process.created(owner)
        process.started(owner, 1_000_000) { writes += it }
        process.started(owner, 1_010_000) { writes += it }
        process.pulse(owner, 1_020_000) { writes += it }
        assertEquals(1_020_000L, process.lastForWake())
        assertEquals(listOf(1_000_000L), writes)
        process.pulse(owner, 1_300_000) { writes += it }
        assertEquals(listOf(1_000_000L, 1_300_000L), writes)
        assertTrue(process.stopped(owner))
        process.created(next)
        process.started(next, 1_301_000) { writes += it }
        assertEquals(1_301_000L, writes.last())
    }

    @Test
    fun `replacement rejects stale operations while pending active refused and destroyed`() {
        val old = Any()
        val replacement = Any()
        val writes = mutableListOf<Long>()
        val process = ProcessHeartbeat(300_000)
        process.created(old)
        process.started(old, 1_000_000) { writes += it }
        process.created(replacement)
        assertEquals(0L, process.lastForWake())
        assertFalse(process.started(old, 1_050_000) { throw AssertionError("stale start") })
        assertFalse(process.refused(old))
        assertFalse(process.stopped(old))
        assertTrue(process.refused(replacement)) // fails before foreground succeeds
        assertFalse(process.stopped(old))
        assertTrue(process.started(replacement, 1_100_000) { writes += it })
        process.pulse(old, 2_000_000) { throw AssertionError("stale pulse") }
        assertFalse(process.refused(old))
        assertFalse(process.stopped(old))
        assertEquals(1_100_000L, process.lastForWake())
        assertEquals(listOf(1_000_000L, 1_100_000L), writes)
        assertFalse(DeliveryWakePolicy.shouldWakeNow(ready, process.lastForWake(), 1_101_000))
        assertTrue(process.refused(replacement))
        assertFalse(process.stopped(old))
        assertTrue(process.stopped(replacement)) // onDestroy after foreground refusal
        assertFalse(process.stopped(old))
        assertFalse(process.started(replacement, 1_200_000) { throw AssertionError("destroyed start") })
        assertEquals(0L, process.lastForWake())
    }

    @Test
    fun `failed initial foreground has an owner and can stop without ever being alive`() {
        val owner = Any()
        val process = ProcessHeartbeat(300_000)
        assertFalse(process.refused(owner))
        process.created(owner)
        assertTrue(process.refused(owner))
        assertEquals(0L, process.lastForWake())
        assertTrue(process.stopped(owner))
        assertFalse(process.refused(owner))
    }

    @Test
    fun `same owner can recover after refusal before destruction`() {
        val owner = Any()
        val writes = mutableListOf<Long>()
        val process = ProcessHeartbeat(300_000)
        process.created(owner)
        process.started(owner, 1_000_000) { writes += it }
        assertTrue(process.refused(owner))
        process.pulse(owner, 1_001_000) { throw AssertionError("refused owner is not active") }
        assertTrue(process.started(owner, 1_002_000) { writes += it })
        assertEquals(listOf(1_000_000L, 1_002_000L), writes)
        assertEquals(1_002_000L, process.lastForWake())
        assertTrue(process.stopped(owner))
    }

    @Test
    fun `owners are compared by identity rather than equals`() {
        class Equivalent {
            override fun equals(other: Any?): Boolean = other is Equivalent
            override fun hashCode(): Int = 0
        }
        val owner = Equivalent()
        val impersonator = Equivalent()
        val process = ProcessHeartbeat(300_000)
        process.created(owner)
        assertTrue(process.started(owner, 1_000_000) {})
        assertFalse(process.refused(impersonator))
        assertFalse(process.stopped(impersonator))
        process.pulse(impersonator, 1_010_000) { throw AssertionError("equal is not identical") }
        assertEquals(1_000_000L, process.lastForWake())
    }

    @Test
    fun `old callback released after replacement cannot change the new heartbeat`() {
        val old = Any()
        val replacement = Any()
        val process = ProcessHeartbeat(300_000)
        process.created(old)
        process.started(old, 1_000_000) {}
        val queued = CountDownLatch(1)
        val release = CountDownLatch(1)
        val staleStopped = AtomicBoolean(true)
        val staleRefused = AtomicBoolean(true)
        val staleStarted = AtomicBoolean(true)
        val stalePersisted = AtomicBoolean(false)
        val thread = Thread {
            queued.countDown()
            if (release.await(5, TimeUnit.SECONDS)) {
                process.pulse(old, 2_000_000) { stalePersisted.set(true) }
                staleStopped.set(process.stopped(old))
                staleRefused.set(process.refused(old))
                staleStarted.set(process.started(old, 2_000_000) { stalePersisted.set(true) })
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            process.created(replacement)
            process.started(replacement, 1_100_000) {}
        } finally {
            release.countDown()
        }
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertFalse(stalePersisted.get())
        assertFalse(staleStopped.get())
        assertFalse(staleRefused.get())
        assertFalse(staleStarted.get())
        assertEquals(1_100_000L, process.lastForWake())
        process.pulse(replacement, 1_100_001) {} // keep the weak owner reachable through the assertions
    }
}
