package com.vladimir.messenger.data.push

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Delivers this device's own epoch to a peer: an `offer` of the newly registered
 * epoch, or a `revoke` of the old one.
 *
 * Why it is needed at all: the mailbox is addressed by the *sender's* route, and
 * a sender may only seal a message under an epoch the receiver has offered. Until
 * the offer reaches the peer, the peer cannot write into this device's mailbox
 * and no push can ever arrive.
 *
 * What this class does NOT do: it does not prove delivery. A queued outbox entry
 * is durable, and `HANDLED` from the server is a server-side statement; neither
 * means the peer opened it. The entry is retired only after the server accepted
 * it, and a repeated attempt reuses the exact same id and ciphertext.
 *
 * Trust: every attempt re-reads the live pin, the local binding and the secret,
 * and re-derives the route from them. A changed pin, a changed local identity or
 * an unreadable journal stops the attempt instead of sending to a stale peer.
 */
internal class PushEpochDelivery(
    context: Context,
    private val outbox: PushMailboxOutbox,
    private val client: PushMailboxHttpClient,
    private val crypto: PushWakeCrypto,
    private val recordFailure: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    sealed interface Outcome {
        /** The server accepted the sealed announcement; still not delivery. */
        object Handled : Outcome
        /** The server stored it and a wake is queued for the peer. */
        object Stored : Outcome
        /** Nothing was sent: no pin, no epoch, a changed identity or unusable state. */
        object NotReady : Outcome
        /** A durable outbox entry exists and a later attempt will reuse it. */
        object Deferred : Outcome
        object Throttled : Outcome
        object Failed : Outcome
    }

    /**
     * Offers [state]'s epoch to [peerId], sealing it under the peer's pinned
     * binding. Only the sender direction can produce an offer, and only a live
     * registration with an accepted epoch can be offered at all.
     */
    fun offer(
        state: PushRegistrationState.State,
        peerId: String,
        localBinding: ByteArray,
        pinnedPeer: ByteArray,
        localSecret: ByteArray,
    ): Outcome = send(state, peerId, localBinding, pinnedPeer, localSecret)

    /** Sends the revocation of an epoch the peer must stop using. */
    fun revoke(
        state: PushRegistrationState.State,
        peerId: String,
        localBinding: ByteArray,
        pinnedPeer: ByteArray,
        localSecret: ByteArray,
    ): Outcome {
        if (state.phase != PushRegistrationState.Phase.REVOKING &&
            state.phase != PushRegistrationState.Phase.REVOKED
        ) return Outcome.NotReady
        return send(state, peerId, localBinding, pinnedPeer, localSecret)
    }

    private fun send(
        state: PushRegistrationState.State,
        peerId: String,
        localBinding: ByteArray,
        pinnedPeer: ByteArray,
        localSecret: ByteArray,
    ): Outcome {
        if (localBinding.size !in 1..512 || pinnedPeer.size !in 1..512 || localSecret.size != 32) {
            return Outcome.NotReady
        }
        val announcement = state.announcement() ?: return Outcome.NotReady
        val local = localBinding.copyOf()
        val peer = pinnedPeer.copyOf()
        val secret = localSecret.copyOf()
        return try {
            val localId = runCatching { uniffi.p2p_core.fileExchangeBindingNodeId(local) }.getOrNull()
                ?: return Outcome.NotReady
            val peerNodeId = runCatching { uniffi.p2p_core.fileExchangeBindingNodeId(peer) }.getOrNull()
                ?: return Outcome.NotReady
            if (peerNodeId != peerId || localId == peerNodeId) return Outcome.NotReady
            val packet = PushEpochProtocol.parse(announcement) ?: return Outcome.NotReady
            // The offered epoch must belong to this exact pair.
            if (packet.ownerBindingHash != PushEpochProtocol.bindingHash(local) ||
                packet.targetBindingHash != PushEpochProtocol.bindingHash(peer)
            ) return Outcome.NotReady

            val now = nowMs()
            val expiresAt = now + ttl
            if (!PushMailboxProtocol.validTimestamp(now)) return Outcome.NotReady
            val epoch = PushEpochProtocol.State.from(packet)
            val draft = PushMailboxOutbox.Draft(
                id = recordId(localId, state.generation, announcement),
                sender = localId,
                recipient = peerNodeId,
                sentAtMs = now,
                content = announcement,
                localBinding = local,
                pinnedPeer = peer,
                localSecret = secret,
                epoch = epoch,
                expiresAt = expiresAt,
            )
            val prepared = when (val result = outbox.prepare(draft) { true }) {
                is PushMailboxOutbox.Result.Ready -> result
                PushMailboxOutbox.Result.Unavailable -> return Outcome.Deferred
                else -> return Outcome.NotReady
            }
            val material = crypto.derive(local, peer, secret, epoch.epochHex.orEmpty(), true)
                ?: return Outcome.NotReady
            val request = try {
                material.mailboxPut(
                    localBinding = local,
                    pinnedPeerBinding = peer,
                    state = epoch,
                    id = prepared.entry.id,
                    expiresAt = prepared.entry.expiresAt,
                    envelope = prepared.entry.envelope,
                    current = { true },
                )
            } finally {
                material.close()
            } ?: return Outcome.NotReady

            return when (val result = client.put(request)) {
                is PushMailboxHttpClient.PutResult.Handled -> {
                    outbox.retire(prepared.entry) { true }
                    Outcome.Handled
                }
                is PushMailboxHttpClient.PutResult.Stored -> {
                    // The entry stays: the peer may still need it after a restart,
                    // and re-sending the same id is idempotent on the server.
                    Outcome.Stored
                }
                PushMailboxHttpClient.PutResult.RouteUnavailable,
                PushMailboxHttpClient.PutResult.Conflict -> Outcome.Failed
                PushMailboxHttpClient.PutResult.Throttled -> Outcome.Throttled
                PushMailboxHttpClient.PutResult.Rejected -> Outcome.NotReady
                PushMailboxHttpClient.PutResult.Unavailable -> Outcome.Deferred
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            Outcome.Failed
        } finally {
            java.util.Arrays.fill(local, 0)
            java.util.Arrays.fill(peer, 0)
            java.util.Arrays.fill(secret, 0)
        }
    }

    /**
     * A stable id per (device, generation, announcement): a retried send reuses
     * the same server row instead of creating a second message for the peer.
     */
    private fun recordId(localId: String, generation: Long, announcement: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "apu-push-epoch:$localId:$generation:$announcement".toByteArray(Charsets.UTF_8),
        ).toString()

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushEpochDelivery").warning("Epoch delivery diagnostics unavailable")
        }
    }

    companion object {
        /** The peer may be offline for a day; the outbox keeps the ciphertext. */
        const val DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000
    }
}
