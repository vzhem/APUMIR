package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The production answer to "who is this device talking to, and is that still
 * true right now?" — the piece every earlier class demanded but none supplied.
 *
 * For one operation it reads, from the live stores every time:
 *  1. the current local profile (`node_id` and the file-exchange binding);
 *  2. the *pinned* binding of the peer (Room, strict TOFU; never created here);
 *  3. the local exchange secret, which is the only input that cannot be
 *     re-derived and therefore the only one that makes trust real;
 *  4. optionally the ACTIVE registration record for exactly those participants.
 *
 * It never creates a pin, never repairs a record and never reads trust from a
 * provider payload. A missing pin, a missing/rotated registration, a changed
 * local binding, a stale secret or an unusable epoch produce `null`, which every
 * caller treats as "retry later", never as "nothing to do".
 *
 * Deliberate limits: resolving is a sequence of reads, not a lock, so a change
 * between two reads is only noticed by callers that read the same values again
 * before each effect ([PushMailboxReceiver] does). The secret is copied for the
 * duration of a pass and wiped in [Pass.close]; the Keystore provider and the
 * Rust FFI may retain copies that cannot be zeroized.
 */
internal class PushTrustResolver(
    private val localNodeId: () -> String,
    private val localBinding: () -> ByteArray?,
    private val localSecret: () -> ByteArray?,
    private val pinnedPeer: suspend (peerId: String) -> ByteArray?,
    private val registration: (PushRegistrationStore.Participants) -> PushRegistrationStore.Result,
    private val recordFailure: () -> Unit = {},
) {
    /**
     * Everything one operation may use. Owns copies of the three secrets; the
     * caller must [close] it, and must not share it across concurrent windows.
     */
    class Pass internal constructor(
        val localId: String,
        val peerId: String,
        internal val localBinding: ByteArray,
        internal val localSecret: ByteArray,
        internal val pinnedPeerBinding: ByteArray,
        val registration: PushRegistrationState.State?,
        /**
         * Resolving never derives: the caller supplies its own [PushWakeCrypto]
         * so the FFI seam stays out of the trust path entirely.
         */
        private val crypto: PushWakeCrypto = PushWakeCrypto(recordFailure = {}),
    ) : AutoCloseable {
        private var closed = false

        /** Derive material for [epochHex] in the receiver direction. */
        fun derive(epochHex: String): PushWakeCrypto.Material? {
            if (closed || epochHex.isEmpty()) return null
            return crypto.derive(localBinding, pinnedPeerBinding, localSecret, epochHex, false)
        }

        /** The route for the registered epoch, or null when nothing is registered. */
        override fun close() {
            if (closed) return
            closed = true
            localBinding.fill(0)
            localSecret.fill(0)
            pinnedPeerBinding.fill(0)
        }

        override fun toString(): String = "PushTrustPass(redacted)"
    }

    /** For a receive window: requires an ACTIVE, server-confirmed registration. */
    suspend fun resolve(peerId: String): Pass? = base(peerId, requireRegistered = true)

    /** For (re)registration: the record may still be waiting for its first acceptance. */
    suspend fun resolveForRegistration(peerId: String): Pass? = base(peerId, requireRegistered = false)

    /** Fresh snapshot of the same values for the drain trust gate. */
    suspend fun snapshot(localId: String, peerId: String): PushDrainTrustGate.Snapshot? {
        return try {
            val local = localBinding() ?: return null
            val pin = pinnedPeer(peerId) ?: run {
                local.fill(0)
                return null
            }
            val secret = localSecret() ?: run {
                local.fill(0)
                pin.fill(0)
                return null
            }
            PushDrainTrustGate.Snapshot(localId, peerId, local, pin, secret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        }
    }

    private suspend fun base(peerId: String, requireRegistered: Boolean): Pass? {
        val nodeId = localNodeId()
        if (nodeId.isBlank()) return null
        val local = localBinding() ?: return null
        val pin = try {
            pinnedPeer(peerId)
        } catch (cancelled: CancellationException) {
            local.fill(0)
            throw cancelled
        } catch (_: Exception) {
            local.fill(0)
            diag()
            return null
        } ?: run {
            local.fill(0)
            return null
        }
        val secret = localSecret() ?: run {
            local.fill(0)
            pin.fill(0)
            return null
        }
        var keep = false
        return try {
            if (local.size !in 1..512 || pin.size !in 1..512 || secret.size != 32) return null
            if (MessageDigest.isEqual(local, pin)) return null
            val participants = PushRegistrationStore.Participants(nodeId, peerId, local, pin)
            val read = registration(participants)
            // "No record" is distinct from "the journal is unreadable", but
            // neither may be turned into trust or into silence.
            val state = (read as? PushRegistrationStore.Result.Ready)?.state ?: return null
            val usable = state.phase == PushRegistrationState.Phase.ACTIVE && state.epochHex.isNotEmpty() &&
                (!requireRegistered || state.registeredAtMs > 0)
            if (!usable) return null
            Pass(nodeId, peerId, local, secret, pin, state).also { keep = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } finally {
            if (!keep) {
                local.fill(0)
                pin.fill(0)
                secret.fill(0)
            }
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushTrustResolver").warning("Trust diagnostics unavailable")
        }
    }

    companion object {
        /** Production wiring: every value re-read from the live profile each call. */
        fun of(
            context: android.content.Context,
            pins: com.vladimir.messenger.data.file.FileExchangePeerStore,
            registrations: PushRegistrationStore,
        ): PushTrustResolver {
            val app = context.applicationContext
            return PushTrustResolver(
                localNodeId = { com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(app) },
                localBinding = {
                    com.vladimir.messenger.data.file.FileExchangeKeyStore.publicBinding(app)
                },
                localSecret = {
                    if (com.vladimir.messenger.data.file.FileExchangeKeyStore.mode(app) !=
                        com.vladimir.messenger.data.file.FileExchangeKeyStore.Mode.READY
                    ) {
                        null
                    } else {
                        // A copy: withExistingSecret wipes its own buffer.
                        com.vladimir.messenger.data.file.FileExchangeKeyStore.withExistingSecret(app) { it.copyOf() }
                    }
                },
                pinnedPeer = { peer -> pins.bindingFor(peer) },
                registration = { participants -> registrations.read(participants) },
            )
        }
    }
}
