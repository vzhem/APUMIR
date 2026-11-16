package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.diagnostics.PushWakeOutcome
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
 * A wake needs a live pin for that exact peer and the *peer's* epoch: the
 * receiver chooses its epoch and announces it, and only with that epoch do both
 * sides derive the same route. Using our own registration epoch here was a real
 * defect: the two phones computed different capabilities, so every wake addressed
 * a route nobody had registered and disappeared without a trace.
 *
 * A wake is attempted only when the peer's epoch is known and no wake for that
 * peer was attempted in the last minute (in-process throttle) or accepted within
 * the local budget. With the gate closed (a shipped build that is not a test
 * build) this returns immediately without touching storage or network.
 */
internal object PushWakePublish {

    /** Why nothing was sent, or that the Worker accepted the request. */
    sealed interface Outcome {
        /** The Worker accepted the request; this is not delivery. */
        object Accepted : Outcome
        /** Sent recently enough: the budget suppressed this one. */
        object WithinBudget : Outcome
        /**
         * No trust pass for this pair: no pin, a changed identity or an unusable
         * exchange secret. Nothing may be derived for this correspondent.
         */
        object NotRegistered : Outcome
        /**
         * The peer never announced an epoch to us, so there is no route to wake.
         *
         * Kept apart from [NotRegistered] because the fix is on the other side of
         * the pair: this device is ready, the correspondent has not spoken yet.
         */
        object NoPeerEpoch : Outcome
        /** The service refused or could not be reached. */
        object Unavailable : Outcome
        /** The gate is closed, the peer is blank, or push is not configured. */
        object Skipped : Outcome
    }

    /**
     * Ask the service to wake [peerId]. Safe to call on every send and on every
     * retry pass: it never throws and never blocks a caller for long.
     *
     * Two independent limits. The local budget is consumed only by an accepted
     * request, so a refused service is retried later. The in-process throttle
     * above it exists because retries can call this every few seconds: without it
     * a single stuck message would turn into an unbounded stream of storage reads
     * and counted attempts that all say the same thing.
     */
    suspend fun wake(context: Context, peerId: String): Outcome {
        if (peerId.isBlank()) return Outcome.Skipped
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return Outcome.Skipped
        PushDiagnostics.install(app)
        install(app)
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[peerId]
        if (previous != null && now >= previous && now - previous < MIN_ATTEMPT_INTERVAL_MS) {
            return Outcome.WithinBudget
        }
        lastAttemptAtMs[peerId] = now
        val outcome = attempt(app, peerId)
        PushDiagnostics.noteWake(
            when (outcome) {
                Outcome.Accepted -> PushWakeOutcome.ACCEPTED
                Outcome.WithinBudget -> PushWakeOutcome.WITHIN_BUDGET
                Outcome.NotRegistered -> PushWakeOutcome.NOT_REGISTERED
                Outcome.NoPeerEpoch -> PushWakeOutcome.NO_PEER_EPOCH
                Outcome.Unavailable -> PushWakeOutcome.UNAVAILABLE
                Outcome.Skipped -> PushWakeOutcome.SKIPPED
            },
        )
        if (outcome == Outcome.Accepted) PushDiagnostics.noteWakeAccepted()
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
            // Wake needs trust (pin, own binding, exchange secret), not our own
            // registration: that record says how *we* are reached, and using its
            // epoch here addresses a route the correspondent never registered.
            resolver.resolveForRegistration(peerId)?.use { pass ->
                val peerEpoch = peerEpoch(shared, pass) ?: return Outcome.NoPeerEpoch
                val material = shared.crypto.derive(
                    pass.localBinding,
                    pass.pinnedPeerBinding,
                    pass.localSecret,
                    peerEpoch,
                    localIsSender = true,
                ) ?: return Outcome.Unavailable
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
                        PushWakeSignal.Decision.NotRegistered -> Outcome.NoPeerEpoch
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

    /**
     * The epoch the correspondent chose for this pair, or null when it has not
     * announced one yet.
     *
     * A revoke is not an address: only an offer carries an epoch, and a stored
     * revoke means the peer asked us to stop using the previous one.
     */
    private fun peerEpoch(shared: PushServiceLocator.Shared, pass: PushTrustResolver.Pass): String? {
        val stored = shared.journals.epochs.read(
            pass.localId,
            pass.peerId,
            pass.pinnedPeerBinding,
            pass.localBinding,
        )
        val state = (stored as? PushEpochStore.ReadResult.Found)?.state ?: return null
        if (state.action != PushEpochProtocol.Action.OFFER) return null
        val epoch = state.epochHex ?: return null
        return epoch.takeIf { it.length == 32 }
    }

    /**
     * Process-wide application context, so retry pumps can wake a peer without
     * every call site threading a Context through a god file. Null until
     * [PushServiceLocator.onStartup] installs an enabled build.
     */
    @Volatile
    private var installed: android.content.Context? = null

    fun install(context: Context) {
        installed = context.applicationContext
    }

    /** Wake through the installed context; a no-op before startup ran. */
    suspend fun wakeInstalled(peerId: String): Outcome {
        val app = installed ?: return Outcome.Skipped
        return wake(app, peerId)
    }

    /** In-memory throttle; never persisted, so a restart retries immediately. */
    private val lastAttemptAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MIN_ATTEMPT_INTERVAL_MS = 60_000L

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
