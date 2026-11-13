package com.vladimir.messenger.worker

import com.google.common.util.concurrent.SettableFuture
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Guava futures and the production observer/gate; no Android WorkManager. */
class NotificationRecoveryOperationObserverTest {
    private class Fixture {
        val attempts = mutableListOf<UUID>()
        val futures = mutableListOf<SettableFuture<Unit>>()
        val failures = AtomicInteger()
        var completeBeforeListening = false
        val gate = NotificationRecoveryScheduleGate(
            enqueue = { id, failure ->
                attempts += id
                val future = SettableFuture.create<Unit>()
                futures += future
                if (completeBeforeListening) future.setException(IllegalStateException("synthetic"))
                NotificationRecoveryOperationObserver.watch(future, failure)
                true
            },
            recordFailure = { failures.incrementAndGet() },
        )
    }

    @Test fun failedFutureReleasesTicketForIndependentRetry() {
        val f = Fixture()
        assertTrue(f.gate.request())
        assertTrue(f.futures.single().setException(IllegalStateException("synthetic")))
        assertEquals(1, f.failures.get())
        assertTrue(f.gate.request())
        assertEquals(2, f.attempts.size)
        assertTrue(f.attempts[0] != f.attempts[1])
        assertTrue(f.futures[1].set(Unit))
        assertEquals(1, f.failures.get())
    }

    @Test fun cancelledFutureReleasesTicketWithoutClaimingAWorkerRan() {
        val f = Fixture()
        assertTrue(f.gate.request())
        assertTrue(f.futures.single().cancel(false))
        assertEquals(1, f.failures.get())
        assertTrue(f.gate.request())
        assertEquals(2, f.attempts.size)
    }

    @Test fun alreadyFailedFutureIsReportedBeforeRequestReturns() {
        val f = Fixture()
        f.completeBeforeListening = true
        assertFalse(f.gate.request())
        assertEquals(1, f.failures.get())
        f.completeBeforeListening = false
        assertTrue(f.gate.request())
        assertEquals(2, f.attempts.size)
    }

    @Test fun lateFailureCannotReleaseAStartedWorkerOrItsSuccessor() {
        val f = Fixture()
        assertTrue(f.gate.request())
        val first = f.attempts.single()
        f.gate.workerStarted(first)
        assertTrue(f.gate.request()) // mark the running worker dirty
        assertTrue(f.futures[0].setException(IllegalStateException("late")))
        assertEquals(0, f.failures.get())
        f.gate.workerFinished(first, retrying = false)
        assertEquals(2, f.attempts.size)
        f.gate.workerStopped(first) // stale completion cannot clear the successor
        assertTrue(f.gate.request())
        assertEquals(2, f.attempts.size)
        f.gate.workerStarted(f.attempts[1])
        f.gate.workerFinished(f.attempts[1], retrying = false)
        assertEquals(3, f.attempts.size) // dirty successor is independent too
    }
}
