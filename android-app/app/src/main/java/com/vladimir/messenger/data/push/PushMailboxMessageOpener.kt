package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import uniffi.p2p_core.fileExchangeBindingNodeId
import uniffi.p2p_core.openFileKeyEnvelope

/**
 * Opens one mailbox envelope only for an independently pinned peer and the
 * caller's current local binding. Rust verifies both signed bindings, the
 * installed recipient identity, sender signature and key envelope. AES-GCM
 * authenticates the wrapped plaintext, which must itself bind the original
 * message id and both node ids. The outside mailbox id remains untrusted.
 *
 * This does not persist, deduplicate, notify, ACK, or perform network I/O. A
 * returned value is NOT proof of delivery. The caller must obtain the pin
 * before reading the envelope and coordinate its identity/pin/restore lifecycle
 * with durable processing; [current] and snapshot checks alone do not stop ABA.
 * FFI internals, Cipher and JVM Strings can retain copies that cannot be wiped.
 */
internal class PushMailboxMessageOpener(
    private val bindingNodeId: (ByteArray) -> String = ::fileExchangeBindingNodeId,
    private val openKey: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = ::openFileKeyEnvelope,
    private val recordFailure: () -> Unit,
) {
    fun open(
        wire: String,
        localBinding: ByteArray,
        localSecret: ByteArray,
        pinnedPeer: ByteArray,
        expectedSender: String,
        expectedRecipient: String,
        current: () -> Boolean,
    ): PushMailboxMessageCodec.Message? {
        if (localBinding.size !in 1..512 || pinnedPeer.size !in 1..512 || localSecret.size != 32 ||
            !PushMailboxMessageCodec.validPeers(expectedSender, expectedRecipient) ||
            !PushMailboxProtocol.validEnvelope(wire)
        ) return null
        val local = localBinding.copyOf()
        val secret = localSecret.copyOf()
        val pin = pinnedPeer.copyOf()
        val expectedLocal = local.copyOf()
        val expectedSecret = secret.copyOf()
        val expectedPin = pin.copyOf()
        var parts: SealedWire.Parts? = null
        var embeddedSender: ByteArray? = null
        var envelopeSnapshot: ByteArray? = null
        var manifest: ByteArray? = null
        var ffiManifest: ByteArray? = null
        var key: ByteArray? = null
        var keySnapshot: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            if (!current() || secret.all { it == 0.toByte() } || MessageDigest.isEqual(local, pin)) return null
            if (bindingNodeId(local) != expectedRecipient || bindingNodeId(pin) != expectedSender ||
                !MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(pin, expectedPin)
            ) return null
            val decoded = SealedWire.decode(wire) ?: return null
            parts = decoded
            val size = ((decoded.keyEnvelope[1].toInt() and 255) shl 8) or (decoded.keyEnvelope[2].toInt() and 255)
            val sender = decoded.keyEnvelope.copyOfRange(3, 3 + size)
            embeddedSender = sender
            if (!MessageDigest.isEqual(sender, expectedPin)) return null
            val expectedEnvelope = decoded.keyEnvelope.copyOf()
            envelopeSnapshot = expectedEnvelope
            val expectedManifest = SealedWire.manifest(decoded.ciphertext)
            manifest = expectedManifest
            val sentManifest = expectedManifest.copyOf()
            ffiManifest = sentManifest
            val openedKey = openKey(decoded.keyEnvelope, local, secret, sentManifest)
            key = openedKey
            if (!MessageDigest.isEqual(decoded.keyEnvelope, expectedEnvelope) ||
                !MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(secret, expectedSecret) ||
                !MessageDigest.isEqual(pin, expectedPin) || !MessageDigest.isEqual(sentManifest, expectedManifest)
            ) {
                recordFailure()
                return null
            }
            if (openedKey.size != 32 || openedKey.all { it == 0.toByte() }) return null
            val checkedKey = openedKey.copyOf()
            keySnapshot = checkedKey
            val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(checkedKey, "AES"), GCMParameterSpec(128, decoded.iv))
                doFinal(decoded.ciphertext)
            }
            plaintext = clear
            if (clear.isEmpty() || clear.size > PushMailboxMessageCodec.MAX_PLAINTEXT_BYTES) return null
            // MessageSealer's general API decodes leniently; mailbox messages
            // require exact UTF-8 so malformed bytes cannot acquire a new meaning.
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear)).toString()
            val message = PushMailboxMessageCodec.parse(text, expectedSender, expectedRecipient) ?: return null
            // An authenticated peer can still send a control or unsupported card.
            // Never hand such a body to the text-commit path.
            if (!PushMailboxTextPolicy.accepts(message.content)) return null
            if (!current() || !MessageDigest.isEqual(localBinding, expectedLocal) ||
                !MessageDigest.isEqual(localSecret, expectedSecret) || !MessageDigest.isEqual(pinnedPeer, expectedPin)
            ) return null
            message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log decrypted text, capability, binding or exception contents.
            recordFailure()
            null
        } finally {
            local.fill(0)
            secret.fill(0)
            pin.fill(0)
            expectedLocal.fill(0)
            expectedSecret.fill(0)
            expectedPin.fill(0)
            parts?.keyEnvelope?.fill(0)
            parts?.iv?.fill(0)
            parts?.ciphertext?.fill(0)
            embeddedSender?.fill(0)
            envelopeSnapshot?.fill(0)
            manifest?.fill(0)
            ffiManifest?.fill(0)
            key?.fill(0)
            keySnapshot?.fill(0)
            plaintext?.fill(0)
        }
    }
}
