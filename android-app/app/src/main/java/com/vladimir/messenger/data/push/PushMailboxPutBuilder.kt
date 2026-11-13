package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Builds, but never sends or persists, a put for the sender's currently offered
 * epoch. The caller must load [state] from the verified journal and obtain the
 * peer binding independently from its existing pin. The envelope header must
 * structurally match those bindings and its ciphertext, but is not authenticated
 * here. Only a durable, immutable ciphertext/id/cutoff triple may be submitted;
 * this class cannot prove it was saved. [current] is a preflight, not
 * identity/pin/restore synchronization.
 */
internal class PushMailboxPutBuilder(private val recordFailure: () -> Unit) {
    fun build(
        owner: ByteArray,
        capability: ByteArray,
        localHash: String,
        peerHash: String,
        epochHash: ByteArray,
        materialCurrent: () -> Boolean,
        current: () -> Boolean,
        localBinding: ByteArray,
        pinnedPeer: ByteArray,
        state: PushEpochProtocol.State,
        id: String,
        expiresAt: Long,
        envelope: String,
    ): PushMailboxPutRequest? {
        if (owner.size != 32 || capability.size != 32 || epochHash.size != 32 ||
            localBinding.size !in 1..512 || pinnedPeer.size !in 1..512 ||
            state.action != PushEpochProtocol.Action.OFFER || state.ownerBindingHash != peerHash ||
            state.targetBindingHash != localHash || state.epochHex == null
        ) return null
        val epoch = decodeEpoch(state.epochHex) ?: return null
        val local = localBinding.copyOf()
        val pin = pinnedPeer.copyOf()
        val ownerSnapshot = owner.copyOf()
        val capabilitySnapshot = capability.copyOf()
        val epochHashSnapshot = epochHash.copyOf()
        return try {
            if (!materialCurrent() || !current() || ownerSnapshot.all { it == 0.toByte() } ||
                capabilitySnapshot.all { it == 0.toByte() } || local.contentEquals(pin) ||
                PushEpochProtocol.bindingHash(local) != localHash || PushEpochProtocol.bindingHash(pin) != peerHash
            ) return null
            val actualEpochHash = MessageDigest.getInstance("SHA-256").digest(epoch)
            val matchingEpoch = try { MessageDigest.isEqual(actualEpochHash, epochHashSnapshot) }
                finally { actualEpochHash.fill(0) }
            if (!matchingEpoch) return null
            val request = PushMailboxPutRequest(hex(ownerSnapshot), hex(capabilitySnapshot), id, expiresAt, envelope)
            if (!request.isValid() || !matchesEnvelope(envelope, local, pin) ||
                !materialCurrent() || !current() || !materialCurrent() ||
                !MessageDigest.isEqual(localBinding, local) || !MessageDigest.isEqual(pinnedPeer, pin) ||
                !MessageDigest.isEqual(owner, ownerSnapshot) || !MessageDigest.isEqual(capability, capabilitySnapshot) ||
                !MessageDigest.isEqual(epochHash, epochHashSnapshot)
            ) return null
            request
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log capabilities, ciphertext, bindings or exception text.
            recordFailure()
            null
        } finally {
            epoch.fill(0)
            local.fill(0)
            pin.fill(0)
            ownerSnapshot.fill(0)
            capabilitySnapshot.fill(0)
            epochHashSnapshot.fill(0)
        }
    }

    /** Structural sender/recipient/manifest check only, not signature verification. */
    private fun matchesEnvelope(wire: String, sender: ByteArray, recipient: ByteArray): Boolean {
        // request.isValid() has already required a bounded canonical wire.
        val parts = SealedWire.decode(wire) ?: return false
        val manifest = SealedWire.manifest(parts.ciphertext)
        return try {
            PushSealedEnvelopeHeader.matches(parts.keyEnvelope, sender, recipient, manifest)
        } finally {
            parts.keyEnvelope.fill(0)
            parts.iv.fill(0)
            parts.ciphertext.fill(0)
            manifest.fill(0)
        }
    }

    private fun decodeEpoch(value: String): ByteArray? {
        if (value.length != 32 || value.any { it !in '0'..'9' && it !in 'a'..'f' } || value.all { it == '0' }) return null
        return ByteArray(16) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
}
