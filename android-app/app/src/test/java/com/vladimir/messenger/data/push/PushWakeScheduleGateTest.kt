package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake durable inbox and enqueue backend; not a WorkManager or FCM test. */
class PushWakeScheduleGateTest {
    private class Backend {
        val failures = mutableListOf<() -> Unit>()
        var accepts = true
        fun enqueue(onFailure: () -> Unit): Boolean {
            failures.add(onFailure)
            return accepts
        }
    }

    @Test fun repeatedArrivalsCoalesceAndTheRunningWorkerSeesTheNewSignal() {
        val backend = Backend()
        val gate = PushWakeScheduleGate(backend::enqueue) { error("unexpected scheduler failure") }
        var pending = false
        repeat(1000) {
            gate.atomic { pending = true; assertTrue(gate.request()) }
        }
        assertEquals(1, backend.failures.size)
        val worker = gate.workerStarted()
        gate.atomic { pending = false } // the worker began draining the old signal
        gate.atomic { pending = true; assertTrue(gate.request()) }
        assertEquals(PushWakeScheduleGate.Finish.RETRY, gate.workerFinished(worker, false) {
            if (pending) PushWakeScheduleGate.Pending.PENDING else PushWakeScheduleGate.Pending.EMPTY
        })
        assertEquals(1, backend.failures.size)
    }

    @Test fun signalAfterFinalEmptyCheckRequestsASuccessorEvenBeforeWorkerReturns() {
        val backend = Backend()
        val gate = PushWakeScheduleGate(backend::enqueue) { error("unexpected scheduler failure") }
        var pending = false
        gate.atomic { pending = true; assertTrue(gate.request()) }
        val worker = gate.workerStarted()
        gate.atomic { pending = false }
        assertEquals(PushWakeScheduleGate.Finish.SUCCESS, gate.workerFinished(worker, false) {
            if (pending) PushWakeScheduleGate.Pending.PENDING else PushWakeScheduleGate.Pending.EMPTY
        })
        // The backend must enqueue a successor even if WorkManager still sees
        // the earlier worker RUNNING, and it must survive that parent's failure.
        gate.atomic { pending = true; assertTrue(gate.request()) }
        assertTrue(pending)
        assertEquals(2, backend.failures.size)
    }

    @Test fun arrivalRacingAnEmptyFinalCheckCannotBeLost() {
        val backend = Backend()
        val gate = PushWakeScheduleGate(backend::enqueue) { error("unexpected scheduler failure") }
        var pending = false
        gate.atomic { pending = true; assertTrue(gate.request()) }
        val worker = gate.workerStarted()
        gate.atomic { pending = false }
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        val threadError = AtomicReference<Throwable?>()
        val finish = Thread {
            try {
                check(gate.workerFinished(worker, false) {
                    checking.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    if (pending) PushWakeScheduleGate.Pending.PENDING else PushWakeScheduleGate.Pending.EMPTY
                } == PushWakeScheduleGate.Finish.SUCCESS)
            } catch (error: Throwable) { threadError.set(error) }
        }
        val arrival = Thread {
            try {
                gate.atomic { pending = true; check(gate.request()) }
                received.countDown()
            } catch (error: Throwable) { threadError.set(error) }
        }
        try {
            finish.start()
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            arrival.start()
            assertFalse(received.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(received.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            finish.join(5_000)
            arrival.join(5_000)
        }
        assertFalse(finish.isAlive)
        assertFalse(arrival.isAlive)
        assertNull(threadError.get())
        assertTrue(pending)
        assertEquals(2, backend.failures.size)
    }

    @Test fun synchronousAndAsynchronousScheduleFailuresAllowRedrive() {
        val backend = Backend()
        var errors = 0
        val gate = PushWakeScheduleGate(backend::enqueue) { errors++ }
        backend.accepts = false
        assertFalse(gate.atomic { gate.request() })
        assertEquals(1, errors)
        backend.accepts = true
        assertTrue(gate.atomic { gate.request() })
        assertEquals(2, backend.failures.size)
        backend.failures.last().invoke() // operation failed after accepting the request
        assertEquals(2, errors)
        assertTrue(gate.atomic { gate.request() })
        assertEquals(3, backend.failures.size)

        var immediateFailure = true
        val other = PushWakeScheduleGate({ failed -> if (immediateFailure) failed(); true }) { errors++ }
        assertFalse(other.atomic { other.request() })
        assertEquals(3, errors)
        immediateFailure = false
        assertTrue(other.atomic { other.request() })
    }

    @Test fun staleFailureOrOldStopCannotClearANewerWorkerOrSchedule() {
        val backend = Backend()
        var errors = 0
        val gate = PushWakeScheduleGate(backend::enqueue) { errors++ }
        assertTrue(gate.atomic { gate.request() })
        val worker = gate.workerStarted()
        backend.failures[0].invoke()
        assertTrue(gate.atomic { gate.request() })
        assertEquals(1, backend.failures.size)
        assertEquals(PushWakeScheduleGate.Finish.SUCCESS,
            gate.workerFinished(worker, false) { PushWakeScheduleGate.Pending.EMPTY })
        assertTrue(gate.atomic { gate.request() })
        assertEquals(2, backend.failures.size)
        gate.workerStopped(worker)
        backend.failures[0].invoke()
        assertTrue(gate.atomic { gate.request() })
        assertEquals(2, backend.failures.size)
        backend.failures[1].invoke()
        assertEquals(1, errors) // only the currently scheduled operation failed
        assertTrue(gate.atomic { gate.request() })
        assertEquals(3, backend.failures.size)
    }

    @Test fun failedOrUnavailableDrainNeverBecomesSuccess() {
        val backend = Backend()
        var errors = 0
        val gate = PushWakeScheduleGate(backend::enqueue) { errors++ }
        assertTrue(gate.atomic { gate.request() })
        val worker = gate.workerStarted()
        var checked = false
        assertEquals(PushWakeScheduleGate.Finish.RETRY, gate.workerFinished(worker, true) {
            checked = true
            PushWakeScheduleGate.Pending.EMPTY
        })
        assertFalse(checked)
        assertEquals(PushWakeScheduleGate.Finish.RETRY,
            gate.workerFinished(worker, false) { PushWakeScheduleGate.Pending.UNAVAILABLE })
        assertEquals(PushWakeScheduleGate.Finish.RETRY,
            gate.workerFinished(worker, false) { throw IllegalStateException("disk") })
        assertEquals(1, errors)
        gate.workerStopped(worker)
        assertTrue(gate.atomic { gate.request() })
        assertEquals(2, backend.failures.size)
    }

    @Test fun restartRequiresAnExplicitPersistentInboxRescan() {
        val firstBackend = Backend()
        val first = PushWakeScheduleGate(firstBackend::enqueue) { }
        var persisted = false
        first.atomic { persisted = true; assertTrue(first.request()) }
        // A new process has no in-memory scheduled token. Its startup/recovery
        // path must explicitly re-read durable state and request another job.
        val secondBackend = Backend()
        val restarted = PushWakeScheduleGate(secondBackend::enqueue) { }
        assertEquals(0, secondBackend.failures.size)
        restarted.atomic { if (persisted) assertTrue(restarted.request()) }
        assertEquals(1, secondBackend.failures.size)
    }

    @Test fun cancellationPropagatesAndDoesNotLeaveAFalseScheduledFlag() {
        var cancel = true
        val gate = PushWakeScheduleGate({
            if (cancel) throw CancellationException("cancel")
            true
        }) { error("cancellation must not be reported as a failure") }
        var propagated = false
        try { gate.atomic { gate.request() } } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
        cancel = false
        assertTrue(gate.atomic { gate.request() })
        val worker = gate.workerStarted()
        propagated = false
        try { gate.workerFinished(worker, false) { throw CancellationException("cancel") } }
        catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
        gate.workerStopped(worker)
    }
}
