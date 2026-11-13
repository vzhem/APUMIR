package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Adapts the restricted Rust mailbox signer to HTTP request types. Inputs must
 * be the current device journal and a previously established peer pin; this
 * component does not obtain or authenticate either. The caller must coordinate
 * identity, pin and restore changes with the eventual network effect: comparing
 * snapshots before/after a wait alone does not rule out ABA. No I/O occurs here.
 */
internal class PushMailboxAccessSigner(
    private val signer: (ByteArray, ByteArray, ByteArray, ULong, ByteArray, ByteArray, Boolean) -> ByteArray,
    private val recordFailure: () -> Unit,
) {
    fun pull(
        owner: ByteArray, capability: ByteArray, localHash: String, peerHash: String, epochHash: ByteArray,
        current: () -> Boolean, localBinding: ByteArray, pinnedPeer: ByteArray,
        state: PushRegistrationState.State, issuedAt: Long, after: String,
    ): PushMailboxPullRequest? {
        if (after != "-" && !PushMailboxProtocol.validId(after)) return null
        val cursor = if (after == "-") byteArrayOf() else decodeHex(after, 16) ?: return null
        return try {
            val signed = sign(owner, capability, localHash, peerHash, epochHash, current, localBinding, pinnedPeer,
                state, issuedAt, cursor, byteArrayOf(), false) ?: return null
            PushMailboxPullRequest(signed.owner, signed.capability, state.deviceHex, issuedAt, after, signed.signature)
                .takeIf { it.isValid() }
        } finally { cursor.fill(0) }
    }

    fun ack(
        owner: ByteArray, capability: ByteArray, localHash: String, peerHash: String, epochHash: ByteArray,
        current: () -> Boolean, localBinding: ByteArray, pinnedPeer: ByteArray,
        state: PushRegistrationState.State, issuedAt: Long, receipts: List<PushMailboxReceipt>,
    ): PushMailboxAckRequest? {
        if (receipts.size !in 1..PushMailboxProtocol.MAX_ACK_ITEMS) return null
        val selected = receipts.toList()
        if (selected.any { !it.isValid() } || selected.zipWithNext().any { (first, next) -> first.id >= next.id }) return null
        val packed = ByteArray(selected.size * 32)
        return try {
            selected.forEachIndexed { index, item ->
                val id = decodeHex(item.id, 16) ?: return null
                val receipt = decodeHex(item.receipt, 16) ?: run { id.fill(0); return null }
                try {
                    id.copyInto(packed, index * 32)
                    receipt.copyInto(packed, index * 32 + 16)
                } finally { id.fill(0); receipt.fill(0) }
            }
            val signed = sign(owner, capability, localHash, peerHash, epochHash, current, localBinding, pinnedPeer,
                state, issuedAt, byteArrayOf(), packed, true) ?: return null
            PushMailboxAckRequest(signed.owner, signed.capability, state.deviceHex, issuedAt, selected, signed.signature)
                .takeIf { it.isValid() }
        } finally { packed.fill(0) }
    }

    private class Signed(val owner: String, val capability: String, val signature: String) {
        override fun toString(): String = "PushMailboxSigned(redacted)"
    }

    private fun sign(
        owner: ByteArray, capability: ByteArray, localHash: String, peerHash: String, epochHash: ByteArray,
        current: () -> Boolean, localBinding: ByteArray, pinnedPeer: ByteArray,
        state: PushRegistrationState.State, issuedAt: Long, after: ByteArray, items: ByteArray, acknowledge: Boolean,
    ): Signed? {
        if (!current() || owner.size != 32 || capability.size != 32 || epochHash.size != 32 ||
            localBinding.size !in 1..512 || pinnedPeer.size !in 1..512 ||
            state.phase != PushRegistrationState.Phase.ACTIVE || state.registeredAtMs <= 0 ||
            state.ownerHash != localHash || state.peerHash != peerHash || !PushMailboxProtocol.validTimestamp(issuedAt) ||
            !PushMailboxProtocol.validId(state.deviceHex)
        ) return null
        val epoch = decodeHex(state.epochHex, 16) ?: return null
        val device = decodeHex(state.deviceHex, 16) ?: run { epoch.fill(0); return null }
        val binding = localBinding.copyOf()
        val pin = pinnedPeer.copyOf()
        val cap = capability.copyOf()
        val ownerSnapshot = owner.copyOf()
        val expectedEpochHash = epochHash.copyOf()
        val expectedBinding = binding.copyOf()
        val expectedPin = pin.copyOf()
        val expectedCap = cap.copyOf()
        val expectedDevice = device.copyOf()
        val cursor = after.copyOf()
        val packed = items.copyOf()
        val expectedCursor = cursor.copyOf()
        val expectedPacked = packed.copyOf()
        var output: ByteArray? = null
        var outputSnapshot: ByteArray? = null
        return try {
            if (ownerSnapshot.all { it == 0.toByte() } || expectedCap.all { it == 0.toByte() } ||
                expectedBinding.contentEquals(expectedPin) || PushEpochProtocol.bindingHash(expectedBinding) != localHash ||
                PushEpochProtocol.bindingHash(expectedPin) != peerHash
            ) return null
            val actualEpochHash = MessageDigest.getInstance("SHA-256").digest(epoch)
            val sameEpoch = try { MessageDigest.isEqual(actualEpochHash, expectedEpochHash) } finally { actualEpochHash.fill(0) }
            if (!sameEpoch) return null
            output = signer(binding, cap, device, issuedAt.toULong(), cursor, packed, acknowledge)
            if (!MessageDigest.isEqual(binding, expectedBinding) || !MessageDigest.isEqual(pin, expectedPin) ||
                !MessageDigest.isEqual(cap, expectedCap) || !MessageDigest.isEqual(device, expectedDevice) ||
                !MessageDigest.isEqual(cursor, expectedCursor) || !MessageDigest.isEqual(packed, expectedPacked)
            ) {
                recordFailure()
                return null
            }
            // Also reject an ordinary change to the caller-owned arrays while
            // FFI ran. An ABA change can still restore the same bytes, so this
            // is not a substitute for identity/pin/restore coordination.
            if (!current() || !MessageDigest.isEqual(localBinding, expectedBinding) ||
                !MessageDigest.isEqual(pinnedPeer, expectedPin) || !MessageDigest.isEqual(owner, ownerSnapshot) ||
                !MessageDigest.isEqual(capability, expectedCap) || !MessageDigest.isEqual(epochHash, expectedEpochHash) ||
                output.size != 96
            ) return null
            val checked = output.copyOf()
            outputSnapshot = checked
            val signedOwner = checked.copyOfRange(0, 32)
            val signature = checked.copyOfRange(32, 96)
            try {
                if (!MessageDigest.isEqual(signedOwner, ownerSnapshot) || signature.all { it == 0.toByte() }) return null
                Signed(hex(ownerSnapshot), hex(expectedCap), hex(signature))
            } finally { signedOwner.fill(0); signature.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // FFI errors can contain sensitive inputs; retain only a generic count.
            recordFailure()
            null
        } finally {
            epoch.fill(0)
            device.fill(0)
            binding.fill(0)
            pin.fill(0)
            cap.fill(0)
            ownerSnapshot.fill(0)
            expectedEpochHash.fill(0)
            expectedBinding.fill(0)
            expectedPin.fill(0)
            expectedCap.fill(0)
            expectedDevice.fill(0)
            cursor.fill(0)
            packed.fill(0)
            expectedCursor.fill(0)
            expectedPacked.fill(0)
            output?.fill(0)
            outputSnapshot?.fill(0)
        }
    }

    private fun decodeHex(value: String, length: Int): ByteArray? {
        if (value.length != length * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(length) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
}
