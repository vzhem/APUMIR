package com.vladimir.messenger.data.push

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The one call site that asks the service to wake a correspondent.
 *
 * Before this existed the app could receive a signal, register a route and store
 * ciphertext, but nothing ever asked the service to wake the other side - so a
 * sleeping phone was never woken by us, and a two-phone test could not have
 * passed for any reason other than luck.
 *
 * This runs *after* the ordinary send has been attempted, and every outcome is
 * best effort: it cannot change a message status, retry a send, or report
 * delivery. `ACCEPTED` (HTTP 202) means the Worker took the request. It does not
 * mean the peer woke, looked, or that a message exists.
 *
 * A wake is attempted only when this device has an ACTIVE, server-confirmed
 * registration and a live pin for that exact peer, and no wake for that peer was
 * accepted within the local budget. With the gate closed (a shipped build that is
 * not a test build) this returns immediately without touching storage or network.
 */
internal object PushWakePublish {

    /** Why nothing was sent, or that the Worker accepted the request. */
    sealed interface Outcome {
        /** The Worker accepted the request; this is not delivery. */
        object Accepted : Outcome
        /** Sent recently enough: the budget suppressed this one. */
        object WithinBudget : Outcome
        /** Our own route is not registered, so no wake can exist. */
        object NotRegistered : Outcome
        /** The service refused or could not be reached. */
        object Unavailable : Outcome
        /** The gate is closed, the peer is blank, or push is not configured. */
        object Skipped : Outcome
    }

    /**
     * Ask the service to wake [peerId]. Safe to call on every send: it is bounded
     * by the per-peer interval in [PushWakeSignal], and it never throws.
     */
    suspend fun wake(context: Context, peerId: String): Outcome {
        if (peerId.isBlank()) return Outcome.Skipped
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return Outcome.Skipped
        PushDiagnostics.install(app)
        val outcome = attempt(app, peerId)
        PushDiagnostics.noteWake(
            when (outcome) {
                Outcome.Accepted -> PushDiagnostics.Wake.ACCEPTED
                Outcome.WithinBudget -> PushDiagnostics.Wake.WITHIN_BUDGET
                Outcome.NotRegistered -> PushDiagnostics.Wake.NOT_REGISTERED
                Outcome.Unavailable -> PushDiagnostics.Wake.UNAVAILABLE
                Outcome.Skipped -> PushDiagnostics.Wake.SKIPPED
            },
        )
        return outcome
    }

    /** The bounded attempt itself; [wake] only measures and records it. */
    private suspend fun attempt(app: android.content.Context, peerId: String): Outcome {
        return try {
            val endpoint = PushServiceLocator.endpoint(app) ?: return Outcome.Skipped
            val shared = PushServiceLocator.shared(app)
            val http = PushHttpClient(endpoint, recordFailure = { diag("Wake transport failure") })
            val budget = budget(app)
            val resolver = shared.resolver
            resolver.resolve(peerId)?.use { pass ->
                val state = pass.registration ?: return Outcome.NotRegistered
                // Only an ACTIVE, accepted registration can be addressed; anything
                // else would be a wake with a capability nobody accepted.
                if (state.phase != PushRegistrationState.Phase.ACTIVE || state.registeredAtMs <= 0) {
                    return Outcome.NotRegistered
                }
                val material = shared.crypto.derive(
                    pass.localBinding,
                    pass.pinnedPeerBinding,
                    pass.localSecret,
                    state.epochHex,
                    localIsSender = true,
                ) ?: return Outcome.NotRegistered
                try {
                    val last = when (val recorded = budget.last(peerId)) {
                        is PushWakeBudget.Last.At -> recorded.atMs
                        PushWakeBudget.Last.Never, PushWakeBudget.Last.Unavailable -> 0L
                    }
                    val decision = signal.decide(PushWakeSignal.Candidate(material, last))
                    when (decision) {
                        is PushWakeSignal.Decision.Send -> {
                            val result = http.wake(decision.request)
                            if (PushWakeSignal.wasAccepted(result)) {
                                // Only an acceptance consumes the budget: a refused
                                // or unreachable service must not silence the peer
                                // for the whole interval.
                                budget.record(peerId)
                                Outcome.Accepted
                            } else {
                                Outcome.Unavailable
                            }
                        }
                        PushWakeSignal.Decision.TooSoon -> Outcome.WithinBudget
                        PushWakeSignal.Decision.NotRegistered -> Outcome.NotRegistered
                    }
                } finally {
                    material.close()
                }
            } ?: Outcome.NotRegistered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the capability or exception text.
            diag("Wake attempt failed")
            Outcome.Unavailable
        }
    }

    private val signal = PushWakeSignal()

    private fun budget(app: Context): PushWakeBudget = synchronized(LOCK) {
        instance ?: PushWakeBudget(app).also { instance = it }
    }

    private val LOCK = Any()
    private var instance: PushWakeBudget? = null

    private fun diag(message: String) {
        try {
            PushServiceLocator.log(message)
        } catch (_: Exception) {
            Logger.getLogger("PushWakePublish").warning(message)
        }
    }
}
