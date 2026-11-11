package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses real AES-GCM but fake Rust binding/unwrap functions, not production authentication. */
class PushMailboxMessageOpenerTest {
    private val id = "01234567-89ab-4cde-8f01-23456789abcd"
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)
    private val local = byteArrayOf(2, 4, 6)
    private val pin = byteArrayOf(1, 3, 5)
    private val secret = ByteArray(32) { 4 }
    private val key = ByteArray(32) { 7 }
    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val content = "Mailbox payload | привет 😀"

    private fun plaintext(sender: String = this.sender, recipient: String = this.recipient) =
        PushMailboxMessageCodec.encode(id, sender, recipient, 1_700_000_000_000L, content)!!

    /** Structurally valid fake key envelope; its signature is deliberately NOT real. */
    private fun envelope(clear: ByteArray = plaintext().toByteArray(Charsets.UTF_8), senderBinding: ByteArray = pin): String {
        val header = ByteArray(senderBinding.size + 203) { 1 }
        header[0] = 1
        header[1] = (senderBinding.size shr 8).toByte()
        header[2] = senderBinding.size.toByte()
        senderBinding.copyInto(header, 3)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, this@PushMailboxMessageOpenerTest.iv))
            doFinal(clear)
        }
        return SealedWire.encode(header, iv, ciphertext)
    }

    private fun nodes(binding: ByteArray): String = when (binding.firstOrNull()) {
        2.toByte() -> recipient
        1.toByte() -> sender
        else -> error("invalid fake binding")
    }

    private fun opener(
        nodes: (ByteArray) -> String = ::nodes,
        unwrap: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = { _, _, _, _ -> key.copyOf() },
        failures: () -> Unit = { error("unexpected failure") },
    ) = PushMailboxMessageOpener(nodes, unwrap, failures)

    @Test fun validOpenedMessageIsBoundToThePinnedSenderLocalRecipientAndOriginalId() {
        val borrowed = mutableListOf<ByteArray>()
        lateinit var output: ByteArray
        val client = opener(unwrap = { header, binding, usedSecret, manifest ->
            assertArrayEquals(local, binding)
            assertArrayEquals(secret, usedSecret)
            assertTrue(manifest.toString(Charsets.US_ASCII).startsWith("apu-msg-v1|"))
            borrowed.addAll(listOf(header, binding, usedSecret, manifest))
            key.copyOf().also { output = it }
        })
        val message = client.open(envelope(), local, secret, pin, sender, recipient) { true }!!
        assertEquals(id, message.id)
        assertEquals(sender, message.sender)
        assertEquals(recipient, message.recipient)
        assertEquals(content, message.content)
        assertFalse(message.toString().contains(content))
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        assertTrue(output.all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(2, 4, 6), local)
        assertArrayEquals(byteArrayOf(1, 3, 5), pin)
        assertTrue(secret.all { it == 4.toByte() })
    }

    @Test fun missingWrongOrSelfPinsAndWrongNodesNeverUnwrapAKey() {
        var calls = 0
        val client = opener(unwrap = { _, _, _, _ -> calls++; key.copyOf() })
        val wire = envelope()
        assertNull(client.open(wire, local, secret, byteArrayOf(), sender, recipient) { true })
        assertNull(client.open(wire, local, secret, byteArrayOf(1, 3, 6), sender, recipient) { true })
        assertNull(client.open(wire, local, secret, local, sender, recipient) { true })
        assertNull(client.open(wire, local, secret, pin, recipient, sender) { true })
        assertNull(client.open("ordinary plaintext", local, secret, pin, sender, recipient) { true })
        assertNull(client.open(wire, local, ByteArray(32), pin, sender, recipient) { true })
        assertNull(client.open(wire, local, secret, pin, sender, recipient) { false })
        assertEquals(0, calls)
    }

    @Test fun ciphertextAndUtf8TamperingAndWrongBoundMetadataAreRejected() {
        var failures = 0
        val client = opener(failures = { failures++ })
        val parts = SealedWire.decode(envelope())!!
        parts.ciphertext[0] = (parts.ciphertext[0].toInt() xor 1).toByte()
        val altered = SealedWire.encode(parts.keyEnvelope, parts.iv, parts.ciphertext)
        assertNull(client.open(altered, local, secret, pin, sender, recipient) { true })
        assertNull(client.open(envelope(byteArrayOf(0xc3.toByte(), 0x28)), local, secret, pin, sender, recipient) { true })
        val other = "pk_" + "3".repeat(32)
        assertNull(client.open(envelope(plaintext(sender = other).toByteArray()), local, secret, pin, sender, recipient) { true })
        assertNull(client.open(envelope(plaintext(recipient = other).toByteArray()), local, secret, pin, sender, recipient) { true })
        assertEquals(2, failures) // GCM and strict UTF-8; valid but mismatched metadata is a rejection.
    }

    @Test fun alteredBindingIdentityFfiCannotRelabelTheEnvelope() {
        val borrowed = mutableListOf<ByteArray>()
        var unwraps = 0
        val client = opener(nodes = { binding ->
            borrowed.add(binding)
            val id = nodes(binding)
            binding[1] = (binding[1].toInt() xor 1).toByte()
            id
        }, unwrap = { _, _, _, _ -> unwraps++; key.copyOf() })
        assertNull(client.open(envelope(), local, secret, pin, sender, recipient) { true })
        assertEquals(0, unwraps)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test fun changedFfiInputsFailClosedAndAllBorrowedBuffersAreCleared() {
        repeat(4) { changed ->
            val borrowed = mutableListOf<ByteArray>()
            lateinit var output: ByteArray
            var failures = 0
            val client = opener(unwrap = { header, binding, usedSecret, manifest ->
                borrowed.addAll(listOf(header, binding, usedSecret, manifest))
                val target = borrowed[changed]
                target[0] = (target[0].toInt() xor 1).toByte()
                key.copyOf().also { output = it }
            }, failures = { failures++ })
            assertNull(client.open(envelope(), local, secret, pin, sender, recipient) { true })
            assertEquals(1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
            assertTrue(output.all { it == 0.toByte() })
        }
    }

    @Test fun changedCallerSnapshotsOrCurrentStateCannotReturnPlaintext() {
        repeat(3) { changed ->
            val callerLocal = local.copyOf()
            val callerSecret = secret.copyOf()
            val callerPin = pin.copyOf()
            val client = opener(unwrap = { _, _, _, _ ->
                val target = when (changed) { 0 -> callerLocal; 1 -> callerSecret; else -> callerPin }
                target[0] = (target[0].toInt() xor 1).toByte()
                key.copyOf()
            })
            assertNull(client.open(envelope(), callerLocal, callerSecret, callerPin, sender, recipient) { true })
        }
        var currentCalls = 0
        val client = opener()
        assertNull(client.open(envelope(), local, secret, pin, sender, recipient) { ++currentCalls == 1 })
        assertEquals(2, currentCalls)
    }

    @Test fun invalidKeyExceptionsAndCancellationNeverProduceAMessage() {
        var mode = 0
        var failures = 0
        val borrowed = mutableListOf<ByteArray>()
        val client = opener(unwrap = { header, binding, usedSecret, manifest ->
            borrowed.addAll(listOf(header, binding, usedSecret, manifest))
            when (mode++) {
                0 -> ByteArray(31) { 1 }
                1 -> ByteArray(32)
                2 -> throw IOException("secret details")
                else -> throw CancellationException("secret details")
            }
        }, failures = { failures++ })
        repeat(3) { assertNull(client.open(envelope(), local, secret, pin, sender, recipient) { true }) }
        assertEquals(1, failures)
        val exception = runCatching { client.open(envelope(), local, secret, pin, sender, recipient) { true } }.exceptionOrNull()
        assertTrue(exception is CancellationException)
        assertEquals(1, failures)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
    }
}
