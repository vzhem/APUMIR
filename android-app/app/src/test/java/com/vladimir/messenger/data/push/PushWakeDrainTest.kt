package com.vladimir.messenger.data.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests production drain logic with an in-memory queue; not an Android/FCM test. */
class PushWakeDrainTest {
    private class FakeQueue : PushWakeQueue {
        private val item = PushWakeInbox.Claim("12".repeat(32), 1L, 1L)
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

    @Test fun startedButUnfinishedWindowRetriesAndOnlyACompletedWindowConsumes() = runBlocking {
        val queue = FakeQueue()
        var now = 100L
        var attempts = 0
        var outcome = PushWakeDrain.SyncOutcome.RETRY
        val drain = PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, {
            attempts++
            outcome
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
        val pending = PushWakeDrain(retry, { 100 }, { error("do not log sensitive details") }, {
            syncs++
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { failures++ }).run() as PushWakeDrain.Result.Pending
        assertEquals(15_100L, pending.readyAtMs)
        assertEquals(listOf(false), retry.completions)
        assertEquals(1, failures)
        assertEquals(0, syncs)

        val dropped = FakeQueue()
        assertEquals(PushWakeDrain.Result.Empty,
            PushWakeDrain(dropped, { 100 }, { PushWakeDrain.Inspection.DROP }, {
                syncs++
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { failures++ }).run())
        assertEquals(listOf(true), dropped.completions)
        assertEquals(0, syncs)
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
