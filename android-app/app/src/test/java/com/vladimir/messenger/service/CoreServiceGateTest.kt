package com.vladimir.messenger.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreServiceGateTest {
    @Test fun staleReportsAndStopsCannotChangeTheReplacementService() {
        val gate = CoreServiceGate()
        val old = Any()
        val current = Any()
        val events = mutableListOf<String>()
        gate.activate(old) { events.add("old-start") }
        gate.activate(current) { events.add("new-start") }
        assertNull(gate.ifCurrent(old) { events.add("old-ready") })
        assertFalse(gate.retire(old) { events.add("old-stop") })
        assertTrue(gate.isCurrent(current))
        gate.ifCurrent(current) { events.add("new-ready") }
        assertTrue(gate.retire(current) { events.add("new-stop") })
        assertFalse(gate.retire(current) { events.add("double-stop") })
        assertNull(gate.ifCurrent(current) { events.add("late-ready") })
        assertEquals(listOf("old-start", "new-start", "new-ready", "new-stop"), events)
    }

    @Test fun staleQueuedStartIsRefusedEvenIfItRunsAfterShutdownWasQueued() {
        val gate = CoreServiceGate()
        val old = Any()
        val current = Any()
        val lifecycle = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            gate.activate(old) { }
            lifecycle.submit { entered.countDown(); assertTrue(unblock.await(2, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The old coroutine queues a native start while its executor is busy.
            val stale = lifecycle.submit { events.add(if (gate.isCurrent(old)) "old-started" else "old-refused") }
            assertTrue(gate.retire(old) { events.add("retired") })
            val stop = lifecycle.submit { events.add("shutdown") }
            gate.activate(current) { events.add("new-owner") }
            val next = lifecycle.submit { if (gate.isCurrent(current)) events.add("new-started") }
            unblock.countDown()
            stale.get(2, TimeUnit.SECONDS); stop.get(2, TimeUnit.SECONDS); next.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("retired", "new-owner", "old-refused", "shutdown", "new-started"), events.toList())
        } finally {
            unblock.countDown()
            lifecycle.shutdownNow()
        }
    }
}
