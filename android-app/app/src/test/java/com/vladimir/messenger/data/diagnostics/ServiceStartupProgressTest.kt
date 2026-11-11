package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartupProgressTest {
    @Test fun reportsLiveStageAndFreezesMeasuredDurationsOnFinish() {
        var now = 0L
        val progress = ServiceStartupProgress { now }
        assertNull(progress.snapshot().totalMs)
        progress.start()
        now = 20; progress.preparingKeys()
        now = 70; progress.coreRequested()
        now = 100; val ticket = progress.lifecycleQueued()
        now = 120
        val waiting = progress.snapshot()
        assertEquals(ServiceStartupProgress.Phase.WAITING_LIFECYCLE, waiting.phase)
        assertEquals(120L, waiting.totalMs)
        assertEquals(50L, waiting.phaseMs[ServiceStartupProgress.Phase.PREPARING_KEYS])
        assertEquals(20L, waiting.phaseMs[ServiceStartupProgress.Phase.WAITING_LIFECYCLE])
        now = 170; progress.lifecycleEntered(ticket)
        now = 400; progress.finished()
        now = 9_000
        val finished = progress.snapshot()
        assertEquals(ServiceStartupProgress.Phase.FINISHED, finished.phase)
        assertEquals(400L, finished.totalMs)
        assertEquals(70L, finished.phaseMs[ServiceStartupProgress.Phase.WAITING_LIFECYCLE])
        assertEquals(230L, finished.phaseMs[ServiceStartupProgress.Phase.INITIALIZING_ENGINE])
        progress.stopped()
        assertEquals(ServiceStartupProgress.Phase.STOPPED, progress.snapshot().phase)
        assertEquals(400L, progress.snapshot().totalMs)
    }

    @Test fun stoppedStartupIsNotCountedAsCompletedAndLateTaskCannotAffectANewService() {
        var now = 100L
        val progress = ServiceStartupProgress { now }
        progress.start()
        progress.coreRequested()
        val old = progress.lifecycleQueued()
        now = 300; progress.stopped()
        assertEquals(ServiceStartupProgress.Phase.STOPPED, progress.snapshot().phase)
        assertEquals(200L, progress.snapshot().totalMs)
        now = 500; progress.start()
        progress.coreRequested()
        val current = progress.lifecycleQueued()
        assertTrue(old !== current)
        progress.lifecycleEntered(old)
        assertEquals(ServiceStartupProgress.Phase.WAITING_LIFECYCLE, progress.snapshot().phase)
        progress.lifecycleEntered(current)
        assertEquals(ServiceStartupProgress.Phase.INITIALIZING_ENGINE, progress.snapshot().phase)
        assertFalse(progress.snapshot().phase == ServiceStartupProgress.Phase.FINISHED)
    }

    @Test fun nonServiceOrOutOfOrderNativeCallDoesNotHijackTheTrace() {
        var now = 1L
        val progress = ServiceStartupProgress { now }
        assertNull(progress.lifecycleQueued())
        progress.start()
        progress.preparingKeys()
        assertNull(progress.lifecycleQueued())
        progress.lifecycleEntered(null)
        now += 300_000
        assertEquals(ServiceStartupProgress.Phase.PREPARING_KEYS, progress.snapshot().phase)
        assertEquals(300_000L, progress.snapshot().totalMs)
    }
}
