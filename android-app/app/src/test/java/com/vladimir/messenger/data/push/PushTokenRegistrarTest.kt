package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PushTokenRegistrarTest {
    private val peerA = "pk_" + "2".repeat(32)
    private val peerB = "pk_" + "3".repeat(32)

    private fun peer(value: String) = PushTargets.Peer("pk_" + "1".repeat(32), value, 1)

    private class Harness(
        var enabledValue: Boolean = true,
        var provider: PushFirebaseSignals.Outcome = PushFirebaseSignals.Outcome.Token("fcm-test-token-abcdefghijklmnop"),
        var journal: FcmTokenJournal.State = FcmTokenJournal.State.Missing,
        var targets: Pair<PushTargets.Outcome, List<PushTargets.Peer>> = PushTargets.Outcome.COMPLETED to emptyList(),
        var registerOutcome: (String) -> PushRegistrationRunner.Outcome = { PushRegistrationRunner.Outcome.Registered },
        var revokeOutcome: (String) -> Boolean = { true },
        var calls: MutableList<String> = mutableListOf(),
        var failures: Int = 0,
    ) {
        fun build() = PushTokenRegistrar(
            enabled = { enabledValue },
            token = { provider },
            coordinator = { register, record ->
                PushRuntimeCoordinator(
                    gate = PushRuntimeCoordinator.Gate { enabledValue },
                    journal = object : PushRuntimeCoordinator.TokenJournal {
                        override fun record(token: String, nowMs: Long) = journal.also { calls += "journal" }
                        override fun forget(): Boolean = true
                    },
                    register = register,
                    enqueueCheck = { record() },
                    recordFailure = { failures++ },
                )
            },
            peers = { targets },
            register = { peerId, token ->
                calls += "register:${peerId.takeLast(4)}:${token.length}"
                registerOutcome(peerId)
            },
            revoke = { peerId -> calls += "revoke"; revokeOutcome(peerId) },
            forgetToken = { calls += "forget"; true },
            nowMs = { 1_700_000_000_000 },
            recordFailure = { failures++ },
        )
    }

    @Test fun disabledOrUnconfiguredBuildsTouchNothing() = runBlocking {
        val harness = Harness(enabledValue = false)
        assertEquals(PushTokenRegistrar.Outcome.Disabled, harness.build().run())
        assertEquals(emptyList<String>(), harness.calls)

        val noServices = Harness(provider = PushFirebaseSignals.Outcome.NoPlayServices)
        assertEquals(PushTokenRegistrar.Outcome.NoPlayServices, noServices.build().run())
        assertEquals(emptyList<String>(), noServices.calls)

        val notConfigured = Harness(provider = PushFirebaseSignals.Outcome.NotConfigured)
        assertEquals(PushTokenRegistrar.Outcome.NotConfigured, notConfigured.build().run())
        assertEquals(emptyList<String>(), notConfigured.calls)
    }

    @Test fun anUnchangedTokenMakesNoNetworkCall() = runBlocking {
        val harness = Harness(journal = FcmTokenJournal.State.Unchanged, targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)))
        assertEquals(PushTokenRegistrar.Outcome.AlreadyCurrent, harness.build().run())
        assertEquals(listOf("journal"), harness.calls)
    }

    @Test fun everyLivePeerIsRegisteredWithTheSameToken() = runBlocking {
        val harness = Harness(targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)))
        assertEquals(PushTokenRegistrar.Outcome.Registered, harness.build().run())
        assertEquals(3, harness.calls.size)
        assertEquals("journal", harness.calls.first())
        assertEquals(2, harness.calls.count { it.startsWith("register:") })
    }

    @Test fun anUnreadableIndexOrAnEmptyOneIsNeverReportedAsRegistered() = runBlocking {
        val partial = Harness(targets = PushTargets.Outcome.RETRY to listOf(peer(peerA)))
        assertEquals(PushTokenRegistrar.Outcome.Failed, partial.build().run())
        assertEquals(0, partial.calls.count { it.startsWith("register:") })

        val none = Harness(targets = PushTargets.Outcome.COMPLETED to emptyList())
        assertEquals(PushTokenRegistrar.Outcome.Failed, none.build().run())
    }

    @Test fun oneRefusalFailsTheAttemptInsteadOfPretendingSuccess() = runBlocking {
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            registerOutcome = { if (it == peerB) PushRegistrationRunner.Outcome.Failed else PushRegistrationRunner.Outcome.Registered },
        )
        assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run())
        // The coordinator turns a failed registration into Failed and forgets the token.
        assertEquals(1, harness.calls.count { it == "journal" })
    }

    @Test fun onlyUnreadyPeersStillFailsBecauseNothingWasRegistered() = runBlocking {
        val harness = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA)),
            registerOutcome = { PushRegistrationRunner.Outcome.NotReady },
        )
        assertEquals(PushTokenRegistrar.Outcome.Failed, harness.build().run())
    }

    @Test fun forgetRevokesEveryLiveRoute() = runBlocking {
        val harness = Harness(targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)))
        assertEquals(true, harness.build().forget())
        assertEquals(2, harness.calls.count { it == "revoke" })
        assertEquals(listOf("forget", "revoke", "revoke"), harness.calls)

        val oneFailed = Harness(
            targets = PushTargets.Outcome.COMPLETED to listOf(peer(peerA), peer(peerB)),
            revokeOutcome = { it == peerA },
        )
        assertEquals(false, oneFailed.build().forget())

        val unreadable = Harness(targets = PushTargets.Outcome.RETRY to emptyList())
        assertEquals(false, unreadable.build().forget())
    }

    @Test fun cancellationFromTheProviderIsNotConvertedIntoAnOutcome() = runBlocking {
        val harness = Harness()
        val registrar = PushTokenRegistrar(
            enabled = { true },
            token = { throw CancellationException("cancelled") },
            coordinator = { _, _ -> error("no coordinator for a cancelled token request") },
            peers = { PushTargets.Outcome.COMPLETED to emptyList() },
            register = { _, _ -> PushRegistrationRunner.Outcome.Registered },
            revoke = { false },
        )
        var cancelled = false
        try {
            registrar.run()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertEquals(emptyList<String>(), harness.calls)
    }
}
