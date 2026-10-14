package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RuntimeHealthTest {
    @Test fun tracksSuccessFailureCancellationAndInFlightAge() {
        var now = 100L
        val tracker = OperationHealthTracker { now }
        val ok = tracker.begin(DiagnosticOperation.CORE_EVENTS)
        now = 125
        tracker.finish(ok, DiagnosticOutcome.OK)
        val fail = tracker.begin(DiagnosticOperation.CORE_EVENTS)
        now = 165
        tracker.finish(fail, DiagnosticOutcome.FAILED)
        val cancel = tracker.begin(DiagnosticOperation.CORE_EVENTS)
        now = 170
        tracker.finish(cancel, DiagnosticOutcome.CANCELLED)
        tracker.begin(DiagnosticOperation.CORE_EVENTS)
        now = 200
        val state = tracker.snapshot().single()
        assertEquals(3L, state.completed)
        assertEquals(1L, state.failed)
        assertEquals(1L, state.cancelled)
        assertEquals(5L, state.lastMs)
        assertEquals(40L, state.maxMs)
        assertEquals(1, state.inFlight)
        assertEquals(30L, state.oldestInFlightMs)
        assertEquals(30L, state.lastFinishedAgoMs)
    }

    @Test fun doubleFinishAndForeignTicketsCannotFinishAnotherOperation() {
        val tracker = OperationHealthTracker { 0L }
        val other = OperationHealthTracker { 0L }
        val own = tracker.begin(DiagnosticOperation.APK_PUMP)
        tracker.finish(other.begin(DiagnosticOperation.APK_PUMP), DiagnosticOutcome.FAILED)
        assertEquals(1, tracker.snapshot().single().inFlight)
        tracker.finish(own, DiagnosticOutcome.OK)
        tracker.finish(own, DiagnosticOutcome.FAILED)
        assertEquals(1L, tracker.snapshot().single().completed)
        assertEquals(0L, tracker.snapshot().single().failed)
    }

    @Test fun activeTimingsAreBoundedAndRecoveredWhenFinished() {
        val tracker = OperationHealthTracker { 0L }
        val tickets = (1..32).map { tracker.begin(DiagnosticOperation.FILE_PUMP) }
        assertTrue(tickets.all { it != null })
        assertNull(tracker.begin(DiagnosticOperation.FILE_PUMP))
        assertEquals(1L, tracker.dropped)
        tickets.forEach { tracker.finish(it, DiagnosticOutcome.OK) }
        assertNotNull(tracker.begin(DiagnosticOperation.FILE_PUMP))
        assertEquals(32L, tracker.snapshot().single().completed)
    }

    @Test fun durationsCannotBecomeNegative() {
        var now = 100L
        val tracker = OperationHealthTracker { now }
        val ticket = tracker.begin(DiagnosticOperation.FILE_PUMP)
        now = 50
        tracker.finish(ticket, DiagnosticOutcome.OK)
        assertEquals(0L, tracker.snapshot().single().lastMs)
        now = 0
        assertEquals(0L, tracker.snapshot().single().lastFinishedAgoMs)
    }

    @Test fun backgroundCreatesNoProbeAndOnlyOneCanBeOutstanding() {
        val tracker = UiResponsivenessTracker()
        assertNull(tracker.begin(0))
        tracker.visible(true)
        assertNotNull(tracker.begin(0))
        repeat(100) { assertNull(tracker.begin(1000)) }
        assertEquals(0L, tracker.snapshot(1000).samples)
        assertEquals(1000L, tracker.snapshot(1000).pendingAgeMs)
    }

    @Test fun overdueIsCountedOnceAndCompletionIsNotDoubleCounted() {
        val tracker = UiResponsivenessTracker()
        tracker.visible(true)
        val id = tracker.begin(100)!!
        assertNull(tracker.checkOverdue(1599))
        assertEquals(1500L, tracker.checkOverdue(1600))
        assertNull(tracker.checkOverdue(5000))
        tracker.complete(id, 5100)
        tracker.complete(id, 6100)
        val state = tracker.snapshot(6200)
        assertEquals(1L, state.samples)
        assertEquals(1L, state.lateCompleted)
        assertEquals(1L, state.overdueProbes)
        assertEquals(5000L, state.maxCompletedLagMs)
        assertNull(state.pendingAgeMs)
    }

    @Test fun screenOffOrStopDiscardsOldProbeWithoutCallingItAnAnr() {
        val tracker = UiResponsivenessTracker()
        tracker.visible(true)
        val stale = tracker.begin(100)!!
        tracker.visible(false)
        assertNull(tracker.checkOverdue(100_000))
        tracker.complete(stale, 100_000)
        tracker.visible(true)
        val fresh = tracker.begin(100_000)!!
        tracker.complete(stale, 100_001)
        assertEquals(0L, tracker.snapshot(100_001).samples)
        tracker.complete(fresh, 100_002)
        assertEquals(1L, tracker.snapshot(100_002).samples)
        assertEquals(2L, tracker.snapshot(100_002).maxCompletedLagMs)
        assertEquals(0L, tracker.snapshot(100_002).overdueProbes)
    }

    @Test fun repeatedVisibleDoesNotDiscardARealStall() {
        val tracker = UiResponsivenessTracker()
        tracker.visible(true)
        tracker.begin(10)
        tracker.visible(true)
        assertEquals(1500L, tracker.checkOverdue(1510))
    }

    @Test fun reportExportsOnlyEnumsCountsAndAtMostThreeExitReasons() {
        val report = RuntimeHealthSnapshot(
            operations = listOf(OperationHealth(DiagnosticOperation.KEY_MODE_PROBE, 0, 0, 0, 0, 0, 1, 12_000, null)),
            exitHistory = List(6) { PreviousProcessExit(ProcessExitReason.ANR, 1000, 100) },
            exitStatus = ExitHistoryStatus.READY,
        ).render()
        assertTrue(report.contains("op=key_mode_probe"))
        assertTrue(report.contains("oldest_in_flight_ms=12000"))
        assertEquals(3, Regex("reason=anr").findAll(report).count())
        assertTrue(report.contains("не является доказательством ANR"))
        assertTrue(report.contains("не подтверждает доставку"))
        assertFalse(report.contains("reason=6"))
    }
}
