package com.vladimir.messenger.data.push

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake inbox and scheduler; WorkManager, Firebase and message delivery are not exercised. */
class PushWakeRunLoopTest {
    private class Fake {
        var now = 100L
        var pending = false
        var unavailable = false
        var pendingReads = 0
        var failures = 0
        val scheduled = mutableListOf<() -> Unit>()
        val gate = PushWakeScheduleGate({ onFailure -> scheduled += onFailure; true }, { failures++ })
        val dispatcher = PushWakeDispatcher(
            gate,
            { PushWakeIntake.Hint.FOUND },
            { _, _ -> pending = true; PushWakeInbox.EnqueueResult.QUEUED },
            { pending = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
            { time ->
                pendingReads++
                when {
                    unavailable -> PushWakeInbox.PendingResult.Unavailable
                    pending -> PushWakeInbox.PendingResult.Pending(time)
                    else -> PushWakeInbox.PendingResult.Empty
                }
            },
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

    @Test fun busyLoopRetriesWithoutReplacingTheActiveTicket() = runBlocking {
        withTimeout(5_000) {
            val fake = Fake()
            assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async { fake.loop {
                entered.complete(Unit)
                release.await()
                fake.pending = false // fake queue; only the scheduling gate is under test
                PushWakeDrain.Result.Empty
            }.run() }
            try {
                entered.await()
                var overlappingDrains = 0
                assertEquals(PushWakeRunLoop.Result.RETRY, fake.loop {
                    overlappingDrains++
                    PushWakeDrain.Result.Empty
                }.run())
                assertEquals(0, overlappingDrains)
                assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
                assertEquals(1, fake.scheduled.size)
                release.complete(Unit)
                assertEquals(PushWakeRunLoop.Result.SUCCESS, first.await())
                // The rejected concurrent worker must not leave a stale ticket.
                assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
                assertEquals(2, fake.scheduled.size)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun distinctDispatchersShareTheClassloaderDrainLimit() = runBlocking {
        withTimeout(5_000) {
            val one = Fake()
            val two = Fake()
            one.wake()
            two.wake()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async { one.loop {
                entered.complete(Unit)
                release.await()
                one.pending = false
                PushWakeDrain.Result.Empty
            }.run() }
            try {
                entered.await()
                var secondDrains = 0
                val second = two.loop { secondDrains++; two.pending = false; PushWakeDrain.Result.Empty }
                assertEquals(PushWakeRunLoop.Result.RETRY, second.run())
                assertEquals(0, secondDrains)
                assertTrue(two.pending)
                assertEquals(1, two.scheduled.size)
                release.complete(Unit)
                assertEquals(PushWakeRunLoop.Result.SUCCESS, first.await())
                assertEquals(PushWakeRunLoop.Result.SUCCESS, second.run())
                assertEquals(1, secondDrains)
                assertEquals(PushWakeIntake.Result.QUEUED, two.wake())
                assertEquals(2, two.scheduled.size)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun simultaneousThreadsAdmitOneDrainAndRetryEveryOtherWorker() {
        val count = 12
        val workers = List(count) { Fake().also { it.wake() } }
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CountDownLatch(count - 1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val pool = Executors.newFixedThreadPool(count)
        val results = workers.map { fake ->
            pool.submit<PushWakeRunLoop.Result> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                val result = runBlocking {
                    fake.loop {
                        val current = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, current) }
                        entered.countDown()
                        try { check(release.await(60, TimeUnit.SECONDS)) }
                        finally { active.decrementAndGet() }
                        fake.pending = false
                        PushWakeDrain.Result.Empty
                    }.run()
                }
                if (result == PushWakeRunLoop.Result.RETRY) rejected.countDown()
                result
            }
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(rejected.await(10, TimeUnit.SECONDS))
            assertEquals(1, maximum.get())
            release.countDown()
            val finished = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, finished.count { it == PushWakeRunLoop.Result.SUCCESS })
            assertEquals(count - 1, finished.count { it == PushWakeRunLoop.Result.RETRY })
            for ((index, result) in finished.withIndex()) {
                if (result == PushWakeRunLoop.Result.RETRY) {
                    assertTrue(workers[index].pending)
                    assertEquals(PushWakeRunLoop.Result.SUCCESS, runBlocking {
                        workers[index].loop {
                            workers[index].pending = false
                            PushWakeDrain.Result.Empty
                        }.run()
                    })
                }
            }
            assertTrue(workers.none { it.pending })
            assertEquals(0, active.get())
        } finally {
            start.countDown()
            release.countDown()
            pool.shutdownNow()
            assertTrue("test workers did not stop", pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledCallerCannotTurnBusyLimitIntoRetry() = runBlocking {
        withTimeout(5_000) {
            val one = Fake()
            val two = Fake()
            one.wake()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val running = async { one.loop {
                entered.complete(Unit)
                release.await()
                one.pending = false
                PushWakeDrain.Result.Empty
            }.run() }
            try {
                entered.await()
                var returned = false
                val cancelled = launch(start = CoroutineStart.LAZY) {
                    coroutineContext[Job]!!.cancel()
                    two.loop { error("cancelled work must not start") }.run()
                    returned = true
                }
                cancelled.start()
                cancelled.join()
                assertTrue(cancelled.isCancelled)
                assertFalse(returned)
                release.complete(Unit)
                assertEquals(PushWakeRunLoop.Result.SUCCESS, running.await())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun cancellationReleasesTheClassloaderLimitForAnotherLoop() = runBlocking {
        withTimeout(5_000) {
            val one = Fake()
            val two = Fake()
            one.wake()
            two.wake()
            val entered = CompletableDeferred<Unit>()
            val running = launch { one.loop { entered.complete(Unit); awaitCancellation() }.run() }
            entered.await()
            assertEquals(PushWakeRunLoop.Result.RETRY, two.loop { error("must not overlap") }.run())
            running.cancelAndJoin()
            assertEquals(PushWakeRunLoop.Result.SUCCESS,
                two.loop { two.pending = false; PushWakeDrain.Result.Empty }.run())
            assertEquals(PushWakeIntake.Result.QUEUED, one.wake())
            assertEquals(2, one.scheduled.size)
            assertEquals(0, one.failures)
        }
    }

    @Test fun exceptionsAndBrokenDiagnosticsReleaseTheClassloaderLimit() = runBlocking {
        val one = Fake()
        val two = Fake()
        one.wake()
        two.wake()
        val broken = PushWakeRunLoop(one.dispatcher, drain = { error("private details") },
            nowMs = { 100L }, recordFailure = { error("broken counter") })
        assertEquals(PushWakeRunLoop.Result.RETRY, broken.run())
        assertTrue(one.pending)
        assertEquals(PushWakeRunLoop.Result.SUCCESS,
            two.loop { two.pending = false; PushWakeDrain.Result.Empty }.run())
        // A later recovery pass can also replace the old retry ticket.
        assertEquals(PushWakeRunLoop.Result.SUCCESS,
            one.loop { one.pending = false; PushWakeDrain.Result.Empty }.run())
    }

    @Test fun cancellationAfterDrainDoesNotReturnSuccessAndReleasesTicket() = runBlocking {
        val fake = Fake()
        fake.wake()
        var returned = false
        val task = launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext[Job]!!
            fake.loop {
                fake.pending = false
                job.cancel()
                PushWakeDrain.Result.Empty
            }.run()
            returned = true
        }
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
        assertEquals(0, fake.pendingReads) // cancellation precedes the final SQLite/scheduler check
        assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
        assertEquals(2, fake.scheduled.size)
        assertEquals(PushWakeRunLoop.Result.SUCCESS,
            fake.loop { fake.pending = false; PushWakeDrain.Result.Empty }.run())
    }

    @Test fun cancellationRacingAnErrorReleasesTicketAndClassloaderLimit() = runBlocking {
        for (stage in listOf("drain", "diagnostics")) {
            val fake = Fake()
            fake.wake()
            var returned = false
            var reported = 0
            val task = launch(start = CoroutineStart.LAZY) {
                val job = coroutineContext[Job]!!
                val loop = PushWakeRunLoop(fake.dispatcher,
                    drain = {
                        if (stage == "drain") job.cancel()
                        error("failure racing cancellation")
                    },
                    nowMs = { 100L },
                    recordFailure = { reported++; job.cancel() },
                )
                loop.run()
                returned = true
            }
            task.start()
            task.join()
            assertTrue(task.isCancelled)
            assertFalse(returned)
            assertEquals(if (stage == "diagnostics") 1 else 0, reported)
            assertEquals(PushWakeIntake.Result.QUEUED, fake.wake())
            assertEquals(2, fake.scheduled.size)
            assertEquals(PushWakeRunLoop.Result.SUCCESS,
                fake.loop { fake.pending = false; PushWakeDrain.Result.Empty }.run())
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
