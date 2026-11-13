package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake storage, admission, sync and scheduler; no FCM, WorkManager or delivery. */
class PushWakePeriodicRecoveryTest {
    private class Fake {
        val calls = mutableListOf<String>()
        var admission = PushWakePeriodicRecovery.Admission.ALLOW
        var marker = PushWakeInbox.EnqueueResult.RECOVERY_QUEUED
        var drain = PushWakeRunLoop.Result.SUCCESS
        var stored = false
        var failures = 0
        var admissionError: Exception? = null
        var markerError: Exception? = null
        var drainError: Exception? = null

        fun recovery() = PushWakePeriodicRecovery(
            admission = {
                calls += "admission"
                admissionError?.let { throw it }
                admission
            },
            ensureMarker = { now ->
                assertEquals(1234L, now)
                calls += "marker"
                markerError?.let { throw it }
                if (marker == PushWakeInbox.EnqueueResult.RECOVERY_QUEUED) stored = true
                marker
            },
            runDrain = {
                calls += "drain"
                drainError?.let { throw it }
                drain
            },
            nowMs = { 1234L },
            recordFailure = { failures++ },
        )
    }

    @Test fun allowedTickPersistsGenericMarkerBeforeDirectDrain() = runBlocking {
        val fake = Fake()
        assertEquals(PushWakePeriodicRecovery.Result.TASK_SUCCESS, fake.recovery().run())
        assertEquals(listOf("admission", "marker", "drain"), fake.calls)
        assertTrue(fake.stored)
        assertEquals(0, fake.failures)
        // Task success is not a stored message, displayed notification or receipt.
    }

    @Test fun disabledOrUnavailableAdmissionNeverWritesOrStartsDrain() = runBlocking {
        for ((admission, expected) in listOf(
            PushWakePeriodicRecovery.Admission.DISABLED to PushWakePeriodicRecovery.Result.DISABLED,
            PushWakePeriodicRecovery.Admission.RETRY to PushWakePeriodicRecovery.Result.RETRY,
        )) {
            val fake = Fake().apply { this.admission = admission }
            assertEquals(expected, fake.recovery().run())
            assertEquals(listOf("admission"), fake.calls)
            assertFalse(fake.stored)
        }
    }

    @Test fun unavailableOrUnexpectedMarkerNeverRunsOrReportsSuccess() = runBlocking {
        for (marker in listOf(PushWakeInbox.EnqueueResult.INVALID, PushWakeInbox.EnqueueResult.UNAVAILABLE,
                              PushWakeInbox.EnqueueResult.QUEUED)) {
            val fake = Fake().apply { this.marker = marker }
            assertEquals(PushWakePeriodicRecovery.Result.RETRY, fake.recovery().run())
            assertEquals(listOf("admission", "marker"), fake.calls)
            assertFalse(fake.stored)
        }
    }

    @Test fun retryingDrainDoesNotPretendTheSavedMarkerWasProcessed() = runBlocking {
        val fake = Fake().apply { drain = PushWakeRunLoop.Result.RETRY }
        assertEquals(PushWakePeriodicRecovery.Result.RETRY, fake.recovery().run())
        assertEquals(listOf("admission", "marker", "drain"), fake.calls)
        assertTrue(fake.stored)
    }

    @Test fun failedAdmissionMarkerOrDrainAreReportedWithoutBecomingSuccess() = runBlocking {
        for (stage in listOf("admission", "marker", "drain")) {
            val fake = Fake()
            val error = IllegalStateException("secret details must not be reported")
            when (stage) {
                "admission" -> fake.admissionError = error
                "marker" -> fake.markerError = error
                else -> fake.drainError = error
            }
            assertEquals(PushWakePeriodicRecovery.Result.RETRY, fake.recovery().run())
            assertEquals(1, fake.failures)
            assertEquals(stage == "drain", fake.stored)
        }
    }

    @Test fun cancellationPropagatesFromEachStageWithoutBeingReported() = runBlocking {
        for (stage in listOf("admission", "marker", "drain")) {
            val fake = Fake()
            val cancelled = CancellationException("do not turn into success")
            when (stage) {
                "admission" -> fake.admissionError = cancelled
                "marker" -> fake.markerError = cancelled
                else -> fake.drainError = cancelled
            }
            assertTrue(runCatching { fake.recovery().run() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fake.failures)
        }
    }

    @Test fun cancellationAfterMarkerCommitLeavesItForAnotherTick() = runBlocking {
        var saved = false
        var drained = false
        var failures = 0
        val task = launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext[Job]!!
            PushWakePeriodicRecovery(
                admission = { PushWakePeriodicRecovery.Admission.ALLOW },
                ensureMarker = { saved = true; job.cancel(); PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
                runDrain = { drained = true; PushWakeRunLoop.Result.SUCCESS },
                nowMs = { 100L },
                recordFailure = { failures++ },
            ).run()
        }
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(saved)
        assertFalse(drained)
        assertEquals(0, failures)
    }

    @Test fun cancellationWhileAdmissionIsSuspendedDoesNotWriteMarker() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            var saved = false
            var failures = 0
            val periodic = PushWakePeriodicRecovery(
                admission = { started.complete(Unit); awaitCancellation() },
                ensureMarker = { saved = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
                runDrain = { PushWakeRunLoop.Result.SUCCESS },
                nowMs = { 100L },
                recordFailure = { failures++ },
            )
            val job = launch { periodic.run() }
            started.await()
            job.cancelAndJoin()
            assertFalse(saved)
            assertEquals(0, failures)
        }
    }

    @Test fun cancellationAfterAdmissionOrDrainCannotReturnATaskResult() = runBlocking {
        for (stage in listOf("admission", "drain")) {
            var saved = false
            var returned = false
            var failures = 0
            val task = launch(start = CoroutineStart.LAZY) {
                val job = coroutineContext[Job]!!
                PushWakePeriodicRecovery(
                    admission = {
                        if (stage == "admission") {
                            job.cancel()
                            PushWakePeriodicRecovery.Admission.DISABLED
                        } else PushWakePeriodicRecovery.Admission.ALLOW
                    },
                    ensureMarker = { saved = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
                    runDrain = { job.cancel(); PushWakeRunLoop.Result.SUCCESS },
                    nowMs = { 100L },
                    recordFailure = { failures++ },
                ).run()
                returned = true
            }
            task.start()
            task.join()
            assertTrue(task.isCancelled)
            assertFalse(returned)
            assertEquals(stage == "drain", saved)
            assertEquals(0, failures)
        }
    }

    @Test fun cancellationWhileDrainIsSuspendedLeavesTheMarkerForAnotherTick() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            var saved = false
            var failures = 0
            val periodic = PushWakePeriodicRecovery(
                admission = { PushWakePeriodicRecovery.Admission.ALLOW },
                ensureMarker = { saved = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
                runDrain = { started.complete(Unit); awaitCancellation() },
                nowMs = { 100L },
                recordFailure = { failures++ },
            )
            val job = launch { periodic.run() }
            started.await()
            job.cancelAndJoin()
            assertTrue(saved)
            assertEquals(0, failures)
        }
    }

    @Test fun diagnosticFailureCannotMakeARecoverableFailureTerminal() = runBlocking {
        val periodic = PushWakePeriodicRecovery(
            admission = { PushWakePeriodicRecovery.Admission.ALLOW },
            ensureMarker = { throw IllegalStateException("write failed") },
            runDrain = { error("must not drain") },
            nowMs = { 100L },
            recordFailure = { throw IllegalStateException("counter failed") },
        )
        assertEquals(PushWakePeriodicRecovery.Result.RETRY, periodic.run())
    }

    @Test fun cancellationRacingAnOrdinaryFailureIsNotReturnedAsRetry() = runBlocking {
        for (stage in listOf("admission", "diagnostics")) {
            var returned = false
            var reported = 0
            val task = launch(start = CoroutineStart.LAZY) {
                val job = coroutineContext[Job]!!
                PushWakePeriodicRecovery(
                    admission = {
                        if (stage == "admission") job.cancel()
                        throw IllegalStateException("failure before cancellation check")
                    },
                    ensureMarker = { error("must not write") },
                    runDrain = { error("must not drain") },
                    nowMs = { 100L },
                    recordFailure = { reported++; job.cancel() },
                ).run()
                returned = true
            }
            task.start()
            task.join()
            assertTrue(task.isCancelled)
            assertFalse(returned)
            assertEquals(if (stage == "diagnostics") 1 else 0, reported)
        }
    }

    @Test fun independentTickRepairsAnUnrecordedCallbackDespiteAStaleGate() = runBlocking {
        var stored = false
        var scheduled = 0
        var scans = 0
        var failures = 0
        val gate = PushWakeScheduleGate({ _ -> scheduled++; true }, { failures++ })
        val dispatcher = PushWakeDispatcher(
            gate,
            lookup = { PushWakeIntake.Hint.UNAVAILABLE },
            enqueueRoute = { _, _ -> error("unavailable index must not enqueue a route") },
            enqueueRecovery = { PushWakeInbox.EnqueueResult.UNAVAILABLE },
            pending = { now -> if (stored) PushWakeInbox.PendingResult.Pending(now) else PushWakeInbox.PendingResult.Empty },
            recordFailure = { failures++ },
        )
        val stale = dispatcher.workerStarted() // model a stale in-memory ticket
        val lost = dispatcher.onData(mapOf("v" to "1", "route" to "ab".repeat(32)), 100L)
        assertEquals(PushWakeIntake.Result.UNAVAILABLE, lost)
        assertFalse(stored)
        assertEquals(0, scheduled)
        val loop = PushWakeRunLoop(dispatcher, drain = {
            assertTrue(stored)
            scans++ // fake only; not a real mailbox scan
            stored = false
            PushWakeDrain.Result.Empty
        }, nowMs = { 101L }, recordFailure = { failures++ })
        val periodic = PushWakePeriodicRecovery(
            admission = { PushWakePeriodicRecovery.Admission.ALLOW },
            ensureMarker = { stored = true; PushWakeInbox.EnqueueResult.RECOVERY_QUEUED },
            runDrain = loop::run,
            nowMs = { 101L },
            recordFailure = { failures++ },
        )
        assertEquals(PushWakePeriodicRecovery.Result.TASK_SUCCESS, periodic.run())
        assertEquals(1, scans)
        assertEquals(0, scheduled) // no coalescing request hides this independent pass
        assertFalse(stored)
        assertEquals(0, failures)
        dispatcher.workerStopped(stale)
    }
}
