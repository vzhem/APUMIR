package com.vladimir.messenger.service

import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreStatusTest {
    @Test fun limitedReadinessDoesNotCountAsAnEngineStartAndStopClearsTheHint() {
        val tracker = OperationalDiagnostics.tracker
        val before = tracker.snapshot().counters[OperationalCounter.CORE_READY] ?: 0L
        tracker.coreStarting()
        CoreStatus.markStarting()
        CoreStatus.markReady(engineStarted = false)
        assertTrue(CoreStatus.ready.value)
        assertFalse(CoreStatus.engineStarted.value)
        assertEquals(before, tracker.snapshot().counters[OperationalCounter.CORE_READY] ?: 0L)

        tracker.coreStarting()
        CoreStatus.markStarting()
        CoreStatus.markReady(engineStarted = true)
        assertTrue(CoreStatus.ready.value)
        assertTrue(CoreStatus.engineStarted.value)
        assertEquals(before + 1, tracker.snapshot().counters[OperationalCounter.CORE_READY])
        CoreStatus.markStopped()
        assertFalse(CoreStatus.engineStarted.value)
        // A stopped service must not re-open the splash after the UI has continued.
        assertTrue(CoreStatus.ready.value)
    }
}
