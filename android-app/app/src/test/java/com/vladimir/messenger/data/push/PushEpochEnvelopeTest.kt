package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
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

/** Kotlin/AES tests use a fabricated key envelope; real Rust FFI is exercised separately on Android. */
class PushEpochEnvelopeTest {
    private val local = byteArrayOf(2, 4, 6)
    private val peer = byteArrayOf(1, 3, 5)
    private val secret = ByteArray(32) { 4 }
    private val epoch = "09".repeat(16)
    private val device = "56".repeat(16)

    private fun fresh() = PushRegistrationState.fresh(
        PushEpochProtocol.bindingHash(local), PushEpochProtocol.bindingHash(peer), 100, epoch, device,
    )!!
    private fun registered() = fresh().reserve()!!.registered(1, "ab".repeat(32), 1000)!!
    private fun deterministicRandom() = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) { bytes.fill(if (bytes.size == 32) 7 else 8) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    // Structural stand-in only: it has no real signature or wrapped key.
    private fun fakeEnvelope(sender: ByteArray, recipient: ByteArray, manifest: ByteArray): ByteArray {
        val out = ByteArray(sender.size + 203) { 6 }
        out[0] = 1
        out[1] = (sender.size ushr 8).toByte()
        out[2] = sender.size.toByte()
        sender.copyInto(out, 3)
        hash(recipient).copyInto(out, 3 + sender.size)
        hash(manifest).copyInto(out, 3 + sender.size + 32)
        return out
    }

    private fun decrypt(wire: String, key: ByteArray): String {
        val parts = SealedWire.decode(wire)!!
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parts.iv))
            doFinal(parts.ciphertext).toString(Charsets.UTF_8)
        }
    }

    @Test fun offerAndRevokeUseTheExistingEncryptedWireAndClearFfiInputs() {
        val borrowed = mutableListOf<ByteArray>()
        lateinit var keyCopy: ByteArray
        val sealer = PushEpochEnvelope(deterministicRandom(), create = { a, b, s, manifest, key ->
            borrowed.addAll(listOf(a, b, s, manifest, key))
            keyCopy = key.copyOf()
            fakeEnvelope(a, b, manifest)
        }, recordFailure = { error("unexpected failure") })
        val offered = registered()
        val offerWire = sealer.seal(offered, local, peer, secret)!!
        assertTrue(SealedWire.isSealed(offerWire))
        assertFalse(offerWire.contains(offered.announcement()!!))
        assertArrayEquals(local, SealedWire.senderBinding(offerWire)!!)
        assertEquals(offered.announcement(), decrypt(offerWire, keyCopy))
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        keyCopy.fill(0)
        borrowed.clear()
        val revoked = offered.beginRevocation()!!.reserve()!!.revoked(2)!!
        val revokeWire = sealer.seal(revoked, local, peer, secret)!!
        assertEquals(revoked.announcement(), decrypt(revokeWire, keyCopy))
        assertEquals(PushEpochProtocol.Action.REVOKE, PushEpochProtocol.parse(decrypt(revokeWire, keyCopy))!!.action)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        assertArrayEquals(byteArrayOf(2, 4, 6), local)
        assertArrayEquals(byteArrayOf(1, 3, 5), peer)
        assertArrayEquals(ByteArray(32) { 4 }, secret)
        keyCopy.fill(0)
    }

    @Test fun unregisteredStateChangedPinsAndInvalidKeysFailBeforeFfi() {
        var calls = 0
        val sealer = PushEpochEnvelope(deterministicRandom(), create = { a, b, _, manifest, _ ->
            calls++
            fakeEnvelope(a, b, manifest)
        }, recordFailure = { error("unexpected failure") })
        assertNull(sealer.seal(fresh(), local, peer, secret))
        assertNull(sealer.seal(registered(), local, byteArrayOf(9), secret))
        assertNull(sealer.seal(registered(), byteArrayOf(9), peer, secret))
        assertNull(sealer.seal(registered(), local, local, secret))
        assertNull(sealer.seal(registered(), local, peer, ByteArray(32)))
        assertNull(sealer.seal(registered(), local, peer, ByteArray(31) { 1 }))
        assertNull(sealer.seal(registered(), ByteArray(513), peer, secret))
        assertEquals(0, calls)
    }

    @Test fun malformedWrongRecipientAndWrongManifestAreRejected() {
        repeat(7) { kind ->
            var failures = 0
            val sealer = PushEpochEnvelope(deterministicRandom(), create = { a, b, _, manifest, _ ->
                val envelope = fakeEnvelope(a, b, manifest)
                when (kind) {
                    0 -> ByteArray(2_000_000)
                    1 -> envelope.also { it[0] = 2 }
                    2 -> envelope.also { it[2] = 0 }
                    3 -> envelope.also { it[3] = 99 }
                    4 -> envelope.also { it[3 + a.size] = (it[3 + a.size].toInt() xor 1).toByte() }
                    5 -> envelope.also { it[3 + a.size + 32] = (it[3 + a.size + 32].toInt() xor 1).toByte() }
                    else -> envelope.also { it.fill(0, 3 + a.size + 64, 3 + a.size + 88) }
                }
            }, recordFailure = { failures++ })
            assertNull(sealer.seal(registered(), local, peer, secret))
            assertEquals(1, failures)
        }
    }

    @Test fun mutatedFfiInputsAreRejectedEvenWhenTheReturnedHeaderLooksCorrect() {
        repeat(5) { modified ->
            val borrowed = mutableListOf<ByteArray>()
            var failures = 0
            val sealer = PushEpochEnvelope(deterministicRandom(), create = { a, b, s, manifest, key ->
                val envelope = fakeEnvelope(a, b, manifest)
                borrowed.addAll(listOf(a, b, s, manifest, key))
                borrowed[modified][0] = (borrowed[modified][0].toInt() xor 0x40).toByte()
                envelope
            }, recordFailure = { failures++ })
            assertNull(sealer.seal(registered(), local, peer, secret))
            assertEquals(1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
            assertArrayEquals(byteArrayOf(2, 4, 6), local)
            assertArrayEquals(byteArrayOf(1, 3, 5), peer)
            assertArrayEquals(ByteArray(32) { 4 }, secret)
        }
    }

    @Test fun zeroEntropyDoesNotReachFfi() {
        repeat(2) { badCall ->
            var calls = 0
            var failures = 0
            val random = object : SecureRandom() {
                var index = 0
                override fun nextBytes(bytes: ByteArray) { bytes.fill(if (index++ == badCall) 0 else 7) }
            }
            val sealer = PushEpochEnvelope(random, create = { _, _, _, _, _ -> calls++; ByteArray(1) },
                recordFailure = { failures++ })
            assertNull(sealer.seal(registered(), local, peer, secret))
            assertEquals(0, calls)
            assertEquals(1, failures)
        }
    }

    @Test fun ffiFailuresAndCancellationWipeBorrowedBuffers() {
        for (cancel in listOf(false, true)) {
            val borrowed = mutableListOf<ByteArray>()
            var failures = 0
            val sealer = PushEpochEnvelope(deterministicRandom(), create = { a, b, s, manifest, key ->
                borrowed.addAll(listOf(a, b, s, manifest, key))
                if (cancel) throw CancellationException("stop") else throw IOException("sensitive")
            }, recordFailure = { failures++ })
            val result = runCatching { sealer.seal(registered(), local, peer, secret) }
            if (cancel) assertTrue(result.exceptionOrNull() is CancellationException) else assertNull(result.getOrThrow())
            assertEquals(if (cancel) 0 else 1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        }
    }
}
