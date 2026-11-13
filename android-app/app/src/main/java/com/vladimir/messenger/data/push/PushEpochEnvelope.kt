package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import uniffi.p2p_core.createFileKeyEnvelope

/**
 * Seal only a journal-approved push offer/revoke to the exact previously pinned
 * binding. No plaintext fallback and no network work. Callers still must
 * serialize identity/pin changes, re-read the journal, and revalidate current
 * trust immediately before sending the resulting wire and after any wait.
 * A valid self-signed peer binding is not itself an established pin. JVM
 * strings, including the announcement, cannot be reliably zeroized.
 */
internal class PushEpochEnvelope(
    private val random: SecureRandom = SecureRandom(),
    private val create: (ByteArray, ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = ::createFileKeyEnvelope,
    private val recordFailure: () -> Unit,
) {
    fun seal(
        state: PushRegistrationState.State,
        localBinding: ByteArray,
        pinnedPeerBinding: ByteArray,
        localSecret: ByteArray,
    ): String? {
        if (localBinding.size !in 1..512 || pinnedPeerBinding.size !in 1..512 || localSecret.size != 32) return null
        val text = state.announcement() ?: return null
        val plaintext = text.toByteArray(Charsets.UTF_8)
        val local = localBinding.copyOf()
        val peer = pinnedPeerBinding.copyOf()
        val secret = localSecret.copyOf()
        val expectedLocal = local.copyOf()
        val expectedPeer = peer.copyOf()
        val expectedSecret = secret.copyOf()
        val key = ByteArray(32)
        val keyForFfi = ByteArray(32)
        val iv = ByteArray(12)
        var ciphertext: ByteArray? = null
        var manifest: ByteArray? = null
        var manifestForFfi: ByteArray? = null
        var envelope: ByteArray? = null
        var envelopeSnapshot: ByteArray? = null
        return try {
            if (secret.all { it == 0.toByte() } || local.contentEquals(peer) ||
                PushEpochProtocol.bindingHash(expectedLocal) != state.ownerHash ||
                PushEpochProtocol.bindingHash(expectedPeer) != state.peerHash
            ) return null
            random.nextBytes(key)
            random.nextBytes(iv)
            if (key.all { it == 0.toByte() } || iv.all { it == 0.toByte() }) {
                recordFailure()
                return null
            }
            val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                doFinal(plaintext)
            }
            ciphertext = encrypted
            val expectedManifest = SealedWire.manifest(encrypted)
            manifest = expectedManifest
            val ffiManifest = expectedManifest.copyOf()
            manifestForFfi = ffiManifest
            key.copyInto(keyForFfi)
            val wrapped = create(local, peer, secret, ffiManifest, keyForFfi)
            envelope = wrapped
            // Refuse oversized FFI output before copying or Base64 encoding it.
            if (wrapped.size != expectedLocal.size + 203) {
                recordFailure()
                return null
            }
            val checked = wrapped.copyOf()
            envelopeSnapshot = checked
            if (!MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(peer, expectedPeer) ||
                !MessageDigest.isEqual(secret, expectedSecret) || !MessageDigest.isEqual(ffiManifest, expectedManifest) ||
                !MessageDigest.isEqual(keyForFfi, key) || !PushSealedEnvelopeHeader.matches(checked, expectedLocal, expectedPeer, expectedManifest)
            ) {
                recordFailure()
                return null
            }
            // Encode the checked snapshot, not a buffer retained by the FFI.
            SealedWire.encode(checked, iv, encrypted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Generic diagnostic only: FFI exception text may include private data.
            recordFailure()
            null
        } finally {
            plaintext.fill(0)
            local.fill(0)
            peer.fill(0)
            secret.fill(0)
            expectedLocal.fill(0)
            expectedPeer.fill(0)
            expectedSecret.fill(0)
            key.fill(0)
            keyForFfi.fill(0)
            iv.fill(0)
            ciphertext?.fill(0)
            manifest?.fill(0)
            manifestForFfi?.fill(0)
            envelope?.fill(0)
            envelopeSnapshot?.fill(0)
        }
    }

}
