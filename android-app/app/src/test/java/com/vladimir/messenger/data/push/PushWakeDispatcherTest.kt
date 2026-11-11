package com.vladimir.messenger.data.push

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake inbox and enqueue backend; this does not exercise Android, FCM or WorkManager. */
class PushWakeDispatcherTest {
    private class Fake {
        val failures = mutableListOf<() -> Unit>()
        var schedulerAvailable = true
        var hint = PushWakeIntake.Hint.FOUND
        var persisted = false
        var readsUnavailable = false
        var routeWrites = 0
        var recoveryWrites = 0
        var errors = 0
        val gate = PushWakeScheduleGate({ failed -> failures += failed; schedulerAvailable }, { errors++ })
        val dispatcher = PushWakeDispatcher(
            gate,
            { hint },
            { _, _ -> routeWrites++; persisted = true; PushWakeInbox.EnqueueResult.QUEUED },
            { recoveryWrites++; persisted = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
            { now ->
                if (readsUnavailable) PushWakeInbox.PendingResult.Unavailable
                else if (persisted) PushWakeInbox.PendingResult.Pending(now)
                else PushWakeInbox.PendingResult.Empty
            },
            { errors++ },
        )
    }

    private val data = mapOf("v" to "1", "route" to "ab".repeat(32))

    @Test fun validRouteIsPersistedAndCoalescedDuringWorker() {
        val fake = Fake()
        assertEquals(PushWakeIntake.Result.QUEUED, fake.dispatcher.onData(data, 100))
        assertEquals(1, fake.routeWrites)
        assertEquals(1, fake.failures.size)
        val ticket = fake.dispatcher.workerStarted()
        fake.persisted = false // drain consumed the old item
        assertEquals(PushWakeIntake.Result.QUEUED, fake.dispatcher.onData(data, 101))
        assertEquals(1, fake.failures.size)
        assertEquals(PushWakeScheduleGate.Finish.RETRY, fake.dispatcher.workerFinished(ticket, false, 102))
        fake.persisted = false
        assertEquals(PushWakeScheduleGate.Finish.SUCCESS, fake.dispatcher.workerFinished(ticket, false, 103))
    }

    @Test fun deletedMessagesSchedulesGenericRecoveryAndUnavailableLookupNeverWrites() {
        val fake = Fake()
        fake.hint = PushWakeIntake.Hint.UNAVAILABLE
        assertEquals(PushWakeIntake.Result.UNAVAILABLE, fake.dispatcher.onData(data, 100))
        assertEquals(0, fake.routeWrites)
        assertEquals(0, fake.failures.size)
        assertEquals(PushWakeIntake.Result.QUEUED, fake.dispatcher.onDeletedMessages(101))
        assertEquals(1, fake.recoveryWrites)
        assertEquals(1, fake.failures.size)
    }

    @Test fun acceptedEnqueueFailureKeepsInboxAndResumeRedrivesIt() {
        val fake = Fake()
        assertEquals(PushWakeIntake.Result.QUEUED, fake.dispatcher.onData(data, 100))
        fake.failures[0].invoke() // async WorkManager operation failure
        assertTrue(fake.persisted)
        assertEquals(PushWakeIntake.Result.QUEUED, fake.dispatcher.resume(101))
        assertEquals(2, fake.failures.size)
        fake.schedulerAvailable = false
        fake.failures[1].invoke()
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, fake.dispatcher.resume(102))
        assertTrue(fake.persisted)
    }

    @Test fun failedOrUnavailableFinalReadRetries() {
        val fake = Fake()
        fake.dispatcher.onData(data, 100)
        val ticket = fake.dispatcher.workerStarted()
        fake.persisted = false
        assertEquals(PushWakeScheduleGate.Finish.RETRY, fake.dispatcher.workerFinished(ticket, true, 101))
        fake.readsUnavailable = true
        assertEquals(PushWakeScheduleGate.Finish.RETRY, fake.dispatcher.workerFinished(ticket, false, 101))
        fake.dispatcher.workerStopped(ticket)
        fake.readsUnavailable = false
        assertEquals(PushWakeIntake.Result.IGNORED, fake.dispatcher.resume(102))
    }

    @Test fun enqueueAndSchedulerRequestAreAtomicAgainstFinalEmptyCheck() {
        val checking = CountDownLatch(1)
        val allowCheck = CountDownLatch(1)
        val arrivalStarted = CountDownLatch(1)
        val storedSecond = CountDownLatch(1)
        val blockRead = AtomicBoolean(false)
        val writes = AtomicInteger(0)
        val scheduled = AtomicInteger(0)
        val threadError = AtomicReference<Throwable?>()
        val gate = PushWakeScheduleGate({ _ -> scheduled.incrementAndGet(); true }, { error("unexpected") })
        var pending = false // accesses are serialized by gate; join publishes final value
        val dispatcher = PushWakeDispatcher(gate, { PushWakeIntake.Hint.FOUND }, { _, _ ->
            pending = true
            if (writes.incrementAndGet() == 2) storedSecond.countDown()
            PushWakeInbox.EnqueueResult.QUEUED
        }, { PushWakeInbox.EnqueueResult.UNAVAILABLE }, { now ->
            if (blockRead.get()) {
                checking.countDown()
                check(allowCheck.await(5, TimeUnit.SECONDS))
            }
            if (pending) PushWakeInbox.PendingResult.Pending(now) else PushWakeInbox.PendingResult.Empty
        }, { error("unexpected") })
        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.onData(data, 100))
        val ticket = dispatcher.workerStarted()
        gate.atomic { pending = false } // previous signal was consumed
        blockRead.set(true)
        val finish = Thread {
            try { check(dispatcher.workerFinished(ticket, false, 101) == PushWakeScheduleGate.Finish.SUCCESS) }
            catch (error: Throwable) { threadError.set(error) }
        }
        val arrival = Thread {
            try {
                arrivalStarted.countDown()
                check(dispatcher.onData(data, 102) == PushWakeIntake.Result.QUEUED)
            } catch (error: Throwable) { threadError.set(error) }
        }
        try {
            finish.start()
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            arrival.start()
            assertTrue(arrivalStarted.await(2, TimeUnit.SECONDS))
            assertFalse(storedSecond.await(100, TimeUnit.MILLISECONDS))
            allowCheck.countDown()
            assertTrue(storedSecond.await(2, TimeUnit.SECONDS))
        } finally {
            allowCheck.countDown()
            finish.join(5_000)
            arrival.join(5_000)
        }
        assertFalse(finish.isAlive)
        assertFalse(arrival.isAlive)
        assertNull(threadError.get())
        assertTrue(pending)
        assertEquals(2, scheduled.get()) // the backend must accept a successor while old work returns
    }
}
