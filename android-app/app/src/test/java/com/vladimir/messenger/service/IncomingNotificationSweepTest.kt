package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.dao.PendingNotificationCandidate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationSweepTest {
    private class Fixture {
        val pending = sortedMapOf<Long, String>()
        val visited = mutableListOf<String>()
        val failed = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        var live = true
        var diagnostics = 0
        var onVisit: (String) -> Unit = {}
        var onDiagnostic: () -> Unit = {}
        fun sweep() = IncomingNotificationSweep(
            maxPendingRowId = { pending.keys.maxOrNull() },
            page = { after, through, limit ->
                pending.entries.filter { (after == null || it.key > after) && it.key <= through }.take(limit)
                    .map { PendingNotificationCandidate(it.key, it.value) }
            },
            process = { id ->
                visited += id
                onVisit(id)
                if (id in failed) error("synthetic post failure")
                if (id in blocked) IncomingNotificationProcessor.Result.BLOCKED else {
                    pending.entries.removeAll { it.value == id }
                    IncomingNotificationProcessor.Result.DONE
                }
            },
            current = { live },
            recordFailure = { diagnostics++; onDiagnostic() },
        )
    }

    @Test fun emptyQueueCompletesWithoutInventingWork() = runBlocking {
        val step = Fixture().sweep().run(IncomingNotificationSweep.Cursor(), 2)!!
        assertFalse(step.retry)
        assertEquals(IncomingNotificationSweep.Cursor(), step.cursor)
    }

    @Test fun boundedPassSurvivesNewArrivalsAndWrapsBackToFailedRows() = runBlocking {
        val f = Fixture()
        (1L..4L).forEach { f.pending[it] = "id-$it" }
        f.failed += "id-1"
        val sweep = f.sweep()
        val first = sweep.run(IncomingNotificationSweep.Cursor(), 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(2, 4, hadFailure = true), first.cursor)
        assertTrue(first.retry)
        (5L..20L).forEach { f.pending[it] = "id-$it" }
        val second = sweep.run(first.cursor, 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(4, 4, hadFailure = true), second.cursor)
        assertEquals(listOf("id-1", "id-2", "id-3", "id-4"), f.visited)
        val end = sweep.run(second.cursor, 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(), end.cursor)
        assertTrue(end.retry)
        f.failed.clear()
        val next = sweep.run(end.cursor, 2)!!
        assertEquals(listOf("id-1", "id-5"), f.visited.takeLast(2))
        assertEquals(IncomingNotificationSweep.Cursor(5, 20), next.cursor)
        assertEquals(1, f.diagnostics)
    }

    @Test fun failedItemsDoNotHideHealthyItemsInTheSamePage() = runBlocking {
        val f = Fixture()
        f.pending[1] = "bad"
        f.pending[2] = "good"
        f.failed += "bad"
        val step = f.sweep().run(IncomingNotificationSweep.Cursor(), 4)!!
        assertEquals(listOf("bad", "good"), f.visited)
        assertEquals(mapOf(1L to "bad"), f.pending)
        assertEquals(IncomingNotificationSweep.Cursor(), step.cursor)
        assertTrue(step.retry)
        assertEquals(1, f.diagnostics)
    }

    @Test fun systemBlockedRowsRemainForPeriodicRecoveryWithoutAnEagerRetryLoop() = runBlocking {
        val f = Fixture()
        f.pending[1] = "blocked-a"
        f.pending[2] = "good"
        f.pending[3] = "blocked-b"
        f.blocked += listOf("blocked-a", "blocked-b")
        val sweep = f.sweep()
        val first = sweep.run(IncomingNotificationSweep.Cursor(), 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(2, 3, hadBlocked = true), first.cursor)
        assertTrue(first.retry)
        val second = sweep.run(first.cursor, 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(), second.cursor)
        assertFalse(second.retry) // a periodic tick or permission callback starts a new pass
        assertEquals(mapOf(1L to "blocked-a", 3L to "blocked-b"), f.pending)
        f.blocked.clear()
        val restored = sweep.run(second.cursor, 8)!!
        assertFalse(restored.retry)
        assertTrue(f.pending.isEmpty())
    }

    @Test fun transientFailureAndNewArrivalsStillRetryWhenOtherRowsAreBlocked() = runBlocking {
        val f = Fixture()
        f.pending[1] = "blocked"
        f.pending[2] = "failing"
        f.blocked += "blocked"
        f.failed += "failing"
        val first = f.sweep().run(IncomingNotificationSweep.Cursor(), 1)!!
        assertEquals(IncomingNotificationSweep.Cursor(1, 2, hadBlocked = true), first.cursor)
        val second = f.sweep().run(first.cursor, 2)!!
        assertTrue(second.retry)
        assertEquals(IncomingNotificationSweep.Cursor(), second.cursor)
        f.failed.clear()
        f.pending.remove(2)
        f.pending[3] = "new"
        val isolated = f.sweep().run(IncomingNotificationSweep.Cursor(), 1)!!
        val end = f.sweep().run(isolated.cursor, 2)!!
        assertFalse(end.retry)
        assertEquals(mapOf(1L to "blocked"), f.pending)
    }

    @Test fun newWorkAfterCapturedHighWaterIsRetriedEvenWhenAnOlderRowIsBlocked() = runBlocking {
        val f = Fixture()
        f.pending[1] = "blocked"
        f.blocked += "blocked"
        f.onVisit = { f.pending[2] = "new" }
        val step = f.sweep().run(IncomingNotificationSweep.Cursor(), 4)!!
        assertTrue(step.retry)
        assertEquals(IncomingNotificationSweep.Cursor(), step.cursor)
        f.onVisit = {}
        f.sweep().run(step.cursor, 4)
        assertFalse("new" in f.pending.values)
        assertTrue("blocked" in f.pending.values)
    }

    @Test fun brokenDiagnosticsCannotHideOtherRowsOrTurnFailureIntoSuccess() = runBlocking {
        val f = Fixture()
        f.pending[1] = "bad"
        f.pending[2] = "good"
        f.failed += "bad"
        f.onDiagnostic = { error("broken diagnostics") }
        val step = f.sweep().run(IncomingNotificationSweep.Cursor(), 4)!!
        assertEquals(listOf("bad", "good"), f.visited)
        assertTrue(step.retry)
        assertEquals(mapOf(1L to "bad"), f.pending)
    }

    @Test fun identityChangeAbortsWithoutAdvancingCursor() = runBlocking {
        val f = Fixture()
        f.pending[1] = "first"
        f.pending[2] = "second"
        f.onVisit = { f.live = false }
        assertNull(f.sweep().run(IncomingNotificationSweep.Cursor(), 2))
        assertEquals(listOf("first"), f.visited)
        assertTrue("second" in f.pending.values)
        f.live = true
        f.onVisit = {}
        val retried = f.sweep().run(IncomingNotificationSweep.Cursor(), 2)!!
        assertFalse(retried.retry)
        assertTrue(f.pending.isEmpty())
    }

    @Test fun corruptCursorStartsARescanRatherThanSkippingTheQueue() = runBlocking {
        val f = Fixture()
        f.pending[1] = "one"
        val step = f.sweep().run(IncomingNotificationSweep.Cursor(after = 9, through = 2), 2)!!
        assertEquals(listOf("one"), f.visited)
        assertFalse(step.retry)
        assertEquals(IncomingNotificationSweep.Cursor(), step.cursor)
    }

    @Test fun malformedPageCannotAdvanceOrPostAnyRow() = runBlocking {
        var calls = 0
        val sweep = IncomingNotificationSweep(
            maxPendingRowId = { 5L },
            page = { _, _, _ -> listOf(PendingNotificationCandidate(3, "x"), PendingNotificationCandidate(2, "y")) },
            process = { calls++; IncomingNotificationProcessor.Result.DONE },
            current = { true }, recordFailure = { error("unexpected") },
        )
        val step = sweep.run(IncomingNotificationSweep.Cursor(), 2)!!
        assertTrue(step.retry)
        assertEquals(IncomingNotificationSweep.Cursor(), step.cursor)
        assertEquals(0, calls)
    }

    @Test fun cancellationPropagatesAndDoesNotProcessLaterRows() = runBlocking {
        val f = Fixture()
        f.pending[1] = "first"
        f.pending[2] = "second"
        f.onVisit = { throw CancellationException("stop") }
        try {
            f.sweep().run(IncomingNotificationSweep.Cursor(), 2)
            error("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: caller must leave the old checkpoint unchanged.
        }
        assertEquals(listOf("first"), f.visited)
        assertEquals(0, f.diagnostics)
        assertEquals(2, f.pending.size)
    }

    @Test fun unusualRowidsAreTraversedAndInvalidLimitsAreRejected() = runBlocking {
        val f = Fixture()
        f.pending[Long.MIN_VALUE] = "minimum"
        f.pending[-1] = "negative"
        f.pending[0] = "zero"
        f.pending[1] = "positive"
        val first = f.sweep().run(IncomingNotificationSweep.Cursor(), 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(-1, 1), first.cursor)
        val second = f.sweep().run(first.cursor, 3)!!
        assertFalse(second.retry)
        assertEquals(listOf("minimum", "negative", "zero", "positive"), f.visited)
        assertTrue(runCatching { f.sweep().run(IncomingNotificationSweep.Cursor(), 0) }.isFailure)
        assertTrue(runCatching { f.sweep().run(IncomingNotificationSweep.Cursor(), 129) }.isFailure)
    }
}
