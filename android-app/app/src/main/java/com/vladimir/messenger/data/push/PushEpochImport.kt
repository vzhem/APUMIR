package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.logging.Logger

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
 */
internal class PushEpochImport(
    private val context: Context,
    private val epochs: PushEpochStore,
    private val pins: FileExchangePeerStore,
    private val open: (Context, String) -> String? = MessageSealer::open,
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
        if (!SealedWire.isSealed(wire)) return Outcome.NOT_APPLICABLE
        // Base64 decoding happens outside the Room/service hot path; the sealed
        // text is all we need to abort early on a sender mismatch.
        if (!MessageSealer.openedByExpectedSender(wire, senderId)) return Outcome.NOT_APPLICABLE
        val localId = runCatching {
            context.applicationContext
                .getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getString("node_id", "")
                .orEmpty()
        }.getOrNull().orEmpty()
        if (localId.isBlank() || localId == senderId) return Outcome.NOT_APPLICABLE

        val pinned = try {
            pins.withPinnedBinding(senderId) { it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } ?: return Outcome.NOT_APPLICABLE

        val local = try {
            FileExchangeKeyStore.publicBinding(context.applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } ?: run {
            pinned.fill(0)
            return Outcome.NOT_APPLICABLE
        }

        return try {
            val plaintext = open(context.applicationContext, wire) ?: return Outcome.NOT_APPLICABLE
            if (!PushEpochProtocol.isPacket(plaintext)) return Outcome.NOT_APPLICABLE
            val packet = PushEpochProtocol.parse(plaintext) ?: return Outcome.REJECTED
            // Re-check the pin against the sender binding actually used by the
            // envelope: the two must be the same key, not merely both valid.
            val embedded = SealedWire.senderBinding(wire)
            if (embedded == null || !MessageDigest.isEqual(embedded, pinned)) {
                embedded?.fill(0)
                return Outcome.REJECTED
            }
            embedded.fill(0)
            when (epochs.accept(localId, senderId, packet, true, pinned, pinned, local)) {
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
}
