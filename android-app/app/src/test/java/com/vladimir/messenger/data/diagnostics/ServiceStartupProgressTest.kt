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

    @Test fun staleQueueTicketCannotAdvanceTheReplacementTrace() {
        var now = 1L
        val progress = ServiceStartupProgress { now }
        progress.start()
        progress.coreRequested()
        val old = progress.currentToken()
        progress.stopped()
        now = 5L
        progress.start()
        progress.coreRequested()
        val current = progress.currentToken()
        assertTrue(old !== current)
        assertNull(progress.lifecycleQueued(old))
        assertEquals(ServiceStartupProgress.Phase.CORE_REQUESTED, progress.snapshot().phase)
        assertTrue(progress.lifecycleQueued(current) === current)
        progress.lifecycleEntered(old)
        assertEquals(ServiceStartupProgress.Phase.WAITING_LIFECYCLE, progress.snapshot().phase)
        progress.lifecycleEntered(current)
        assertEquals(ServiceStartupProgress.Phase.INITIALIZING_ENGINE, progress.snapshot().phase)
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

    @Test fun engineStagesMeasureLiveAndAccumulateRepeatedStepsWithoutInventingUnobservedOnes() {
        var now = 0L
        val progress = ServiceStartupProgress { now }
        progress.start(); progress.coreRequested()
        val ticket = progress.lifecycleQueued()
        now = 10; progress.lifecycleEntered(ticket)
        now = 20; progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.CLEANING_PREVIOUS)
        now = 50; progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.CHECKING_EXISTING)
        now = 70; progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.CREATING_ENGINE)
        now = 370
        val live = progress.snapshot()
        assertEquals(ServiceStartupProgress.EnginePhase.CREATING_ENGINE, live.enginePhase)
        assertEquals(30L, live.enginePhaseMs[ServiceStartupProgress.EnginePhase.CHECKING_EXISTING])
        assertEquals(30L, live.enginePhaseMs[ServiceStartupProgress.EnginePhase.CLEANING_PREVIOUS])
        assertEquals(300L, live.enginePhaseMs[ServiceStartupProgress.EnginePhase.CREATING_ENGINE])
        assertNull(live.enginePhaseMs[ServiceStartupProgress.EnginePhase.STARTING_ENGINE])
        val rendered = ServiceStartupReport.render(live)
        assertTrue(rendered.contains("engine_step=creating_engine"))
        assertTrue(rendered.contains("creating_engine:300"))
        assertFalse(rendered.contains("starting_engine:"))
        assertTrue(rendered.contains("не доказывают успех"))
    }

    @Test fun returnedEngineStopsCountingButIsNotMarkedSuccessful() {
        var now = 1L
        val progress = ServiceStartupProgress { now }
        progress.start(); progress.coreRequested()
        val ticket = progress.lifecycleQueued(); progress.lifecycleEntered(ticket)
        now = 5; progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.STARTING_ENGINE)
        now = 25; progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.ABORTING)
        now = 75; progress.engineReturned(ticket)
        now = 1000
        val result = progress.snapshot()
        assertEquals(ServiceStartupProgress.EnginePhase.RETURNED, result.enginePhase)
        assertEquals(20L, result.enginePhaseMs[ServiceStartupProgress.EnginePhase.STARTING_ENGINE])
        assertEquals(50L, result.enginePhaseMs[ServiceStartupProgress.EnginePhase.ABORTING])
        progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.READING_PUBLIC_KEY)
        assertEquals(result.enginePhase, progress.snapshot().enginePhase)
        assertTrue(ServiceStartupReport.render(result).contains("engine_step=returned"))
        assertFalse(ServiceStartupReport.render(result).contains("engine_step_ms=returned:"))
    }

    @Test fun obsoleteAndUnownedNativeStagesCannotChangeReplacementOrFrozenTrace() {
        var now = 0L
        val progress = ServiceStartupProgress { now }
        progress.start(); progress.coreRequested()
        val stale = progress.lifecycleQueued(); progress.lifecycleEntered(stale)
        now = 10; progress.stopped()
        now = 20; progress.start(); progress.coreRequested()
        val current = progress.lifecycleQueued(); progress.lifecycleEntered(current)
        now = 40
        progress.engineStage(stale, ServiceStartupProgress.EnginePhase.STARTING_ENGINE)
        progress.engineStage(null, ServiceStartupProgress.EnginePhase.STARTING_ENGINE)
        progress.engineReturned(stale)
        assertEquals(ServiceStartupProgress.EnginePhase.CHECKING_EXISTING, progress.snapshot().enginePhase)
        assertEquals(20L, progress.snapshot().enginePhaseMs[ServiceStartupProgress.EnginePhase.CHECKING_EXISTING])
        progress.finished()
        now = 100
        progress.engineStage(current, ServiceStartupProgress.EnginePhase.STARTING_ENGINE)
        progress.engineReturned(current)
        assertEquals(ServiceStartupProgress.EnginePhase.CHECKING_EXISTING, progress.snapshot().enginePhase)
        assertEquals(20L, progress.snapshot().enginePhaseMs[ServiceStartupProgress.EnginePhase.CHECKING_EXISTING])
    }

    @Test fun noNativeStepIsReportedBeforeLifecycleEntry() {
        val progress = ServiceStartupProgress { 0L }
        assertTrue(ServiceStartupReport.render(progress.snapshot()).contains("engine_step=not_started · engine_step_ms=none"))
        progress.start(); progress.coreRequested()
        val ticket = progress.lifecycleQueued()
        progress.engineStage(ticket, ServiceStartupProgress.EnginePhase.CREATING_ENGINE)
        assertEquals(ServiceStartupProgress.EnginePhase.NOT_STARTED, progress.snapshot().enginePhase)
    }
}
