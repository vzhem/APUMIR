package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationStartupProgressTest {
    @Test fun includesCleanupBeforeServiceCreationAndFreezesOnFinish() {
        var now = 100L
        val progress = ApplicationStartupProgress { now }
        assertNull(progress.snapshot().totalMs)
        progress.start()
        now = 120; progress.advance(ApplicationStartupProgress.Phase.PREPARING)
        now = 140; progress.advance(ApplicationStartupProgress.Phase.RESTORING)
        now = 200; progress.advance(ApplicationStartupProgress.Phase.IDENTITY_HISTORY)
        now = 240; progress.advance(ApplicationStartupProgress.Phase.OBSOLETE_CLEANUP)
        now = 300_240
        val whileBlocked = progress.snapshot()
        assertEquals(ApplicationStartupProgress.Phase.OBSOLETE_CLEANUP, whileBlocked.phase)
        assertEquals(300_140L, whileBlocked.totalMs)
        assertEquals(300_000L, whileBlocked.phaseMs[ApplicationStartupProgress.Phase.OBSOLETE_CLEANUP])
        now = 300_300; progress.advance(ApplicationStartupProgress.Phase.AFTER_CLEANUP)
        now = 300_500; progress.finish()
        now = 900_000
        val finished = progress.snapshot()
        assertEquals(ApplicationStartupProgress.Phase.FINISHED, finished.phase)
        assertEquals(300_400L, finished.totalMs)
        assertEquals(20L, finished.phaseMs[ApplicationStartupProgress.Phase.INJECTION])
        assertEquals(60L, finished.phaseMs[ApplicationStartupProgress.Phase.RESTORING])
        assertEquals(300_060L, finished.phaseMs[ApplicationStartupProgress.Phase.OBSOLETE_CLEANUP])
        assertEquals(200L, finished.phaseMs[ApplicationStartupProgress.Phase.AFTER_CLEANUP])
    }

    @Test fun repeatedBackwardAndPrematureFinishStageDoNotRelabelTime() {
        var now = 0L
        val progress = ApplicationStartupProgress { now }
        progress.start()
        now = 10; progress.advance(ApplicationStartupProgress.Phase.RESTORING)
        now = 20; progress.advance(ApplicationStartupProgress.Phase.PREPARING)
        progress.advance(ApplicationStartupProgress.Phase.RESTORING)
        progress.advance(ApplicationStartupProgress.Phase.FINISHED)
        now = 40
        assertEquals(ApplicationStartupProgress.Phase.RESTORING, progress.snapshot().phase)
        assertEquals(30L, progress.snapshot().phaseMs[ApplicationStartupProgress.Phase.RESTORING])
        progress.finish()
        progress.finish()
        now = 70
        assertEquals(40L, progress.snapshot().totalMs)
    }
}
