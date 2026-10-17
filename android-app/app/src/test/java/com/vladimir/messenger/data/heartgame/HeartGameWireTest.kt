package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.swarm.Ed25519
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class HeartGameWireTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val id = "pk_" + "ab".repeat(32)
    private val now = 1_792_200_000_000L
    // Host test supplies the binding validator; Rust verifies real identity bindings in production.
    private fun binding(): ByteArray = ByteBuffer.allocate(1 + 2 + id.length + 32 + 8 + 64)
        .put(1.toByte()).putShort(id.length.toShort()).put(id.toByteArray())
        .put(Ed25519.keyPairFromSeed(seed).publicKey).putLong(now - 1000).put(ByteArray(64)).array()
    private fun record(score: Long = 7, time: Long = now) = HeartGameWire.sign(binding(), seed, score, time, "Игрок")
    private fun verify(text: String) = HeartGameWire.verify(text, now) { true }

    @Test fun signedResultCanTravelThroughAnyRelayUnchanged() {
        val wire = record()
        val parsed = verify(wire)!!
        assertEquals(id, parsed.nodeId)
        assertEquals("Игрок", parsed.name)
        assertEquals(7L, parsed.score)
        assertEquals(wire, parsed.record)
        assertNull(HeartGameWire.verify(wire, now) { false })
    }
    @Test fun modifyingScoreTimestampNameOrKeyBreaksSignature() {
        val parts = record().split('|')
        for ((field, replacement) in listOf(1 to "8", 2 to (now + 1).toString(), 3 to "QWxpY2U")) {
            val changed = parts.toMutableList(); changed[field] = replacement
            assertNull(verify(changed.joinToString("|")))
        }
        val otherSeed = ByteArray(32) { 42 }
        assertNull(verify(HeartGameWire.sign(binding(), otherSeed, 7, now, "Игрок")))
    }
    @Test fun claimedLegacyIdCannotReplaceAnotherSigningIdentity() {
        val old = verify(record())!!
        val forgedBinding = binding()
        val secondSeed = ByteArray(32) { 42 }
        Ed25519.keyPairFromSeed(secondSeed).publicKey.copyInto(forgedBinding, 3 + id.length)
        val other = verify(HeartGameWire.sign(forgedBinding, secondSeed, 99, now + 1, "Игрок"))!!
        assertEquals(old.nodeId, other.nodeId)
        assertNotEquals(old.keyId, other.keyId)
        assertFalse(HeartGameWire.newer(old, other))
    }
    @Test fun limitsAgeAndMalformedPacketsAreRejected() {
        assertNull(verify(record(time = now - HeartGameWire.RETENTION_MS - 1)))
        assertNull(verify(record(time = now + HeartGameWire.FUTURE_MS + 1)))
        for (bad in listOf("", "x|x|x|x|x", "a".repeat(1201), record() + "|extra")) assertNull(verify(bad))
        assertNotNull(verify(record(score = HeartGamePolicy.MAX_SCORE)))
    }
    @Test fun replayRollbackAndTieOrderingAreDeterministic() {
        val old = verify(record())!!
        assertFalse(HeartGameWire.newer(old, old))
        assertFalse(HeartGameWire.newer(old, verify(record(6, now + 1))!!))
        assertTrue(HeartGameWire.newer(old, verify(record(8, now + 1))!!))
        val other = old.copy(keyId = "00".repeat(32))
        assertEquals(listOf(other, old), HeartGameWire.ranked(listOf(old, other)))
    }
}
