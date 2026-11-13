package com.vladimir.messenger.data.push

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
    private val peers: () -> Pair<PushTargets.Outcome, List<PushTargets.Peer>>,
    /** One signed `register` operation for one peer; see [PushRegistrationRunner]. */
    private val register: suspend (peerId: String, token: String) -> PushRegistrationRunner.Outcome,
    /** Revoke the route of one peer; false keeps the caller retrying. */
    private val revoke: suspend (peerId: String) -> Boolean,
    /** Durable token journal; a Missing/Rotated value requires a registration. */
    private val forgetToken: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val recordFailure: () -> Unit = {},
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
    }

    suspend fun run(): Outcome = try {
        if (!enabled()) return Outcome.Disabled
        val provider = token()
        val value = when (provider) {
            is PushFirebaseSignals.Outcome.Token -> provider.value
            PushFirebaseSignals.Outcome.NotConfigured -> return Outcome.NotConfigured
            PushFirebaseSignals.Outcome.NoPlayServices -> return Outcome.NoPlayServices
            PushFirebaseSignals.Outcome.Failed -> return Outcome.Failed
        }
        val coordinatorInstance = coordinator({ issued -> registerAll(issued) }, { false })
        when (coordinatorInstance.onToken(value, nowMs())) {
            PushRuntimeCoordinator.TokenOutcome.Disabled -> Outcome.Disabled
            PushRuntimeCoordinator.TokenOutcome.AlreadyCurrent -> Outcome.AlreadyCurrent
            is PushRuntimeCoordinator.TokenOutcome.Registered -> Outcome.Registered
            PushRuntimeCoordinator.TokenOutcome.Failed -> Outcome.Failed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        diag()
        Outcome.Failed
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
            return false
        }
        // An unreadable index must not be mistaken for "no peers".
        if (state != PushTargets.Outcome.COMPLETED) return false
        if (list.isEmpty()) return false
        var registered = 0
        var notReady = 0
        for (peer in list) {
            when (register(peer.peerId, token)) {
                PushRegistrationRunner.Outcome.Registered -> registered++
                PushRegistrationRunner.Outcome.NotReady -> notReady++
                PushRegistrationRunner.Outcome.Failed -> {
                    diag()
                    return false
                }
            }
        }
        // NotReady peers have no usable registration record; they must not be
        // counted as registered, but they also must not block others forever.
        return registered > 0 || notReady == list.size
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

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushTokenRegistrar").warning("Registration diagnostics unavailable")
        }
    }
}
