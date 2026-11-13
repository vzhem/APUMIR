package com.vladimir.messenger.data.push

import java.security.MessageDigest

/**
 * Structural sanity check of output from the local Rust envelope creator.
 * This does NOT authenticate an envelope; the recipient must use the Rust
 * verifier and an independently established pin before accepting it.
 */
internal object PushSealedEnvelopeHeader {
    fun matches(envelope: ByteArray, local: ByteArray, peer: ByteArray, manifest: ByteArray): Boolean {
        // Rust FileKeyEnvelopeV1 is exactly 203 bytes plus the sender binding.
        if (local.size !in 1..512 || envelope.size != local.size + 203 || envelope[0] != 1.toByte()) return false
        val bindingSize = ((envelope[1].toInt() and 255) shl 8) or (envelope[2].toInt() and 255)
        if (bindingSize != local.size) return false
        val end = 3 + bindingSize
        val sender = envelope.copyOfRange(3, end)
        val recipientHash = envelope.copyOfRange(end, end + 32)
        val manifestHash = envelope.copyOfRange(end + 32, end + 64)
        val expectedRecipient = MessageDigest.getInstance("SHA-256").digest(peer)
        val expectedManifest = MessageDigest.getInstance("SHA-256").digest(manifest)
        return try {
            MessageDigest.isEqual(sender, local) && MessageDigest.isEqual(recipientHash, expectedRecipient) &&
                MessageDigest.isEqual(manifestHash, expectedManifest) &&
                (end + 64 until end + 88).any { envelope[it] != 0.toByte() }
        } finally {
            sender.fill(0)
            recipientHash.fill(0)
            manifestHash.fill(0)
            expectedRecipient.fill(0)
            expectedManifest.fill(0)
        }
    }
}
