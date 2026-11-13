package com.vladimir.messenger.data.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.collections.AbstractList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests production drain logic with an in-memory queue; not an Android/FCM test. */
class PushWakeDrainTest {
    private class FakeQueue : PushWakeQueue {
        private val item = PushWakeInbox.Claim("12".repeat(32), 1L, 1L, "ab".repeat(32))
        var pending = true
        var leased = false
        var due = 100L
        val completions = mutableListOf<Boolean>()

        override fun claim(nowMs: Long): PushWakeInbox.ClaimResult {
            if (!pending || leased || nowMs < due) return PushWakeInbox.ClaimResult.Empty
            leased = true
            return PushWakeInbox.ClaimResult.Ready(item)
        }
        override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion {
            check(claim === item && leased)
            completions += success
            leased = false
            if (success) {
                pending = false
                return PushWakeInbox.Completion.COMPLETED
            }
            due = nowMs + 15_000
            return PushWakeInbox.Completion.RETRY_QUEUED
        }
        override fun pending(nowMs: Long): PushWakeInbox.PendingResult =
            if (pending) PushWakeInbox.PendingResult.Pending(if (leased) nowMs + 60_000 else due)
            else PushWakeInbox.PendingResult.Empty
    }

    private class BatchQueue(routes: List<String?>) : PushWakeQueue {
        private val items = routes.mapIndexed { index, route ->
            PushWakeInbox.Claim(route, index.toLong() + 1, index.toLong() + 1, "ab".repeat(32),
                if (route == null) "cd".repeat(32) else null)
        }
        private var next = 0
        val completions = mutableListOf<Pair<String?, Boolean>>()

        override fun claim(nowMs: Long): PushWakeInbox.ClaimResult =
            if (next == items.size) PushWakeInbox.ClaimResult.Empty
            else PushWakeInbox.ClaimResult.Ready(items[next++])

        override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion {
            completions += claim.route to success
            return if (success) PushWakeInbox.Completion.COMPLETED else PushWakeInbox.Completion.RETRY_QUEUED
        }

        override fun pending(nowMs: Long): PushWakeInbox.PendingResult = when {
            next < items.size -> PushWakeInbox.PendingResult.Pending(nowMs)
            completions.any { !it.second } -> PushWakeInbox.PendingResult.Pending(nowMs + 15_000)
            else -> PushWakeInbox.PendingResult.Empty
        }
    }

    @Test fun startedButUnfinishedWindowRetriesAndOnlyACompletedWindowConsumes() = runBlocking {
        val queue = FakeQueue()
        var now = 100L
        var attempts = 0
        var outcome = PushWakeDrain.SyncOutcome.RETRY
        val drain = PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            attempts++
            batch.items.map { it.result(outcome) }
        }, { error("unexpected failure") })
        val pending = drain.run() as PushWakeDrain.Result.Pending
        assertEquals(15_100L, pending.readyAtMs)
        assertEquals(listOf(false), queue.completions)
        assertTrue(queue.pending)
        now = pending.readyAtMs
        outcome = PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        assertEquals(PushWakeDrain.Result.Empty, drain.run())
        assertEquals(2, attempts)
        assertEquals(listOf(false, true), queue.completions)
        assertFalse(queue.pending)
    }

    @Test fun cancelledWindowLeavesTheClaimForLeaseRecovery() = runBlocking {
        withTimeout(5_000) {
            val queue = FakeQueue()
            val entered = CompletableDeferred<Unit>()
            val drain = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, {
                entered.complete(Unit)
                awaitCancellation()
            }, { error("cancellation must not be recorded as an error") })
            val job = launch { drain.run() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(queue.pending)
            assertTrue(queue.leased)
            assertTrue(queue.completions.isEmpty())
        }
    }

    @Test fun cancellationRacingCallbacksCannotConsumeOrReleaseTheClaim() = runBlocking {
        val stages = listOf("before", "clockBeforeClaim", "claim", "inspectAllow", "inspectDrop", "inspectRetry",
            "inspectFailure", "sync", "syncFailure", "resultRead", "clockBeforeComplete", "invalidResult",
            "diagnostics", "invalidDiagnosticsThrows", "diagnosticsThrows")
        for (stage in stages) {
            val stored = FakeQueue()
            var inspections = 0
            var syncs = 0
            var diagnostics = 0
            var clockReads = 0
            var resultReads = 0
            var returned = false
            var ordinaryFailure = false
            val job = launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]!!
                val queue = object : PushWakeQueue {
                    override fun claim(nowMs: Long): PushWakeInbox.ClaimResult = stored.claim(nowMs).also {
                        if (stage == "claim" && it is PushWakeInbox.ClaimResult.Ready) self.cancel()
                    }
                    override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long) =
                        stored.complete(claim, success, nowMs)
                    override fun pending(nowMs: Long) = stored.pending(nowMs)
                }
                val drain = PushWakeDrain(queue, {
                    clockReads++
                    if (stage == "clockBeforeClaim" && clockReads == 1 || stage == "clockBeforeComplete" && clockReads == 3) {
                        self.cancel()
                    }
                    100
                }, {
                    inspections++
                    when (stage) {
                        "inspectAllow" -> { self.cancel(); PushWakeDrain.Inspection.ALLOW }
                        "inspectDrop" -> { self.cancel(); PushWakeDrain.Inspection.DROP }
                        "inspectRetry" -> { self.cancel(); PushWakeDrain.Inspection.RETRY }
                        "inspectFailure" -> { self.cancel(); error("private detail") }
                        "diagnostics", "diagnosticsThrows" -> error("private detail")
                        else -> PushWakeDrain.Inspection.ALLOW
                    }
                }, { batch ->
                    syncs++
                    when (stage) {
                        "sync" -> {
                            val completed = batch.items.single().result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW)
                            self.cancel()
                            object : AbstractList<PushWakeDrain.SyncResult>() {
                                override val size: Int get() { resultReads++; return 1 }
                                override fun get(index: Int): PushWakeDrain.SyncResult { resultReads++; return completed }
                            }
                        }
                        "syncFailure" -> { self.cancel(); error("private detail") }
                        "resultRead" -> {
                            val completed = batch.items.single().result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW)
                            object : AbstractList<PushWakeDrain.SyncResult>() {
                                override val size = 1
                                override fun get(index: Int): PushWakeDrain.SyncResult {
                                    self.cancel()
                                    return completed
                                }
                            }
                        }
                        "invalidResult", "invalidDiagnosticsThrows" -> emptyList()
                        else -> batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
                    }
                }, {
                    diagnostics++
                    if (stage in setOf("diagnostics", "invalidResult", "diagnosticsThrows", "invalidDiagnosticsThrows")) {
                        self.cancel()
                        if (stage.endsWith("Throws")) error("broken diagnostics")
                    }
                })
                if (stage == "before") self.cancel()
                try {
                    drain.run()
                    returned = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Capture an incorrectly surfaced non-cancellation failure.
                    ordinaryFailure = true
                }
            }
            job.start()
            job.join()
            assertTrue("$stage must cancel", job.isCancelled)
            assertFalse("$stage must not return", returned)
            assertFalse("$stage must not surface an ordinary failure", ordinaryFailure)
            assertTrue("$stage must keep the signal", stored.pending)
            assertEquals("$stage lease", stage != "before" && stage != "clockBeforeClaim", stored.leased)
            assertTrue("$stage must not complete the claim", stored.completions.isEmpty())
            if (stage == "before" || stage == "clockBeforeClaim" || stage == "claim") {
                assertEquals("$stage inspection", 0, inspections)
            }
            if (stage.startsWith("inspect") || stage == "before" || stage == "clockBeforeClaim" ||
                stage == "claim" || stage.startsWith("diagnostics")
            ) {
                assertEquals("$stage sync", 0, syncs)
            }
            if (stage == "sync") assertEquals("$stage result reads", 0, resultReads)
            val diagnosticStage = stage in setOf("diagnostics", "invalidResult", "diagnosticsThrows", "invalidDiagnosticsThrows")
            assertEquals("$stage diagnostics", if (diagnosticStage) 1 else 0, diagnostics)
        }
    }

    @Test fun cancellationExceptionsFromCallbacksPropagateWithoutCompletingClaims() = runBlocking {
        for (stage in listOf("inspection", "sync", "resultRead", "diagnostics", "invalidDiagnostics")) {
            val queue = FakeQueue()
            var diagnostics = 0
            var syncs = 0
            val drain = PushWakeDrain(queue, { 100 }, {
                when (stage) {
                    "inspection" -> throw CancellationException("stop")
                    "diagnostics" -> error("private inspection detail")
                    else -> PushWakeDrain.Inspection.ALLOW
                }
            }, { batch ->
                syncs++
                when (stage) {
                    "sync" -> throw CancellationException("stop")
                    "resultRead" -> object : AbstractList<PushWakeDrain.SyncResult>() {
                        override val size = 1
                        override fun get(index: Int): PushWakeDrain.SyncResult = throw CancellationException("stop")
                    }
                    "invalidDiagnostics" -> emptyList()
                    else -> batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
                }
            }, {
                diagnostics++
                throw CancellationException("stop")
            })
            var cancelled = false
            try { drain.run() } catch (_: CancellationException) { cancelled = true }
            assertTrue("$stage cancellation must propagate", cancelled)
            assertTrue(queue.pending && queue.leased)
            assertTrue(queue.completions.isEmpty())
            assertEquals(if (stage == "diagnostics" || stage == "invalidDiagnostics") 1 else 0, diagnostics)
            assertEquals(if (stage == "inspection" || stage == "diagnostics") 0 else 1, syncs)
        }
    }

    @Test fun cancellationAfterOneCompletionLeavesTheRemainingLeaseUntouched() = runBlocking {
        val a = "12".repeat(32)
        val b = "34".repeat(32)
        val stored = BatchQueue(listOf(a, b))
        var returned = false
        val job = launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val queue = object : PushWakeQueue {
                override fun claim(nowMs: Long) = stored.claim(nowMs)
                override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long) =
                    stored.complete(claim, success, nowMs).also {
                        if (stored.completions.size == 1) self.cancel()
                    }
                override fun pending(nowMs: Long) = stored.pending(nowMs)
            }
            PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
            }, { error("unexpected diagnostics") }).run()
            returned = true
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        // The first SQLite transaction could already have committed. Cancellation
        // cannot undo it, but the second lease must not be completed afterwards.
        assertEquals(listOf(a to true), stored.completions)
    }

    @Test fun cancellationDuringFinalPendingReadCannotReturnSuccess() = runBlocking {
        var returned = false
        val job = launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val queue = object : PushWakeQueue {
                override fun claim(nowMs: Long) = PushWakeInbox.ClaimResult.Empty
                override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion =
                    error("nothing was leased")
                override fun pending(nowMs: Long): PushWakeInbox.PendingResult {
                    self.cancel()
                    return PushWakeInbox.PendingResult.Empty
                }
            }
            PushWakeDrain(queue, { 100 }, { error("nothing to inspect") }, { error("nothing to sync") },
                { error("unexpected diagnostics") }).run()
            returned = true
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    @Test fun cancellationAtFinalClockDoesNotStartPendingRead() = runBlocking {
        var clockReads = 0
        var pendingReads = 0
        var returned = false
        val job = launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val queue = object : PushWakeQueue {
                override fun claim(nowMs: Long) = PushWakeInbox.ClaimResult.Empty
                override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion =
                    error("nothing was leased")
                override fun pending(nowMs: Long): PushWakeInbox.PendingResult {
                    pendingReads++
                    return PushWakeInbox.PendingResult.Empty
                }
            }
            PushWakeDrain(queue, {
                if (++clockReads == 2) self.cancel()
                100
            }, { error("nothing to inspect") }, { error("nothing to sync") },
                { error("unexpected diagnostics") }).run()
            returned = true
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(2, clockReads)
        assertEquals(0, pendingReads)
    }

    @Test fun brokenDiagnosticsStillRetryEachUnprocessedSignal() = runBlocking {
        for (stage in listOf("inspection", "sync", "invalid")) {
            val queue = FakeQueue()
            var diagnostics = 0
            var syncs = 0
            val result = PushWakeDrain(queue, { 100 }, {
                if (stage == "inspection") error("private route detail")
                PushWakeDrain.Inspection.ALLOW
            }, { batch ->
                syncs++
                if (stage == "sync") error("private sync detail")
                if (stage == "invalid") emptyList()
                else batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
            }, { diagnostics++; error("private diagnostic detail") }).run()
            assertTrue("$stage must retry", result is PushWakeDrain.Result.Pending)
            assertEquals("$stage completion", listOf(false), queue.completions)
            assertTrue(queue.pending)
            assertEquals(1, diagnostics)
            assertEquals(if (stage == "inspection") 0 else 1, syncs)
        }
    }

    @Test fun failedInspectionRetriesAndDroppedRouteDoesNotStartSync() = runBlocking {
        val retry = FakeQueue()
        var failures = 0
        var syncs = 0
        val pending = PushWakeDrain(retry, { 100 }, { error("do not log sensitive details") }, { batch ->
            syncs++
            batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
        }, { failures++ }).run() as PushWakeDrain.Result.Pending
        assertEquals(15_100L, pending.readyAtMs)
        assertEquals(listOf(false), retry.completions)
        assertEquals(1, failures)
        assertEquals(0, syncs)

        val dropped = FakeQueue()
        assertEquals(PushWakeDrain.Result.Empty,
            PushWakeDrain(dropped, { 100 }, { PushWakeDrain.Inspection.DROP }, { batch ->
                syncs++
                batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
            }, { failures++ }).run())
        assertEquals(listOf(true), dropped.completions)
        assertEquals(0, syncs)
    }

    @Test fun onlyAllowedHintsReachTheBatchAndEachOutcomeControlsItsOwnLease() = runBlocking {
        val a = "12".repeat(32)
        val b = "34".repeat(32)
        val c = "56".repeat(32)
        val queue = BatchQueue(listOf(null, a, b, c))
        var failures = 0
        var batches = 0
        val result = PushWakeDrain(queue, { 100 }, { route ->
            when (route) {
                b -> PushWakeDrain.Inspection.DROP
                c -> PushWakeDrain.Inspection.RETRY
                else -> PushWakeDrain.Inspection.ALLOW
            }
        }, { batch ->
            batches++
            assertEquals(listOf(null, a), batch.items.map { it.route })
            val aResult = batch.items[1].result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW)
            assertFalse(batch.toString().contains(a))
            assertFalse(batch.items.toString().contains(a))
            assertFalse(aResult.toString().contains(a))
            var modified = false
            try { (batch.items as MutableList<PushWakeDrain.SyncItem>).clear(); modified = true }
            catch (_: UnsupportedOperationException) { /* The batch is an immutable snapshot. */ }
            assertFalse(modified)
            // Reversed completion order must not transfer success to recovery.
            listOf(aResult, batch.items[0].result(PushWakeDrain.SyncOutcome.RETRY))
        }, { failures++ }).run()
        assertTrue(result is PushWakeDrain.Result.Pending)
        assertEquals(listOf(b to true, c to false, null to false, a to true), queue.completions)
        assertEquals(1, batches)
        assertEquals(0, failures)
    }

    @Test fun recoveryItemCarriesTheExactLocalMarkerIdentityAndRevision() = runBlocking {
        val route = "12".repeat(32)
        val queue = BatchQueue(listOf(route, null))
        val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            assertEquals(route, batch.items[0].route)
            assertEquals(null, batch.items[0].recoverySignal)
            val marker = batch.items[1]
            assertEquals(null, marker.route)
            val signal = checkNotNull(marker.recoverySignal)
            assertEquals("cd".repeat(32), signal.incarnation)
            assertEquals(2L, signal.revision)
            assertFalse(signal.toString().contains(signal.incarnation))
            batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
        }, { error("unexpected diagnostics") }).run()
        assertEquals(PushWakeDrain.Result.Empty, result)
        assertEquals(listOf(route to true, null to true), queue.completions)
    }

    @Test fun malformedMarkerNeverReachesSyncOrCompletesItsLease() = runBlocking {
        val route = "12".repeat(32)
        val claims = listOf(
            PushWakeInbox.Claim(null, 1, 1, "ab".repeat(32)),
            PushWakeInbox.Claim(null, 1, 1, "ab".repeat(32), "bad"),
            PushWakeInbox.Claim(null, 1, 1, "ab".repeat(32), "0".repeat(64)),
            PushWakeInbox.Claim(null, 0, 1, "ab".repeat(32), "cd".repeat(32)),
            PushWakeInbox.Claim(route, 1, 1, "ab".repeat(32), "cd".repeat(32)),
        )
        for (claim in claims) {
            var taken = false
            val queue = object : PushWakeQueue {
                override fun claim(nowMs: Long): PushWakeInbox.ClaimResult =
                    if (taken) PushWakeInbox.ClaimResult.Empty else PushWakeInbox.ClaimResult.Ready(claim).also { taken = true }
                override fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion =
                    error("malformed claim must stay leased")
                override fun pending(nowMs: Long): PushWakeInbox.PendingResult = error("storage result must be unavailable")
            }
            val result = PushWakeDrain(queue, { 100 }, { error("must not inspect") },
                { error("must not sync") }, { error("unexpected diagnostics") }).run()
            assertEquals(PushWakeDrain.Result.Unavailable, result)
        }
    }

    @Test fun wrongOutcomeCountRetriesEveryLeaseInsteadOfGuessing() = runBlocking {
        val completed = PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        for (extra in listOf(false, true)) {
            val queue = FakeQueue()
            var failures = 0
            val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                if (extra) List(2) { batch.items.single().result(completed) } else emptyList()
            }, { failures++ }).run()
            assertTrue(result is PushWakeDrain.Result.Pending)
            assertEquals(listOf(false), queue.completions)
            assertEquals(1, failures)
        }
    }

    @Test fun duplicateOrForeignResultsRetryTheEntireBatch() = runBlocking {
        val a = "12".repeat(32)
        val b = "34".repeat(32)
        val done = PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        for (duplicate in listOf(false, true)) {
            val queue = BatchQueue(listOf(a, b))
            var failures = 0
            val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                val first = batch.items[0].result(done)
                val second = if (duplicate) first else PushWakeDrain.SyncItem(b).result(done)
                listOf(first, second)
            }, { failures++ }).run()
            assertTrue(result is PushWakeDrain.Result.Pending)
            assertEquals(listOf(a to false, b to false), queue.completions)
            assertEquals(1, failures)
        }
    }

    @Test fun exceptionReadingAResultRetriesAllLeasesWithoutPartialCompletion() = runBlocking {
        val a = "12".repeat(32)
        val b = "34".repeat(32)
        val queue = BatchQueue(listOf(a, b))
        var failures = 0
        val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            val first = batch.items[0].result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW)
            object : AbstractList<PushWakeDrain.SyncResult>() {
                override val size = 2
                override fun get(index: Int): PushWakeDrain.SyncResult =
                    if (index == 0) first else error("sensitive callback detail")
            }
        }, { failures++ }).run()
        assertTrue(result is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a to false, b to false), queue.completions)
        assertEquals(1, failures)
    }

    @Test fun aPreviousBatchResultCannotCompleteANewLeaseForTheSameRoute() = runBlocking {
        val queue = FakeQueue()
        var now = 100L
        var prior: PushWakeDrain.SyncResult? = null
        var failures = 0
        val drain = PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            val stale = prior
            if (stale != null) listOf(stale) else {
                prior = batch.items.single().result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW)
                listOf(batch.items.single().result(PushWakeDrain.SyncOutcome.RETRY))
            }
        }, { failures++ })
        val pending = drain.run() as PushWakeDrain.Result.Pending
        now = pending.readyAtMs
        assertTrue(drain.run() is PushWakeDrain.Result.Pending)
        assertEquals(listOf(false, false), queue.completions)
        assertEquals(1, failures)
    }

    @Test fun separateLeasesForTheSameRouteRemainDistinctItems() = runBlocking {
        val a = "12".repeat(32)
        val queue = BatchQueue(listOf(a, a))
        val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            assertEquals(listOf(a, a), batch.items.map { it.route })
            assertTrue(batch.items[0] !== batch.items[1])
            listOf(batch.items[1].result(PushWakeDrain.SyncOutcome.RETRY),
                batch.items[0].result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW))
        }, { error("unexpected failure") }).run()
        assertTrue(result is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a to true, a to false), queue.completions)
    }

    @Test fun aBatchNeverIncludesMoreThanSixteenHints() = runBlocking {
        val routes = (1..PushWakeDrain.MAX_BATCH + 1).map { "%064x".format(it) }
        val queue = BatchQueue(routes)
        val batches = mutableListOf<List<String?>>()
        val drain = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            batches += batch.items.map { it.route }
            batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
        }, { error("unexpected failure") })
        assertTrue(drain.run() is PushWakeDrain.Result.Pending)
        assertEquals(PushWakeDrain.Result.Empty, drain.run())
        assertEquals(listOf(routes.take(16), routes.takeLast(1)), batches)
        assertEquals(17, queue.completions.size)
    }

    @Test fun syncExceptionRetriesRatherThanAcknowledgingSignal() = runBlocking {
        val queue = FakeQueue()
        var failures = 0
        val result = PushWakeDrain(queue, { 100 }, { PushWakeDrain.Inspection.ALLOW }, {
            error("provider or event processing failed")
        }, { failures++ }).run()
        assertTrue(result is PushWakeDrain.Result.Pending)
        assertEquals(listOf(false), queue.completions)
        assertEquals(1, failures)
    }
}
