package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.file.FileExchangePeerStore
import java.util.concurrent.CancellationException

/**
 * Joins the encrypted registration index, the existing Room pin and the Rust
 * derivation before an experimental generic wake. This still does not provide
 * a complete lifecycle lock: the caller's [localIdentity] must come from the
 * current installation, and profile/key/backup changes and journal mutation
 * must be coordinated with an eventual effect before push can be enabled.
 * In particular, checking a snapshot again after suspension alone is not an
 * ABA-safe guarantee. No Firebase, scheduler, notification or network call is
 * made here.
 */
internal class PushWakeInspector(
    private val journal: PushRegistrationStore,
    private val pins: FileExchangePeerStore,
    private val localIdentity: () -> LocalIdentity?,
    private val recoveryAllowed: () -> PushWakeDrain.Inspection,
    private val crypto: PushWakeCrypto,
    private val recordFailure: () -> Unit,
) {
    /** Owns temporary copies. The creator must clear its own secret separately. */
    class LocalIdentity(val nodeId: String, binding: ByteArray, secret: ByteArray) : AutoCloseable {
        init { require(binding.size in 1..512 && secret.size == 32) }
        internal val binding = binding.copyOf()
        internal val secret = secret.copyOf()
        override fun close() { binding.fill(0); secret.fill(0) }
        override fun toString(): String = "PushWakeLocalIdentity(redacted)"
    }

    /** Null route is exclusively the local provider-overflow recovery marker. */
    suspend fun inspect(route: String?): PushWakeDrain.Inspection {
        try {
            if (route == null) return recoveryAllowed()
            val payload = PushWakePayload.parse(mapOf("v" to "1", "route" to route))
                ?: return PushWakeDrain.Inspection.DROP
            val index = when (val found = journal.findRoute(route)) {
                PushRegistrationStore.RouteResult.Missing -> return PushWakeDrain.Inspection.DROP
                PushRegistrationStore.RouteResult.Unavailable -> return PushWakeDrain.Inspection.RETRY
                is PushRegistrationStore.RouteResult.Found -> found
            }
            return pins.withPinnedBinding(index.peerId) { pin ->
                if (pin == null) return@withPinnedBinding PushWakeDrain.Inspection.DROP
                val local = localIdentity() ?: return@withPinnedBinding PushWakeDrain.Inspection.RETRY
                try {
                    if (local.nodeId != index.localId) return@withPinnedBinding PushWakeDrain.Inspection.DROP
                    val participants = PushRegistrationStore.Participants(local.nodeId, index.peerId, local.binding, pin)
                    val registration = when (val result = journal.read(participants)) {
                        PushRegistrationStore.Result.Missing, PushRegistrationStore.Result.Rejected ->
                            return@withPinnedBinding PushWakeDrain.Inspection.DROP
                        PushRegistrationStore.Result.Full, PushRegistrationStore.Result.Unavailable ->
                            return@withPinnedBinding PushWakeDrain.Inspection.RETRY
                        is PushRegistrationStore.Result.Ready -> result.state
                    }
                    val material = crypto.derive(local.binding, pin, local.secret, registration.epochHex, false)
                        ?: return@withPinnedBinding PushWakeDrain.Inspection.RETRY
                    val derived = try { material.routeHex() } finally { material.close() }
                    if (PushWakeAdmission.isConsistent(payload, index.localId, index.peerId, index.generation,
                            local.nodeId, index.peerId, local.binding, pin, registration, derived)
                    ) PushWakeDrain.Inspection.ALLOW else PushWakeDrain.Inspection.DROP
                } finally { local.close() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The callback reports a generic count only, not route or exception details.
            recordFailure()
            return PushWakeDrain.Inspection.RETRY
        }
    }
}
