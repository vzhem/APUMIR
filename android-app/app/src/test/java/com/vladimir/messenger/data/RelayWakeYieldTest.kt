package com.vladimir.messenger.data

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWakeYieldTest {
    @Test fun normalWindowExpiresAndNewGenerationDoesNotInheritAnOldRequest() {
        val gate = RelayWakeYield()
        val first = gate.snapshot()
        assertFalse(gate.await(first, 1))
        gate.request()
        assertTrue(gate.requestedSince(first))
        val next = gate.snapshot()
        assertFalse(gate.requestedSince(next))
        assertFalse(gate.await(next, 0))
    }

    @Test fun serviceRequestBeforeTheWaitIsNotLost() {
        val gate = RelayWakeYield()
        val token = gate.snapshot()
        gate.request()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(pool.submit<Boolean> { gate.await(token, 25_000) }.get(2, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun serviceRequestReleasesTheWorkerAndKeepsLifecycleOrder() {
        val gate = RelayWakeYield()
        val token = gate.snapshot()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val enteringWindow = CountDownLatch(1)
        val lifecycle = Executors.newSingleThreadExecutor()
        try {
            val worker = lifecycle.submit {
                events.add("worker_start")
                try {
                    enteringWindow.countDown()
                    assertTrue(gate.await(token, 25_000))
                } finally { events.add("worker_stop") }
            }
            assertTrue(enteringWindow.await(2, TimeUnit.SECONDS))
            gate.request()
            val service = lifecycle.submit { events.add("service_start") }
            worker.get(2, TimeUnit.SECONDS)
            service.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("worker_start", "worker_stop", "service_start"), events.toList())
        } finally { lifecycle.shutdownNow() }
    }

    @Test fun requestDuringNativeStartupYieldsAfterStartNotBeforeStop() {
        val gate = RelayWakeYield()
        val token = gate.snapshot()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startupEntered = CountDownLatch(1)
        val finishStartup = CountDownLatch(1)
        val lifecycle = Executors.newSingleThreadExecutor()
        try {
            val worker = lifecycle.submit {
                events.add("worker_start")
                startupEntered.countDown()
                assertTrue(finishStartup.await(2, TimeUnit.SECONDS))
                try { assertTrue(gate.await(token, 25_000)) }
                finally { events.add("worker_stop") }
            }
            assertTrue(startupEntered.await(2, TimeUnit.SECONDS))
            gate.request()
            val service = lifecycle.submit { events.add("service_start") }
            // A signal does not pretend that an in-progress native start has ended.
            assertEquals(listOf("worker_start"), events.toList())
            finishStartup.countDown()
            worker.get(2, TimeUnit.SECONDS)
            service.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("worker_start", "worker_stop", "service_start"), events.toList())
        } finally {
            finishStartup.countDown()
            lifecycle.shutdownNow()
        }
    }

    @Test fun interruptionEscapesTheWaitAndStillRunsCleanup() {
        val gate = RelayWakeYield()
        val token = gate.snapshot()
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val thread = Thread {
            try {
                entered.countDown()
                gate.await(token, 25_000)
            } catch (_: InterruptedException) {
                interrupted.countDown()
            } finally { cleaned.countDown() }
        }
        thread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        thread.join(2_000)
        assertFalse(thread.isAlive)
    }
}
