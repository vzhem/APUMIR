package com.vladimir.messenger.data.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake inbox and scheduler; WorkManager, Firebase and message delivery are not exercised. */
class PushWakeRunLoopTest {
    private class Fake {
        var now = 100L
        var pending = false
        var unavailable = false
        var failures = 0
        val scheduled = mutableListOf<() -> Unit>()
        val gate = PushWakeScheduleGate({ onFailure -> scheduled += onFailure; true }, { failures++ })
        val dispatcher = PushWakeDispatcher(
            gate,
            { PushWakeIntake.Hint.FOUND },
            { _, _ -> pending = true; PushWakeInbox.EnqueueResult.QUEUED },
            { pending = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
            { time -> when {
                unavailable -> PushWakeInbox.PendingResult.Unavailable
                pending -> PushWakeInbox.PendingResult.Pending(time)
                else -> PushWakeInbox.PendingResult.Empty
            } },
            { failures++ },
        )
        val payload = mapOf("v" to "1", "route" to "12".repeat(32))

        fun wake() = dispatcher.onData(payload, now++)
        fun loop(drain: suspend () -> PushWakeDrain.Result) =
            PushWakeRunLoop(dispatcher, drain, { now++ }, { failures++ })
    }

    @Test fun newSignalDuringDrainIsRetriedAndAfterFinishSchedulesASuccessor() = runBlocking {
        val fake = Fake()
        assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
        val first = fake.loop {
            fake.pending = false // previous signal was processed
            assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
            PushWakeDrain.Result.Empty // stale observation from before the new signal
        }
        assertEquals(PushWakeRunLoop.Result.RETRY, first.run())
        assertEquals(1, fake.scheduled.size)
        assertTrue(fake.pending)
        val retry = fake.loop { fake.pending = false; PushWakeDrain.Result.Empty }
        assertEquals(PushWakeRunLoop.Result.SUCCESS, retry.run())
        assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
        assertEquals(2, fake.scheduled.size)
    }

    @Test fun failedDrainOrUnavailableFinalReadNeverBecomeSuccess() = runBlocking {
        val fake = Fake()
        fake.wake()
        fake.pending = false
        assertEquals(PushWakeRunLoop.Result.RETRY, fake.loop { PushWakeDrain.Result.Unavailable }.run())
        fake.unavailable = true
        assertEquals(PushWakeRunLoop.Result.RETRY, fake.loop { PushWakeDrain.Result.Empty }.run())
        fake.unavailable = false
        assertEquals(PushWakeRunLoop.Result.SUCCESS, fake.loop { PushWakeDrain.Result.Empty }.run())
    }

    @Test fun cancellationReleasesOnlyTheWorkerTicketAndAllowsAnotherRequest() = runBlocking {
        withTimeout(5_000) {
            val fake = Fake()
            fake.wake()
            val started = CompletableDeferred<Unit>()
            val loop = fake.loop { started.complete(Unit); awaitCancellation() }
            val task = launch { loop.run() }
            started.await()
            task.cancelAndJoin()
            assertTrue(fake.pending)
            assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
            assertEquals(2, fake.scheduled.size)
            assertEquals(0, fake.failures)
        }
    }

    @Test fun independentRecoveryPassCanRepairAStaleRetryTicket() = runBlocking {
        val fake = Fake()
        fake.wake()
        val failed = fake.loop { error("sensitive details must not be reported") }
        assertEquals(PushWakeRunLoop.Result.RETRY, failed.run())
        assertEquals(1, fake.failures)
        // An in-memory ticket can remain after a scheduler failure. A periodic
        // recovery job must drain directly, not call the coalescing request().
        assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
        assertEquals(1, fake.scheduled.size)
        val recovery = fake.loop { fake.pending = false; PushWakeDrain.Result.Empty }
        assertEquals(PushWakeRunLoop.Result.SUCCESS, recovery.run())
        assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
        assertEquals(2, fake.scheduled.size)
    }
}
