package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.diagnostics.PushRegisterReason
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Turns "Google issued this token" into "the mailbox service knows the routes
 * of this device" — and nothing more.
 *
 * Flow: read the token from the SDK → record its fingerprint in the encrypted
 * token journal (compare-and-set) → for every peer that has a live pin *and* an
 * ACTIVE registration, sign and post one `register` operation → index each route
 * only after the server accepted that exact operation.
 *
 * Honest outcomes:
 *  - `DISABLED` / `NOT_CONFIGURED` / `NO_PLAY_SERVICES`: nothing was stored,
 *    sent or scheduled; the app keeps working exactly as before;
 *  - `REGISTERED`: every live peer accepted the operation. It still does not
 *    prove Google will deliver a signal or that any route is reachable now;
 *  - `PARTIAL`: some peers registered, at least one refused. Reported as a
 *    failure so the caller retries; a `204` for one peer says nothing about the
 *    others;
 *  - `FAILED`: nothing trustworthy happened; retry later with backoff.
 */
internal class PushTokenRegistrar(
    private val enabled: () -> Boolean,
    private val token: suspend () -> PushFirebaseSignals.Outcome,
    private val coordinator: (register: suspend (String) -> Boolean, record: () -> Boolean) -> PushRuntimeCoordinator,
    private val peers: suspend () -> Pair<PushTargets.Outcome, List<PushTargets.Peer>>,
    /** One signed `register` operation for one peer; see [PushRegistrationRunner]. */
    private val register: suspend (peerId: String, token: String) -> PushRegistrationRunner.Outcome,
    /** Revoke the route of one peer; false keeps the caller retrying. */
    private val revoke: suspend (peerId: String) -> Boolean,
    /** Durable token journal; a Missing/Rotated value requires a registration. */
    private val forgetToken: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val recordFailure: () -> Unit = {},
    /** See [PushRegistrationRunner]: the phase says what, the reason says where. */
    private val recordReason: (PushRegisterReason) -> Unit = {},
    /**
     * Reader of the per-peer runner's own reason. Production wiring supplies
     * it, so `registered == 0` can name the step that stopped; tests and
     * older callers leave it out and keep the coarse outcome.
     */
    private val reasonSink: (() -> PushRegisterReason)? = null,
) {
    sealed interface Outcome {
        object Disabled : Outcome
        object NotConfigured : Outcome
        object NoPlayServices : Outcome
        /** Journal unchanged: this exact token was already registered. */
        object AlreadyCurrent : Outcome
        object Registered : Outcome
        object Partial : Outcome
        object Failed : Outcome
        /**
         * There was nothing to register: no pin and no indexed route at all.
         *
         * Kept apart from [Failed] on purpose. A device with no correspondent yet
         * must not look like a broken registration, and a device that does have
         * one must not look idle — those are different diagnoses for the owner.
         */
        object NoTargets : Outcome
    }

    /** Set by [registerAll] when the peer set was complete and empty. */
    @Volatile
    private var noTargets = false

    /** Where the last per-peer attempt stopped; see [recordReason]. */
    @Volatile
    private var lastReason: PushRegisterReason = PushRegisterReason.NONE

    suspend fun run(): Outcome = runSafely()

    private suspend fun runSafely(): Outcome {
        return try {
            if (!enabled()) Outcome.Disabled else {
                when (val provider = token()) {
                    is PushFirebaseSignals.Outcome.Token -> {
                        noTargets = false
                        when (coordinator({ issued -> registerAll(issued) }, { false }).onToken(provider.value, nowMs())) {
                            PushRuntimeCoordinator.TokenOutcome.Disabled -> Outcome.Disabled
                            PushRuntimeCoordinator.TokenOutcome.AlreadyCurrent ->
                                // Token did not rotate, so indexed peers need zero network calls;
                                // only newly pinned seeds (contacts, group or channel members) run.
                                registerUnindexedSeeds(provider.value)
                            is PushRuntimeCoordinator.TokenOutcome.Registered -> Outcome.Registered
                            PushRuntimeCoordinator.TokenOutcome.Failed -> {
                                // Whoever stopped already wrote its own step
                                // straight to the report: the coordinator for the
                                // token journal, the per-peer runner for the
                                // operation. A coarse guess from this level
                                // erased exactly that line - the report went
                                // back to naming a service refusal, a dead
                                // network and an unprepared pair at once, which
                                // answers none of them. Only the empty-peer
                                // case is decided here; anything else keeps the
                                // step that actually stopped.
                                if (noTargets) reason(PushRegisterReason.NO_PINNED_PEERS)
                                else if (lastReason != PushRegisterReason.NONE) reason(lastReason)
                                if (noTargets) Outcome.NoTargets else Outcome.Failed
                            }
                        }
                    }
                    PushFirebaseSignals.Outcome.NotConfigured -> {
                        reason(PushRegisterReason.TOKEN_UNAVAILABLE)
                        Outcome.NotConfigured
                    }
                    PushFirebaseSignals.Outcome.NoPlayServices -> {
                        reason(PushRegisterReason.TOKEN_UNAVAILABLE)
                        Outcome.NoPlayServices
                    }
                    PushFirebaseSignals.Outcome.Failed -> {
                        reason(PushRegisterReason.TOKEN_UNAVAILABLE)
                        Outcome.Failed
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            Outcome.Failed
        }
    }

    /**
     * One operation per peer. `true` only when every live peer was accepted; a
     * single refusal fails the whole attempt, because a partially registered
     * device would silently miss signals for the others.
     */
    private suspend fun registerAll(token: String): Boolean {
        val (state, list) = try {
            peers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            reason(PushRegisterReason.TARGETS_UNREADABLE)
            return false
        }
        // An unreadable index must not be mistaken for "no peers".
        if (state != PushTargets.Outcome.COMPLETED) {
            reason(PushRegisterReason.TARGETS_UNREADABLE)
            return false
        }
        // Nothing to address: no pin and no indexed route. Recorded as its own
        // outcome by [runSafely] rather than as a failure of the server.
        if (list.isEmpty()) {
            noTargets = true
            reason(PushRegisterReason.NO_PINNED_PEERS)
            return false
        }
        var registered = 0
        for (peer in list) {
            when (register(peer.peerId, token)) {
                PushRegistrationRunner.Outcome.Registered -> registered++
                // No usable local record for this peer: nothing was sent, and
                // nothing may be counted. Other peers still get their attempt.
                PushRegistrationRunner.Outcome.NotReady -> Unit
                PushRegistrationRunner.Outcome.Failed -> {
                    diag()
                    // The runner named the step before returning. Keep it here,
                    // or the caller's fallback below would overwrite it with
                    // "no reason recorded" - which is how a phone report ended
                    // up naming three illnesses at once again.
                    reasonSink?.let { sink -> lastReason = sink() }
                    return false
                }
            }
            reasonSink?.let { sink -> lastReason = sink() }
        }
        // NotReady peers have no usable registration record. They must not be
        // counted as registered, and a pass that registered nobody is not a
        // success either: the caller retries with backoff instead of believing
        // this device is reachable.
        if (registered == 0) {
            diag()
            // Nothing was sent: the last per-peer reason already names the step
            // that stopped, so keep it instead of a generic failure.
            reason(lastReason)
        } else {
            reason(PushRegisterReason.REGISTERED)
        }
        return registered > 0
    }

    /**
     * Incremental bootstrap for peers pinned after the current token was already
     * confirmed (for example after joining a group or channel). Indexed routes
     * (`generation > 0`) are untouched; when no seed exists no network call runs.
     */
    private suspend fun registerUnindexedSeeds(token: String): Outcome {
        val (state, list) = try {
            peers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            reason(PushRegisterReason.TARGETS_UNREADABLE)
            return Outcome.Failed
        }
        if (state != PushTargets.Outcome.COMPLETED) {
            reason(PushRegisterReason.TARGETS_UNREADABLE)
            return Outcome.Failed
        }
        val seeds = list.filter { it.generation == 0L }
        if (seeds.isEmpty()) return Outcome.AlreadyCurrent
        val batch = seeds.take(MAX_SEED_REGISTRATIONS_PER_PASS)
        var registered = 0
        for (peer in batch) {
            when (register(peer.peerId, token)) {
                PushRegistrationRunner.Outcome.Registered -> registered++
                PushRegistrationRunner.Outcome.NotReady -> Unit
                PushRegistrationRunner.Outcome.Failed -> {
                    diag()
                    reasonSink?.let { sink -> lastReason = sink() }
                    if (lastReason != PushRegisterReason.NONE) reason(lastReason)
                    return if (registered > 0) Outcome.Partial else Outcome.Failed
                }
            }
            reasonSink?.let { sink -> lastReason = sink() }
        }
        if (registered == 0) {
            diag()
            if (lastReason != PushRegisterReason.NONE) reason(lastReason)
            return Outcome.Failed
        }
        reason(PushRegisterReason.REGISTERED)
        return if (seeds.size > batch.size) Outcome.Partial else Outcome.Registered
    }

    /** Out of the app: forget the token and revoke every live route locally. */
    suspend fun forget(): Boolean {
        val (state, list) = try {
            peers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            return false
        }
        if (state != PushTargets.Outcome.COMPLETED) return false
        var all = try {
            forgetToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            false
        }
        for (peer in list) {
            if (!revoke(peer.peerId)) all = false
        }
        return all
    }

    /** Never throws: a broken reason sink must not turn a failure into a silent one. */
    private fun reason(value: PushRegisterReason) {
        try {
            recordReason(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushTokenRegistrar").warning("Registration reason unavailable")
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushTokenRegistrar").warning("Registration diagnostics unavailable")
        }
    }

    companion object {
        /** Keeps large channel bootstrap well below the Worker hourly IP budget (240/hr). */
        internal const val MAX_SEED_REGISTRATIONS_PER_PASS = 64
    }
}
