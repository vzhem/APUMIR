package com.vladimir.messenger.data.push

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
    private val flow: (PushRegistrationStore.Participants) -> PushRegistrationFlow,
    private val recordFailure: () -> Unit = {},
) {
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
            null
        } ?: return Outcome.NotReady
        return try {
            val participants = PushRegistrationStore.Participants(
                resolved.localId, resolved.peerId, resolved.localBinding, resolved.pinnedPeerBinding,
            )
            val epoch = resolved.registration?.epochHex.orEmpty()
            val material = resolved.derive(epoch) ?: return Outcome.NotReady
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
                    PushRegistrationFlow.Result.Registered -> Outcome.Registered
                    PushRegistrationFlow.Result.Rejected -> Outcome.NotReady
                    else -> Outcome.Failed
                }
            } finally {
                material.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
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
