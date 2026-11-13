package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Supplies the `current`/`stable` predicate that [PushMailboxDrain] and
 * [PushMailboxIncoming] already require but nothing implemented yet: a snapshot
 * of the live local binding, pinned peer binding and local exchange secret that
 * is re-read from the database/identity for every question.
 *
 * A pass is abandoned as soon as any of the three values changes, as soon as
 * the store cannot be read, or as soon as any value is structurally invalid.
 * This is deliberately a *fail-closed snapshot check*, not a lock: it cannot
 * stop an identity/pin/restore change that happens between two reads, and it
 * does not serialize writers. `docs/REMOTE_PUSH_DESIGN.md` still requires an
 * ABA-safe lifecycle boundary before this may be connected to a live receive
 * path.
 *
 * Nothing here performs I/O; [read] is supplied by the caller and must return
 * fresh, caller-owned arrays on every call (it is invoked from the drain
 * thread, so it must stay cheap and non-blocking).
 */
internal class PushDrainTrustGate(
    private val read: () -> Snapshot?,
) {
    /**
     * Copied trust material for one pass. Not a data class on purpose: the
     * generated `toString` would print binding bytes and the secret.
     */
    class Snapshot(
        val senderId: String,
        val recipientId: String,
        val localBinding: ByteArray,
        val pinnedPeerBinding: ByteArray,
        val localSecret: ByteArray,
    ) {
        override fun toString(): String = "PushDrainTrust(redacted)"
    }

    fun interface Stable {
        /** False means: stop. Do not ACK, do not write, do not retry blindly. */
        fun stable(): Boolean
    }

    /**
     * Starts one pass. Returns null when the baseline cannot be read or is
     * structurally invalid, in which case the caller must not start a network
     * or storage operation at all.
     */
    fun start(): Pass? {
        val baseline = try {
            read() ?: return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (!valid(baseline)) {
            wipe(baseline)
            return null
        }
        return Pass(baseline)
    }

    /** One bounded pass. Not thread-safe; create one per pass. */
    inner class Pass internal constructor(private val baseline: Snapshot) : Stable, AutoCloseable {
        private var failed = false

        override fun stable(): Boolean {
            if (failed) return false
            val current = try {
                // Re-read for EVERY question: a cached snapshot would make this
                // check vacuous.
                read()
            } catch (_: Exception) {
                null
            }
            if (current == null) {
                failed = true
                return false
            }
            val same = try {
                valid(current) && same(baseline, current)
            } finally {
                wipe(current)
            }
            if (!same) failed = true
            return same
        }

        /** Wipes the baseline; safe to call repeatedly. */
        override fun close() = wipe(baseline)
    }

    private fun valid(snapshot: Snapshot): Boolean =
        NODE.matches(snapshot.senderId) && NODE.matches(snapshot.recipientId) &&
            snapshot.senderId != snapshot.recipientId &&
            snapshot.localBinding.size in 1..MAX_BINDING_BYTES &&
            snapshot.pinnedPeerBinding.size in 1..MAX_BINDING_BYTES &&
            snapshot.localSecret.size == SECRET_BYTES && snapshot.localSecret.any { it != 0.toByte() } &&
            !MessageDigest.isEqual(snapshot.localBinding, snapshot.pinnedPeerBinding)

    private fun same(a: Snapshot, b: Snapshot): Boolean =
        a.senderId == b.senderId && a.recipientId == b.recipientId &&
            MessageDigest.isEqual(a.localBinding, b.localBinding) &&
            MessageDigest.isEqual(a.pinnedPeerBinding, b.pinnedPeerBinding) &&
            MessageDigest.isEqual(a.localSecret, b.localSecret)

    private companion object {
        const val MAX_BINDING_BYTES = 512
        const val SECRET_BYTES = 32
        val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

        fun wipe(snapshot: Snapshot) {
            snapshot.localBinding.fill(0)
            snapshot.pinnedPeerBinding.fill(0)
            snapshot.localSecret.fill(0)
        }
    }
}
