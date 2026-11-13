package com.vladimir.messenger.worker

import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRecoveryScheduleGateTest {
    private class Fixture {
        val ids = Collections.synchronizedList(mutableListOf<UUID>())
        val failures = ConcurrentHashMap<UUID, () -> Unit>()
        val diagnostics = AtomicInteger()
        val sequence = AtomicLong()
        var onEnqueue: (UUID, () -> Unit) -> Boolean = { id, failed -> ids += id; failures[id] = failed; true }
        var onDiagnostic: () -> Unit = {}
        val gate = NotificationRecoveryScheduleGate(
            enqueue = { id, failed -> onEnqueue(id, failed) },
            recordFailure = { diagnostics.incrementAndGet(); onDiagnostic() },
            newId = { UUID(0, sequence.incrementAndGet()) },
        )
    }

    @Test fun dirtyWorkerEnqueuesAnIndependentSuccessorBeforeItReturns() {
        val f = Fixture()
        assertTrue(f.gate.request())
        val first = f.ids.single()
        f.gate.workerStarted(first)
        repeat(40) { assertTrue(f.gate.request()) }
        assertEquals(1, f.ids.size)
        f.gate.workerFinished(first, retrying = false)
        assertEquals(2, f.ids.size)
        val successor = f.ids.last()
        assertTrue(first != successor)
        // A late callback, cancellation, or completion from the older worker
        // cannot cancel the successor or inherit its fate.
        f.failures.getValue(first).invoke()
        f.gate.workerStopped(first)
        f.gate.workerFinished(first, retrying = true)
        f.gate.workerStarted(successor)
        f.gate.workerFinished(successor, retrying = false)
        assertTrue(f.gate.request())
        assertEquals(3, f.ids.size)
        assertEquals(0, f.diagnostics.get())
    }

    @Test fun retryKeepsTicketButAConcurrentArrivalGetsAnIndependentSuccessor() {
        val f = Fixture()
        f.gate.request()
        val first = f.ids.single()
        f.gate.workerStarted(first)
        f.gate.workerFinished(first, retrying = true)
        assertTrue(f.gate.request())
        assertEquals(1, f.ids.size)
        f.gate.workerStarted(first) // WorkManager retries the same ID
        f.gate.workerFinished(first, retrying = true)
        assertEquals(2, f.ids.size)
        f.gate.workerStopped(first)
        f.gate.request()
        assertEquals(2, f.ids.size) // coalesced behind the successor
    }

    @Test fun cancellationWithDirtyWorkSchedulesSuccessorAndWithoutItReleasesTheTicket() {
        val f = Fixture()
        f.gate.request()
        val first = f.ids.single()
        f.gate.workerStarted(first)
        f.gate.request()
        f.gate.workerStopped(first)
        assertEquals(2, f.ids.size)
        val second = f.ids.last()
        f.gate.workerStarted(second)
        f.gate.workerStopped(second)
        f.gate.request()
        assertEquals(3, f.ids.size)
    }

    @Test fun lateFailureCannotClearAStartedOrReplacementTicket() {
        val f = Fixture()
        f.gate.request()
        val first = f.ids.single()
        f.gate.workerStarted(first)
        f.failures.getValue(first).invoke()
        f.gate.request()
        assertEquals(1, f.ids.size)
        f.gate.workerFinished(first, retrying = false)
        val second = f.ids.last()
        f.failures.getValue(first).invoke()
        f.gate.workerStarted(second)
        f.gate.workerFinished(second, retrying = false)
        assertTrue(f.gate.request())
        assertEquals(3, f.ids.size)
        assertEquals(0, f.diagnostics.get())
    }

    @Test fun asynchronousAndSynchronousEnqueueFailuresReleaseTheTicket() {
        val f = Fixture()
        f.gate.request()
        val first = f.ids.single()
        f.failures.getValue(first).invoke()
        assertEquals(1, f.diagnostics.get())
        assertTrue(f.gate.request())
        val second = f.ids.last()
        f.failures.getValue(first).invoke()
        f.gate.workerStarted(second)
        f.gate.workerFinished(second, retrying = false)
        f.onEnqueue = { _, failed -> failed(); true }
        assertFalse(f.gate.request())
        assertEquals(2, f.diagnostics.get())
        f.onEnqueue = { id, failed -> f.ids += id; f.failures[id] = failed; true }
        assertTrue(f.gate.request())
        assertEquals(3, f.ids.size)
    }

    @Test fun thrownFailureOrBrokenDiagnosticsCannotLeaveADeadTicket() {
        val f = Fixture()
        f.onEnqueue = { _, _ -> error("scheduler down") }
        f.onDiagnostic = { error("broken diagnostics") }
        assertFalse(f.gate.request())
        assertFalse(f.gate.request())
        assertEquals(2, f.diagnostics.get())
        f.onEnqueue = { id, failed -> f.ids += id; f.failures[id] = failed; true }
        assertTrue(f.gate.request())
        assertEquals(1, f.ids.size)
    }

    @Test fun cancellationPropagatesWithoutLeavingAReservedTicket() {
        val f = Fixture()
        f.onEnqueue = { _, _ -> throw CancellationException("stop") }
        try { f.gate.request(); error("must propagate cancellation") } catch (_: CancellationException) { }
        f.onEnqueue = { id, failed -> f.ids += id; f.failures[id] = failed; true }
        assertTrue(f.gate.request())
        assertEquals(0, f.diagnostics.get())
        assertEquals(1, f.ids.size)
    }

    @Test fun concurrentRequestsCoalesceAndStillCreateOneSuccessor() = runBlocking {
        val f = Fixture()
        (1..100).map { async(Dispatchers.Default) { assertTrue(f.gate.request()) } }.awaitAll()
        assertEquals(1, f.ids.size)
        val first = f.ids.single()
        f.gate.workerStarted(first)
        f.gate.workerFinished(first, retrying = false)
        assertEquals(2, f.ids.size)
        val second = f.ids.last()
        f.gate.workerStarted(second)
        f.gate.workerFinished(second, retrying = false)
        f.gate.request()
        assertEquals(3, f.ids.size)
    }

    @Test fun untrackedPeriodicAndRestoredWorkersCannotChangeTheCurrentTicket() {
        val f = Fixture()
        f.gate.request()
        val first = f.ids.single()
        val restored = UUID(1, 99)
        f.gate.workerStarted(restored)
        f.gate.workerFinished(restored, retrying = false)
        f.gate.workerStopped(restored)
        f.gate.request()
        assertEquals(1, f.ids.size)
        f.gate.workerStarted(first)
        f.gate.workerFinished(first, retrying = false)
        assertEquals(2, f.ids.size)
    }
}
