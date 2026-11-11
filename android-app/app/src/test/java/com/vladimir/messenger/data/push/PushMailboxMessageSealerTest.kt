package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real AES-GCM with fake FFI bindings and envelopes: this does not test Rust signatures. */
class PushMailboxMessageSealerTest {
    private val id = "01234567-89ab-4cde-8f01-23456789abcd"
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)
    private val local = byteArrayOf(1, 3, 5)
    private val peer = byteArrayOf(2, 4, 6)
    private val secret = ByteArray(32) { 4 }
    private val body = "Оригинальное сообщение | 😀"
    private val time = 1_700_000_000_000L

    private fun nodes(value: ByteArray): String = when (value.firstOrNull()) {
        1.toByte() -> sender
        2.toByte() -> recipient
        else -> error("invalid fake binding")
    }
    private fun deterministicRandom() = object : SecureRandom() {
        var calls = 0
        override fun nextBytes(bytes: ByteArray) { bytes.fill((7 + calls++).toByte()) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    // Structurally valid stand-in: no signature and no real wrapped key.
    private fun fakeEnvelope(senderBinding: ByteArray, recipientBinding: ByteArray, manifest: ByteArray): ByteArray {
        val out = ByteArray(senderBinding.size + 203) { 6 }
        out[0] = 1
        out[1] = (senderBinding.size ushr 8).toByte()
        out[2] = senderBinding.size.toByte()
        senderBinding.copyInto(out, 3)
        hash(recipientBinding).copyInto(out, 3 + senderBinding.size)
        hash(manifest).copyInto(out, 3 + senderBinding.size + 32)
        return out
    }
    private fun sealer(
        random: SecureRandom = deterministicRandom(),
        nodes: (ByteArray) -> String = ::nodes,
        create: (ByteArray, ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = { a, b, _, m, _ -> fakeEnvelope(a, b, m) },
        failures: () -> Unit = { error("unexpected failure") },
    ) = PushMailboxMessageSealer(random, nodes, create, failures)
    private fun PushMailboxMessageSealer.sealMessage(
        local: ByteArray = this@PushMailboxMessageSealerTest.local,
        peer: ByteArray = this@PushMailboxMessageSealerTest.peer,
        secret: ByteArray = this@PushMailboxMessageSealerTest.secret,
        current: () -> Boolean = { true },
    ) = seal(id, sender, recipient, time, body, local, peer, secret, current)

    @Test fun originalMessageRoundTripsInsideAnAuthenticatedEnvelopeAndBuffersAreCleared() {
        val borrowed = mutableListOf<ByteArray>()
        lateinit var capturedKey: ByteArray
        lateinit var returned: ByteArray
        val sealer = sealer(create = { a, b, s, manifest, key ->
            borrowed.addAll(listOf(a, b, s, manifest, key))
            capturedKey = key.copyOf()
            fakeEnvelope(a, b, manifest).also { returned = it }
        })
        val wire = sealer.sealMessage()!!
        assertTrue(PushMailboxProtocol.validEnvelope(wire))
        assertFalse(wire.contains(body))
        assertFalse(wire.contains(id))
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        assertTrue(returned.all { it == 0.toByte() })
        val opener = PushMailboxMessageOpener(::nodes, { _, _, _, _ -> capturedKey.copyOf() }, { error("unexpected failure") })
        val opened = opener.open(wire, peer, ByteArray(32) { 8 }, local, sender, recipient) { true }!!
        assertEquals(id, opened.id)
        assertEquals(sender, opened.sender)
        assertEquals(recipient, opened.recipient)
        assertEquals(time, opened.sentAtMs)
        assertEquals(body, opened.content)
        capturedKey.fill(0)
        assertArrayEquals(byteArrayOf(1, 3, 5), local)
        assertArrayEquals(byteArrayOf(2, 4, 6), peer)
        assertArrayEquals(ByteArray(32) { 4 }, secret)
    }

    @Test fun invalidInputsPeersAndStateNeverInvokeKeyCreation() {
        var creates = 0
        val sealer = sealer(create = { a, b, _, m, _ -> creates++; fakeEnvelope(a, b, m) })
        assertNull(sealer.seal("bad", sender, recipient, time, body, local, peer, secret) { true })
        assertNull(sealer.seal(id, sender, sender, time, body, local, peer, secret) { true })
        assertNull(sealer.seal(id, sender, recipient, time, "", local, peer, secret) { true })
        assertNull(sealer.seal(id, recipient, sender, time, body, local, peer, secret) { true })
        assertNull(sealer.sealMessage(peer = byteArrayOf()))
        assertNull(sealer.sealMessage(peer = local))
        assertNull(sealer.sealMessage(secret = ByteArray(32)))
        assertNull(sealer.sealMessage(secret = ByteArray(31)))
        assertNull(sealer.sealMessage(current = { false }))
        assertEquals(0, creates)
    }

    @Test fun changingFfiInputsOrReturningMalformedHeadersFailsClosed() {
        repeat(5) { changed ->
            val borrowed = mutableListOf<ByteArray>()
            lateinit var output: ByteArray
            var failures = 0
            val sealer = sealer(create = { a, b, s, manifest, key ->
                val result = fakeEnvelope(a, b, manifest)
                borrowed.addAll(listOf(a, b, s, manifest, key))
                borrowed[changed][0] = (borrowed[changed][0].toInt() xor 0x40).toByte()
                result.also { output = it }
            }, failures = { failures++ })
            assertNull(sealer.sealMessage())
            assertEquals(1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
            assertTrue(output.all { it == 0.toByte() })
        }
        repeat(7) { kind ->
            var failures = 0
            val sealer = sealer(create = { a, b, _, m, _ ->
                val value = fakeEnvelope(a, b, m)
                when (kind) {
                    0 -> ByteArray(900)
                    1 -> value.also { it[0] = 2 }
                    2 -> value.also { it[2] = 0 }
                    3 -> value.also { it[3] = 99 }
                    4 -> value.also { it[3 + a.size] = (it[3 + a.size].toInt() xor 1).toByte() }
                    5 -> value.also { it[3 + a.size + 32] = (it[3 + a.size + 32].toInt() xor 1).toByte() }
                    else -> value.also { it.fill(0, 3 + a.size + 64, 3 + a.size + 88) }
                }
            }, failures = { failures++ })
            assertNull(sealer.sealMessage())
            assertEquals(1, failures)
        }
    }

    @Test fun changedCallerSnapshotsAndCurrentStateCannotReturnWire() {
        repeat(3) { changed ->
            val callerLocal = local.copyOf()
            val callerPeer = peer.copyOf()
            val callerSecret = secret.copyOf()
            val sealer = sealer(create = { a, b, _, manifest, _ ->
                val target = when (changed) { 0 -> callerLocal; 1 -> callerPeer; else -> callerSecret }
                target[0] = (target[0].toInt() xor 1).toByte()
                fakeEnvelope(a, b, manifest)
            })
            assertNull(sealer.sealMessage(callerLocal, callerPeer, callerSecret))
        }
        var currentCalls = 0
        assertNull(sealer().sealMessage(current = { ++currentCalls == 1 }))
        assertEquals(2, currentCalls)
    }

    @Test fun changingBindingWhileResolvingItsNodeIdIsRejectedAndCleared() {
        val borrowed = mutableListOf<ByteArray>()
        var creates = 0
        val sealer = sealer(nodes = { bytes ->
            borrowed.add(bytes)
            val id = nodes(bytes)
            bytes[1] = (bytes[1].toInt() xor 1).toByte()
            id
        }, create = { a, b, _, m, _ -> creates++; fakeEnvelope(a, b, m) })
        assertNull(sealer.sealMessage())
        assertEquals(0, creates)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test fun zeroEntropyExceptionsAndCancellationNeverCreateAResult() {
        repeat(2) { badCall ->
            var creates = 0
            var failures = 0
            val random = object : SecureRandom() {
                var calls = 0
                override fun nextBytes(bytes: ByteArray) { bytes.fill(if (calls++ == badCall) 0 else 7) }
            }
            val sealer = sealer(random = random, create = { a, b, _, m, _ -> creates++; fakeEnvelope(a, b, m) }, failures = { failures++ })
            assertNull(sealer.sealMessage())
            assertEquals(0, creates)
            assertEquals(1, failures)
        }
        for (cancel in listOf(false, true)) {
            val borrowed = mutableListOf<ByteArray>()
            var failures = 0
            val sealer = sealer(create = { a, b, s, m, key ->
                borrowed.addAll(listOf(a, b, s, m, key))
                if (cancel) throw CancellationException("stop") else throw IOException("private detail")
            }, failures = { failures++ })
            val result = runCatching { sealer.sealMessage() }
            if (cancel) assertTrue(result.exceptionOrNull() is CancellationException) else assertNull(result.getOrThrow())
            assertEquals(if (cancel) 0 else 1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        }
    }

    @Test fun eachSealingGeneratesANewKeyAndNonceRatherThanAStableRetryWire() {
        val keys = mutableListOf<ByteArray>()
        val sealer = sealer(create = { a, b, _, manifest, key ->
            keys.add(key.copyOf())
            fakeEnvelope(a, b, manifest)
        })
        val first = sealer.sealMessage()!!
        val second = sealer.sealMessage()!!
        assertNotEquals(first, second)
        assertFalse(keys[0].contentEquals(keys[1]))
        assertFalse(SealedWire.decode(first)!!.iv.contentEquals(SealedWire.decode(second)!!.iv))
        keys.forEach { it.fill(0) }
    }
}
