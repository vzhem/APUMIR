package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * The only place where a peer's epoch announcement may stop being quarantined.
 *
 * [PushControlIngress] keeps the whole `APUPUSH…` family out of the chat path;
 * this class is the narrow, authenticated exception for the mailbox push feature:
 *
 *  1. the wire must be a sealed envelope whose embedded sender binding is exactly
 *     the pinned key of the claimed sender (never a key carried in the packet);
 *  2. it must open under this device's own secret;
 *  3. the plaintext must parse as an epoch packet addressed to this pair;
 *  4. the journal's own [PushEpochStore.accept] re-checks all of the above and
 *     enforces monotonic generation, so a replayed older epoch is a duplicate or
 *     a rejection, never an overwrite.
 *
 * Everything else — a plaintext packet, an unknown sender, a failed open, an
 * unreadable journal — leaves the text in quarantine and changes nothing. A
 * successful accept means "the peer offered an epoch", not "a message arrived"
 * and not "this device may now reach the peer".
 *
 * The dependencies are seams, not stores: production wiring lives in [of], so the
 * decision order above is exercised without an emulator, a Keystore or the Rust
 * library. Nothing here is a policy variable — every seam is a read of live state
 * or the single journal write.
 */
internal class PushEpochImport(
    private val localNodeId: () -> String,
    private val pinnedPeer: suspend (peerId: String) -> ByteArray?,
    private val localBinding: () -> ByteArray?,
    private val open: (wire: String) -> String?,
    private val accept: (
        localId: String,
        peerId: String,
        packet: PushEpochProtocol.Announcement,
        pinned: ByteArray,
        local: ByteArray,
    ) -> PushEpochStore.WriteResult,
    private val senderBinding: (wire: String) -> ByteArray? = SealedWire::senderBinding,
    private val nodeIdOfBinding: (binding: ByteArray) -> String = ::fileExchangeBindingNodeId,
    private val recordFailure: () -> Unit = {},
) {
    /** What happened to one reserved-looking text. Never carries the text itself. */
    enum class Outcome {
        /** Applied; the peer's epoch is now stored for this pair. */
        APPLIED,
        /** The same generation was already stored. */
        DUPLICATE,
        /** Identity, pin or profile changed between the checks; leave it alone. */
        REJECTED,
        /** Not an epoch packet, not sealed, or the sender is not the pinned peer. */
        NOT_APPLICABLE,
        /** Storage failed; the caller must retry later, not treat this as applied. */
        UNAVAILABLE,
    }

    suspend fun import(senderId: String, wire: String): Outcome {
        if (senderId.isBlank() || wire.isBlank()) return Outcome.NOT_APPLICABLE
        if (!SealedWire.isSealed(wire)) return Outcome.NOT_APPLICABLE
        // The envelope must name the pinned sender. This is a header check only:
        // it decides nothing until the pin itself is compared below.
        val claimed = try {
            senderBinding(wire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } ?: return Outcome.NOT_APPLICABLE
        val claimedSender = try {
            nodeIdOfBinding(claimed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } finally {
            claimed.fill(0)
        }
        if (claimedSender == null || claimedSender != senderId) return Outcome.NOT_APPLICABLE

        val localId = try {
            localNodeId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            ""
        }
        if (localId.isBlank() || localId == senderId) return Outcome.NOT_APPLICABLE

        val pinned = try {
            pinnedPeer(senderId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        }
        if (pinned == null || pinned.isEmpty()) return Outcome.NOT_APPLICABLE

        val local = try {
            localBinding()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        }
        if (local == null || local.isEmpty()) {
            pinned.fill(0)
            return Outcome.NOT_APPLICABLE
        }

        return try {
            val plaintext = open(wire) ?: return Outcome.NOT_APPLICABLE
            if (!PushEpochProtocol.isPacket(plaintext)) return Outcome.NOT_APPLICABLE
            val packet = PushEpochProtocol.parse(plaintext) ?: return Outcome.REJECTED
            // Re-check the key the envelope actually carries against the pin that
            // is stored right now: both must be the same bytes, not merely valid.
            val embedded = senderBinding(wire) ?: return Outcome.REJECTED
            val matches = MessageDigest.isEqual(embedded, pinned)
            embedded.fill(0)
            if (!matches) return Outcome.REJECTED
            when (accept(localId, senderId, packet, pinned, local)) {
                PushEpochStore.WriteResult.Applied -> Outcome.APPLIED
                PushEpochStore.WriteResult.Duplicate -> Outcome.DUPLICATE
                PushEpochStore.WriteResult.Rejected -> Outcome.REJECTED
                PushEpochStore.WriteResult.Full,
                PushEpochStore.WriteResult.Unavailable -> Outcome.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            Outcome.UNAVAILABLE
        } finally {
            pinned.fill(0)
            local.fill(0)
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the sender, the packet or exception text.
            Logger.getLogger("PushEpochImport").warning("Epoch import diagnostics unavailable")
        }
    }

    companion object {
        /** Production wiring: every value is re-read from the live stores. */
        fun of(
            context: Context,
            epochs: PushEpochStore,
            pins: FileExchangePeerStore,
            recordFailure: () -> Unit = {},
        ): PushEpochImport {
            val app = context.applicationContext
            return PushEpochImport(
                localNodeId = {
                    com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(app)
                },
                pinnedPeer = { peerId -> pins.bindingFor(peerId) },
                localBinding = { FileExchangeKeyStore.publicBinding(app) },
                open = { wire -> MessageSealer.open(app, wire) },
                accept = { localId, peerId, packet, pinned, local ->
                    epochs.accept(localId, peerId, packet, true, pinned, pinned, local)
                },
                recordFailure = recordFailure,
            )
        }
    }
}
