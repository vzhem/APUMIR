package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.diagnostics.PushRegisterReason
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The real (re)registration path behind `PushRuntimeCoordinator.register`: it
 * takes the token Google just issued, signs a `register` operation for **one**
 * pinned peer with one ACTIVE registration, posts it, and only after the
 * server accepted it writes the route hint into the encrypted index.
 *
 * This is the only place where a provider token meets a mailbox route, and it
 * keeps them apart:
 *  - the token is used to sign the operation and is only fingerprinted for the
 *    journal; it is never stored in plaintext, indexed or logged;
 *  - the route hint is written by `PushRegistrationStore.registered` only for
 *    the exact sequence that was reserved and sent;
 *  - a failure leaves the journal at the new token, so the coordinator reports
 *    `Failed` and a later attempt re-reserves instead of pretending the old
 *    route still works.
 *
 * A `204` from the service means "operation accepted". It is not proof that
 * Google accepted the token, that the service can reach Google, or that a
 * message will ever arrive.
 */
internal class PushRegistrationRunner(
    private val resolver: PushTrustResolver,
    private val journal: PushRegistrationStore,
    private val flow: (PushRegistrationStore.Participants) -> PushRegistrationFlow,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val recordFailure: () -> Unit = {},
    /**
     * Where this pass stopped. A phase alone cannot say it: `failed` used to
     * cover a service refusal, an unreachable network and an unprepared pair
     * with the same word, and each of those is fixed somewhere else.
     */
    private val recordReason: (PushRegisterReason) -> Unit = {},
) {
    /**
     * Where the last [register] stopped. Read by the caller that has to name
     * the step for the owner's report; a phase alone cannot say it.
     */
    @Volatile
    private var stoppedAt: PushRegisterReason = PushRegisterReason.NONE

    fun lastReason(): PushRegisterReason = stoppedAt

    sealed interface Outcome {
        /** Server accepted the operation and the route hint is indexed. */
        object Registered : Outcome
        /** Nothing was sent: no pinned peer, no ACTIVE record or a bad token. */
        object NotReady : Outcome
        /** The server refused or the transport failed; retry later. */
        object Failed : Outcome
    }

    /**
     * Registers the receiver route for one peer. A token-only provider callback
     * does not know which peers exist, so the caller enumerates the encrypted
     * registration index and calls this per peer.
     */
    suspend fun register(peerId: String, token: String): Outcome {
        val resolved = try {
            resolver.resolveForRegistration(peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            reason(PushRegisterReason.PEER_NOT_READY)
            null
        } ?: return Outcome.NotReady
        return try {
            val participants = PushRegistrationStore.Participants(
                resolved.localId, resolved.peerId, resolved.localBinding, resolved.pinnedPeerBinding,
            )
            // A freshly pinned pair has no record yet; create the local epoch so
            // a first registration is possible. An existing record may lack a
            // route index after an interrupted attempt, and only `reserve`
            // repairs that, so a create-only call is not enough here.
            val stored = journal.read(participants).let { read ->
                if (read is PushRegistrationStore.Result.Missing) journal.ensure(participants, nowMs()) else read
            }
            // The epoch is read back from the journal: a record created a moment
            // ago was not in the trust pass that resolved this peer.
            val state = (stored as? PushRegistrationStore.Result.Ready)?.state ?: run {
                reason(PushRegisterReason.STORAGE_UNAVAILABLE)
                return Outcome.NotReady
            }
            val material = resolved.derive(state.epochHex) ?: run {
                reason(PushRegisterReason.PEER_NOT_READY)
                return Outcome.NotReady
            }
            try {
                return when (
                    flow(participants).register(
                        participants = participants,
                        signer = PushRegistrationFlow.signer(material, resolved.localBinding),
                        token = token,
                        // Re-derive from the same owned material, never from a hint.
                        deriveRoute = { material.routeHex() },
                    )
                ) {
                    PushRegistrationFlow.Result.Registered -> {
                        reason(PushRegisterReason.REGISTERED)
                        Outcome.Registered
                    }
                    // Nothing was sent: the state, token, trust or reservation was
                    // unusable, or the service answered 400/403.
                    PushRegistrationFlow.Result.Rejected -> {
                        reason(PushRegisterReason.REQUEST_INVALID)
                        Outcome.NotReady
                    }
                    PushRegistrationFlow.Result.Conflict -> {
                        reason(PushRegisterReason.SERVER_STALE)
                        Outcome.Failed
                    }
                    PushRegistrationFlow.Result.Throttled -> {
                        reason(PushRegisterReason.SERVER_THROTTLED)
                        Outcome.Failed
                    }
                    PushRegistrationFlow.Result.Unavailable -> {
                        reason(PushRegisterReason.SERVER_UNAVAILABLE)
                        Outcome.Failed
                    }
                    // Unreachable from a register call, but a `when` used as an
                    // expression must cover the whole sealed interface.
                    PushRegistrationFlow.Result.Revoked -> {
                        reason(PushRegisterReason.REQUEST_INVALID)
                        Outcome.NotReady
                    }
                }
            } finally {
                material.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            reason(PushRegisterReason.PEER_NOT_READY)
            Outcome.Failed
        } finally {
            resolved.close()
        }
    }

    /** Revocation clears the local index even when the network is down. */
    suspend fun revoke(peerId: String): Boolean {
        val resolved = try {
            resolver.resolveForRegistration(peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            reason(PushRegisterReason.PEER_NOT_READY)
            null
        } ?: return false
        return try {
            val participants = PushRegistrationStore.Participants(
                resolved.localId, resolved.peerId, resolved.localBinding, resolved.pinnedPeerBinding,
            )
            val epoch = resolved.registration?.epochHex.orEmpty()
            val material = resolved.derive(epoch) ?: return false
            try {
                flow(participants).revoke(
                    participants = participants,
                    signer = PushRegistrationFlow.signer(material, resolved.localBinding),
                ) == PushRegistrationFlow.Result.Revoked
            } finally {
                material.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            false
        } finally {
            resolved.close()
        }
    }

    /** Never throws: a broken reason sink must not turn a failure into a silent one. */
    private fun reason(value: PushRegisterReason) {
        stoppedAt = value
        try {
            recordReason(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushRegistrationRunner").warning("Registration reason unavailable")
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushRegistrationRunner").warning("Registration diagnostics unavailable")
        }
    }
}
