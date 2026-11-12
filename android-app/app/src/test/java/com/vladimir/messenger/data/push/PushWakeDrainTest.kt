package com.vladimir.messenger.data.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
            PushWakeInbox.Claim(route, index.toLong() + 1, index.toLong() + 1, "ab".repeat(32))
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
