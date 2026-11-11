package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import uniffi.p2p_core.createFileKeyEnvelope
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * Prepares one encrypted, authenticated mailbox message for the exact pin the
 * caller independently obtained. Rust verifies both bindings, the installed
 * sender identity and the sender secret; the original id and participants are
 * inside the ciphertext, not trusted to the server's outside metadata.
 *
 * This is intentionally not connected to an outbox or network. The caller MUST
 * persist the resulting wire AND a fresh outside mailbox id before its first
 * put, retry identical bytes, and stop retrying at the original expiry. It must
 * also coordinate pin, identity, restore, epoch and message mutations; [current]
 * and array snapshots alone do not prevent ABA. Neither successful sealing nor
 * server acceptance proves delivery. JVM strings and FFI/Cipher internals may
 * retain copies of plaintext or key material that cannot be reliably wiped.
 */
internal class PushMailboxMessageSealer(
    private val random: SecureRandom = SecureRandom(),
    private val bindingNodeId: (ByteArray) -> String = ::fileExchangeBindingNodeId,
    private val createKey: (ByteArray, ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = ::createFileKeyEnvelope,
    private val recordFailure: () -> Unit,
) {
    fun seal(
        id: String,
        sender: String,
        recipient: String,
        sentAtMs: Long,
        content: String,
        localBinding: ByteArray,
        pinnedPeer: ByteArray,
        localSecret: ByteArray,
        current: () -> Boolean,
    ): String? {
        if (localBinding.size !in 1..512 || pinnedPeer.size !in 1..512 || localSecret.size != 32) return null
        val text = PushMailboxMessageCodec.encode(id, sender, recipient, sentAtMs, content) ?: return null
        val plaintext = text.toByteArray(Charsets.UTF_8)
        val local = localBinding.copyOf()
        val peer = pinnedPeer.copyOf()
        val secret = localSecret.copyOf()
        val expectedLocal = local.copyOf()
        val expectedPeer = peer.copyOf()
        val expectedSecret = secret.copyOf()
        val key = ByteArray(32)
        val ffiKey = ByteArray(32)
        val iv = ByteArray(12)
        var ciphertext: ByteArray? = null
        var manifest: ByteArray? = null
        var ffiManifest: ByteArray? = null
        var envelope: ByteArray? = null
        var envelopeSnapshot: ByteArray? = null
        return try {
            if (!current() || secret.all { it == 0.toByte() } || MessageDigest.isEqual(local, peer)) return null
            if (bindingNodeId(local) != sender || bindingNodeId(peer) != recipient ||
                !MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(peer, expectedPeer)
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
            val sentManifest = expectedManifest.copyOf()
            ffiManifest = sentManifest
            key.copyInto(ffiKey)
            val wrapped = createKey(local, peer, secret, sentManifest, ffiKey)
            envelope = wrapped
            if (wrapped.size != expectedLocal.size + 203) {
                recordFailure()
                return null
            }
            // Verify/encode a copy rather than an FFI-retained buffer.
            val checked = wrapped.copyOf()
            envelopeSnapshot = checked
            if (!MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(peer, expectedPeer) ||
                !MessageDigest.isEqual(secret, expectedSecret) || !MessageDigest.isEqual(sentManifest, expectedManifest) ||
                !MessageDigest.isEqual(ffiKey, key) || !PushSealedEnvelopeHeader.matches(checked, expectedLocal, expectedPeer, expectedManifest)
            ) {
                recordFailure()
                return null
            }
            val wire = SealedWire.encode(checked, iv, encrypted)
            if (!PushMailboxProtocol.validEnvelope(wire) || !current() ||
                !MessageDigest.isEqual(localBinding, expectedLocal) || !MessageDigest.isEqual(pinnedPeer, expectedPeer) ||
                !MessageDigest.isEqual(localSecret, expectedSecret)
            ) return null
            wire
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The exception and its message may contain private data.
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
            ffiKey.fill(0)
            iv.fill(0)
            ciphertext?.fill(0)
            manifest?.fill(0)
            ffiManifest?.fill(0)
            envelope?.fill(0)
            envelopeSnapshot?.fill(0)
        }
    }
}
