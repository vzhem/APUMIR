package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses fake derivation, journal snapshots and HTTP; no live peer or persistence. */
class PushMailboxPutBuilderTest {
    private val owner = "81".repeat(32)
    private val capability = "ab".repeat(32)
    private val local = byteArrayOf(1, 3, 5)
    private val peer = byteArrayOf(2, 4, 6)
    private val secret = ByteArray(32) { 9 }
    private val epoch = "09".repeat(16)
    private val cutoff = 1_700_604_800_000L
    private val wire = fakeWire(local, peer)
    private val id = PushMailboxProtocol.putId(cutoff, wire)!!

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun fakeWire(sender: ByteArray, recipient: ByteArray): String {
        val ciphertext = ByteArray(32) { 1 }
        val key = ByteArray(sender.size + 203) { 7 }
        key[0] = 1
        key[1] = (sender.size ushr 8).toByte()
        key[2] = sender.size.toByte()
        sender.copyInto(key, 3)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.digest(recipient).copyInto(key, 3 + sender.size)
        hash.digest(SealedWire.manifest(ciphertext)).copyInto(key, 3 + sender.size + 32)
        return SealedWire.encode(key, ByteArray(12) { 1 }, ciphertext)
    }
    private fun material(sender: Boolean = true, failures: () -> Unit = { error("unexpected failure") }) =
        PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { _, _, _, _, _, _, _ -> error("not used") }, recordFailure = failures)
            .derive(local, peer, secret, epoch, sender)!!
    private fun state(local: ByteArray = this.local, peer: ByteArray = this.peer, epoch: String = this.epoch, revoke: Boolean = false): PushEpochProtocol.State {
        val peerHash = PushEpochProtocol.bindingHash(peer)
        val localHash = PushEpochProtocol.bindingHash(local)
        val text = if (revoke) PushEpochProtocol.encodeRevoke(100, peerHash, localHash)
            else PushEpochProtocol.encodeOffer(100, peerHash, localHash, epoch)
        return PushEpochProtocol.State.from(PushEpochProtocol.parse(text)!!)
    }
    private fun PushWakeCrypto.Material.put(
        local: ByteArray = this@PushMailboxPutBuilderTest.local,
        peer: ByteArray = this@PushMailboxPutBuilderTest.peer,
        state: PushEpochProtocol.State = this@PushMailboxPutBuilderTest.state(),
        id: String = this@PushMailboxPutBuilderTest.id,
        cutoff: Long = this@PushMailboxPutBuilderTest.cutoff,
        wire: String = this@PushMailboxPutBuilderTest.wire,
        current: () -> Boolean = { true },
    ) = mailboxPut(local, peer, state, id, cutoff, wire, current)

    @Test fun senderBuildsOnlyTheStoredTripleForTheMatchingOfferedEpoch() {
        val sender = material()
        val receiver = material(sender = false)
        try {
            val request = sender.put()!!
            assertTrue(request.isValid())
            assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\",\"id\":\"$id\",\"expiresAt\":$cutoff,\"envelope\":\"$wire\"}",
                request.toJsonBytes().toString(Charsets.US_ASCII))
            assertFalse(request.toString().contains(capability))
            assertFalse(request.toString().contains(wire))
            assertNull(receiver.put())
            sender.close()
            assertNull(sender.put())
        } finally { sender.close(); receiver.close() }
    }

    @Test fun wrongPinIdentityEpochOrCutoffCannotProduceARequest() {
        val sender = material()
        try {
            assertNull(sender.put(local = byteArrayOf(8)))
            assertNull(sender.put(peer = byteArrayOf(8)))
            assertNull(sender.put(peer = local))
            assertNull(sender.put(state = state(local = peer, peer = local)))
            assertNull(sender.put(state = state(epoch = "0a".repeat(16))))
            assertNull(sender.put(state = state(revoke = true)))
            assertNull(sender.put(id = "11".repeat(16)))
            assertNull(sender.put(id = "00".repeat(16)))
            assertNull(sender.put(cutoff = cutoff + 1))
            assertNull(sender.put(cutoff = 0))
            assertNull(sender.put(wire = "plaintext"))
            // Even a matching outside hash cannot route a wire sealed for a
            // different sender or recipient, or with a changed ciphertext.
            val wrongSender = fakeWire(byteArrayOf(7), peer)
            val wrongRecipient = fakeWire(local, byteArrayOf(8))
            val parts = SealedWire.decode(wire)!!
            parts.ciphertext[0] = (parts.ciphertext[0].toInt() xor 1).toByte()
            val wrongManifest = SealedWire.encode(parts.keyEnvelope, parts.iv, parts.ciphertext)
            for (other in listOf(wrongSender, wrongRecipient, wrongManifest)) {
                assertNull(sender.put(id = PushMailboxProtocol.putId(cutoff, other)!!, wire = other))
            }
            assertNull(sender.put(current = { false }))
            assertNotNull(sender.put())
        } finally { sender.close() }
    }

    @Test fun changedCallerBuffersOrClosedMaterialDuringBuildFailClosed() {
        repeat(2) { changed ->
            val sender = material()
            val localCopy = local.copyOf()
            val peerCopy = peer.copyOf()
            var calls = 0
            try {
                val result = sender.put(local = localCopy, peer = peerCopy, current = {
                    if (++calls == 2) {
                        val target = if (changed == 0) localCopy else peerCopy
                        target[0] = (target[0].toInt() xor 0x40).toByte()
                    }
                    true
                })
                assertNull(result)
                assertEquals(2, calls)
                assertTrue(localCopy.any { it != 0.toByte() })
                assertTrue(peerCopy.any { it != 0.toByte() })
            } finally { sender.close() }
        }
        val sender = material()
        var calls = 0
        assertNull(sender.put(current = { if (++calls == 2) sender.close(); true }))
        assertEquals(2, calls)
        sender.close()
    }

    @Test fun callbackFailureAndCancellationDoNotLeakOrTurnIntoSuccess() {
        var failures = 0
        val sender = material(failures = { failures++ })
        try {
            assertNull(sender.put(current = { throw IOException("sensitive detail") }))
            assertEquals(1, failures)
            val result = runCatching { sender.put(current = { throw CancellationException("stop") }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(1, failures)
        } finally { sender.close() }
    }
}
