package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CancellationException as CoroutineCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRuntimeCoordinatorTest {
    private val token = "fcm-test-token-abcdefghijklmnop"
    private val rotatedToken = "fcm-rotated-token-abcdefghijklmn"

    private class FakeJournal(initial: FcmTokenJournal.State = FcmTokenJournal.State.Missing) :
        PushRuntimeCoordinator.TokenJournal {
        var state = initial
        var records = 0
        var forgets = 0
        var throws = false
        var lastToken: String? = null

        override fun record(token: String, nowMs: Long): FcmTokenJournal.State {
            if (throws) throw IllegalStateException("journal unavailable")
            records++
            lastToken = token
            return state
        }

        override fun forget(): Boolean {
            forgets++
            return true
        }
    }

    private class Fake(open: Boolean = true, journal: FakeJournal = FakeJournal()) {
        val tokenJournal = journal
        var enabled = open
        var registered = mutableListOf<String>()
        var registerSucceeds = true
        var registerThrows = false
        var checks = mutableListOf<Long>()
        var checkAccepted = true
        var checkThrows = false
        var failures = 0

        val coordinator = PushRuntimeCoordinator(
            gate = object : PushRuntimeCoordinator.Gate {
                override fun enabled(): Boolean = enabled
            },
            journal = tokenJournal,
            register = { value ->
                if (registerThrows) throw IllegalStateException("network")
                registered += value
                registerSucceeds
            },
            enqueueCheck = { now ->
                if (checkThrows) throw IllegalStateException("work unavailable")
                checks += now
                checkAccepted
            },
            recordFailure = { failures++ },
        )
    }

    @Test fun closedGateStoresNothingAndSchedulesNothing() = runBlocking {
        val fake = Fake(open = false)
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Disabled, fake.coordinator.onToken(token, 1))
        assertEquals(0, fake.tokenJournal.records)
        assertTrue(fake.registered.isEmpty())
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Disabled,
            fake.coordinator.onSignal(mapOf("v" to "1"), false, 1),
        )
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Disabled,
            fake.coordinator.onDeletedMessages(1),
        )
        assertTrue(fake.checks.isEmpty())
    }

    @Test fun aFreshTokenIsRegisteredOnlyThroughThePinnedRoute() = runBlocking {
        val fake = Fake(journal = FakeJournal(FcmTokenJournal.State.Missing))
        val outcome = fake.coordinator.onToken(token, 123)
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Registered(false), outcome)
        assertEquals(listOf(token), fake.registered)
        assertEquals(1, fake.tokenJournal.records)
    }

    @Test fun anUnchangedTokenMakesNoNetworkRequest() = runBlocking {
        val fake = Fake(journal = FakeJournal(FcmTokenJournal.State.Unchanged))
        assertEquals(PushRuntimeCoordinator.TokenOutcome.AlreadyCurrent, fake.coordinator.onToken(token, 123))
        assertTrue(fake.registered.isEmpty())
    }

    @Test fun aRotatedTokenMustBeReRegisteredAndFailureIsNotSuccess() = runBlocking {
        val rotated = Fake(journal = FakeJournal(FcmTokenJournal.State.Rotated))
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Registered(true), rotated.coordinator.onToken(rotatedToken, 1))
        assertEquals(listOf(rotatedToken), rotated.registered)

        val refused = Fake(journal = FakeJournal(FcmTokenJournal.State.Rotated))
        refused.registerSucceeds = false
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Failed, refused.coordinator.onToken(rotatedToken, 1))
        // The journal must not keep claiming a token that was never registered.
        assertEquals(1, refused.tokenJournal.forgets)
    }

    @Test fun corruptJournalOrRegistrationErrorFailsClosed() = runBlocking {
        val corrupt = Fake(journal = FakeJournal(FcmTokenJournal.State.Unavailable))
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Failed, corrupt.coordinator.onToken(token, 1))
        assertTrue(corrupt.registered.isEmpty())

        val thrown = Fake(journal = FakeJournal())
        thrown.tokenJournal.throws = true
        assertEquals(PushRuntimeCoordinator.TokenOutcome.Failed, thrown.coordinator.onToken(token, 1))
        assertEquals(1, thrown.failures)
    }

    @Test fun providerSignalMayOnlyRequestABoundedCheck() {
        val fake = Fake()
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.CheckRequested,
            fake.coordinator.onSignal(mapOf("v" to "1", "route" to "ab".repeat(32)), false, 99),
        )
        assertEquals(listOf(99L), fake.checks)
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Rejected,
            fake.coordinator.onSignal(mapOf("v" to "1", "text" to "hi"), false, 100),
        )
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Rejected,
            fake.coordinator.onSignal(
                mapOf("v" to "1", "route" to "ab".repeat(32)),
                hasNotificationBlock = true,
                nowMs = 101,
            ),
        )
        // No route: the deployed service always names one, so this is not our
        // signal and must not cost a bounded scan.
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Rejected,
            fake.coordinator.onSignal(mapOf("v" to "1"), false, 102),
        )
        assertEquals(
            PushRuntimeCoordinator.SignalOutcome.Ignored,
            fake.coordinator.onSignal(mapOf("v" to "2", "route" to "ab".repeat(32)), false, 103),
        )
        assertEquals(listOf(99L), fake.checks)
    }

    @Test fun gapAndCheckFailuresAreReportedRatherThanSwallowed() {
        val fake = Fake()
        assertEquals(PushRuntimeCoordinator.SignalOutcome.CheckRequested, fake.coordinator.onDeletedMessages(7))
        fake.checkAccepted = false
        assertEquals(PushRuntimeCoordinator.SignalOutcome.Failed, fake.coordinator.onDeletedMessages(8))
        fake.checkThrows = true
        assertEquals(PushRuntimeCoordinator.SignalOutcome.Failed, fake.coordinator.onDeletedMessages(9))
        assertTrue(fake.failures >= 1)
    }

    @Test fun cancellationIsNeverConvertedIntoAnOutcome() {
        val fake = Fake(journal = FakeJournal())
        fake.tokenJournal.throws = false
        val coordinator = PushRuntimeCoordinator(
            gate = object : PushRuntimeCoordinator.Gate {
                override fun enabled() = true
            },
            journal = fake.tokenJournal,
            register = { throw CoroutineCancellation("cancelled") },
            enqueueCheck = { true },
        )
        var cancelled = false
        try {
            runBlocking { coordinator.onToken(token, 1) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        // The fake journal recorded the token, but no outcome was produced:
        // cancellation propagates instead of becoming Registered/Failed.
        assertEquals(0, fake.failures)
    }
}
