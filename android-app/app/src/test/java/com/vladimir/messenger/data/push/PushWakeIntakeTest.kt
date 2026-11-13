package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakeIntakeTest {
    private val route = "ab".repeat(32)
    private val data = mapOf("v" to "1", "route" to route)

    private class Fake {
        val events = mutableListOf<String>()
        var hint = PushWakeIntake.Hint.FOUND
        var enqueue = PushWakeInbox.EnqueueResult.QUEUED
        var recovery = PushWakeInbox.EnqueueResult.RECOVERY_QUEUED
        var persisted = false
        var schedulerAvailable = true
        var scheduleThrows: Exception? = null
        var lookupThrows: Exception? = null
        var enqueueThrows: Exception? = null
        var recoveryThrows: Exception? = null
        var failures = 0
        var storedRoute: String? = null

        fun intake() = PushWakeIntake(
            lookup = {
                events += "lookup"
                lookupThrows?.let { error -> throw error }
                hint
            },
            enqueueRoute = { route, _ ->
                events += "route"
                enqueueThrows?.let { error -> throw error }
                storedRoute = route
                if (enqueue == PushWakeInbox.EnqueueResult.QUEUED || enqueue == PushWakeInbox.EnqueueResult.RECOVERY_QUEUED) persisted = true
                enqueue
            },
            enqueueRecovery = {
                events += "recovery"
                recoveryThrows?.let { error -> throw error }
                if (recovery == PushWakeInbox.EnqueueResult.QUEUED || recovery == PushWakeInbox.EnqueueResult.RECOVERY_QUEUED) persisted = true
                recovery
            },
            pending = {
                events += "pending"
                if (persisted) PushWakeInbox.PendingResult.Pending(it) else PushWakeInbox.PendingResult.Empty
            },
            schedule = {
                events += "schedule"
                scheduleThrows?.let { error -> throw error }
                schedulerAvailable
            },
            recordFailure = { failures++ },
        )
    }

    @Test fun invalidAndUnknownRoutesCannotQueueOrSchedule() {
        val fake = Fake()
        val intake = fake.intake()
        listOf(emptyMap(), mapOf("v" to "2", "route" to route), mapOf("v" to "1", "route" to route, "text" to "private"),
            mapOf("v" to "1", "route" to route.uppercase())).forEach {
            assertEquals(PushWakeIntake.Result.IGNORED, intake.onData(it, 1000))
        }
        assertTrue(fake.events.isEmpty())
        fake.hint = PushWakeIntake.Hint.MISSING
        assertEquals(PushWakeIntake.Result.IGNORED, intake.onData(data, 1000))
        assertEquals(listOf("lookup"), fake.events)
        assertFalse(fake.persisted)
    }

    @Test fun matchingHintIsPersistedBeforeRequestingGenericWork() {
        val fake = Fake()
        assertEquals(PushWakeIntake.Result.QUEUED, fake.intake().onData(data, 1000))
        assertEquals(listOf("lookup", "route", "schedule"), fake.events)
        assertEquals(route, fake.storedRoute)
        assertTrue(fake.persisted)
        // QUEUED is not an FCM, worker, message-delivery or notification receipt.
    }

    @Test fun unavailableIndexOrRouteWritePersistsRouteFreeRecoveryBeforeScheduling() {
        val fake = Fake()
        val intake = fake.intake()
        fake.hint = PushWakeIntake.Hint.UNAVAILABLE
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onData(data, 1000))
        assertEquals(listOf("lookup", "recovery", "schedule"), fake.events)
        assertEquals(null, fake.storedRoute)
        assertTrue(fake.persisted)
        fake.events.clear()
        fake.persisted = false
        fake.hint = PushWakeIntake.Hint.FOUND
        fake.enqueue = PushWakeInbox.EnqueueResult.UNAVAILABLE
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onData(data, 1000))
        assertEquals(listOf("lookup", "route", "recovery", "schedule"), fake.events)
        assertTrue(fake.persisted)
    }

    @Test fun failedRecoveryNeverSchedulesAndRequiresIndependentFullScan() {
        val fake = Fake()
        val intake = fake.intake()
        fake.hint = PushWakeIntake.Hint.UNAVAILABLE
        fake.recovery = PushWakeInbox.EnqueueResult.UNAVAILABLE
        assertEquals(PushWakeIntake.Result.UNAVAILABLE, intake.onData(data, 1000))
        assertEquals(listOf("lookup", "recovery"), fake.events)
        assertFalse(fake.persisted)
        fake.events.clear()
        fake.hint = PushWakeIntake.Hint.FOUND
        fake.enqueue = PushWakeInbox.EnqueueResult.UNAVAILABLE
        assertEquals(PushWakeIntake.Result.UNAVAILABLE, intake.onData(data, 1001))
        assertEquals(listOf("lookup", "route", "recovery"), fake.events)
        assertFalse(fake.persisted)
        fake.events.clear()
        fake.hint = PushWakeIntake.Hint.UNAVAILABLE
        fake.recoveryThrows = IllegalStateException("do not expose")
        assertEquals(PushWakeIntake.Result.UNAVAILABLE, intake.onData(data, 1002))
        assertEquals(listOf("lookup", "recovery"), fake.events)
        assertEquals(1, fake.failures)
        assertFalse(fake.persisted)
    }

    @Test fun thrownLookupAndRouteErrorsAlsoFallBackWithoutExposingDetails() {
        val fake = Fake()
        val intake = fake.intake()
        fake.lookupThrows = IllegalStateException("secret route")
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onData(data, 1000))
        assertEquals(listOf("lookup", "recovery", "schedule"), fake.events)
        assertEquals(1, fake.failures)
        assertEquals(null, fake.storedRoute)
        fake.events.clear()
        fake.lookupThrows = null
        fake.enqueueThrows = IllegalStateException("secret route")
        fake.schedulerAvailable = false
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, intake.onData(data, 1001))
        assertEquals(listOf("lookup", "route", "recovery", "schedule"), fake.events)
        assertEquals(2, fake.failures)
        assertTrue(fake.persisted)
        fake.schedulerAvailable = true
        assertEquals(PushWakeIntake.Result.QUEUED, intake.resume(1002))
    }

    @Test fun deletedMessagesAndInboxOverflowRequestAFullRecovery() {
        val fake = Fake()
        val intake = fake.intake()
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onDeletedMessages(1000))
        assertEquals(listOf("recovery", "schedule"), fake.events)
        fake.events.clear()
        fake.enqueue = PushWakeInbox.EnqueueResult.RECOVERY_QUEUED
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onData(data, 1001))
        assertEquals(listOf("lookup", "route", "schedule"), fake.events)
    }

    @Test fun schedulerFailureLeavesDurableWorkAndStartupCanRedriveIt() {
        val fake = Fake()
        val intake = fake.intake()
        fake.schedulerAvailable = false
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, intake.onData(data, 1000))
        assertTrue(fake.persisted)
        fake.events.clear()
        fake.schedulerAvailable = true
        assertEquals(PushWakeIntake.Result.QUEUED, intake.resume(2000))
        assertEquals(listOf("pending", "schedule"), fake.events)
        fake.persisted = false
        fake.events.clear()
        assertEquals(PushWakeIntake.Result.IGNORED, intake.resume(2001))
        assertEquals(listOf("pending"), fake.events)
    }

    @Test fun thrownSchedulerErrorDoesNotMisreportOrRemoveTheSavedSignal() {
        val fake = Fake()
        val intake = fake.intake()
        fake.scheduleThrows = IllegalStateException("secret provider detail")
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, intake.onData(data, 1000))
        assertTrue(fake.persisted)
        assertEquals(1, fake.failures)
        fake.scheduleThrows = null
        assertEquals(PushWakeIntake.Result.QUEUED, intake.resume(1001))
        assertEquals(1, fake.failures)
    }

    @Test fun cancellationPropagatesAndLeavesPersistedWorkForRecovery() {
        val fake = Fake()
        val intake = fake.intake()
        fake.scheduleThrows = CancellationException("cancelled")
        assertTrue(runCatching { intake.onData(data, 1000) }.exceptionOrNull() is CancellationException)
        assertTrue(fake.persisted)
        assertEquals(0, fake.failures)
        fake.scheduleThrows = null
        assertEquals(PushWakeIntake.Result.QUEUED, intake.resume(1001))
        fake.lookupThrows = CancellationException("cancelled")
        fake.events.clear()
        assertTrue(runCatching { intake.onData(data, 1002) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("lookup"), fake.events)
        fake.lookupThrows = null
        fake.enqueueThrows = CancellationException("cancelled")
        fake.events.clear()
        assertTrue(runCatching { intake.onData(data, 1003) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("lookup", "route"), fake.events)
        fake.enqueueThrows = null
        fake.hint = PushWakeIntake.Hint.UNAVAILABLE
        fake.recoveryThrows = CancellationException("cancelled")
        fake.events.clear()
        assertTrue(runCatching { intake.onData(data, 1004) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("lookup", "recovery"), fake.events)
        assertEquals(0, fake.failures)
    }
}
