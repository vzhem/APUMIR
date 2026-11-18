package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake decision runs on every send, so its rules are pinned here.
 *
 * Material is produced through real derivation code and a fake FFI, the same way
 * the mailbox builder tests do it: no key material, no journal and no network.
 */
class PushWakeSignalTest {
    private val local = byteArrayOf(1, 3, 5)
    private val peer = byteArrayOf(2, 4, 6)
    private val secret = ByteArray(32) { 9 }
    private val epoch = "09".repeat(16)

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun material(canWake: Boolean): PushWakeCrypto.Material? =
        PushWakeCrypto(
            derive = { _, _, _, _, _ -> bytes("81".repeat(32)) + bytes("ab".repeat(32)) },
            sign = { _, _, _, _, _, _, _ -> error("not used") },
            signMailbox = { _, _, _, _, _, _, _ -> error("not used") },
            recordFailure = {},
        ).derive(local, peer, secret, epoch, localIsSender = canWake)

    @Test
    fun aPeerWithoutARegisteredCapabilityIsNeverAddressed() {
        val signal = PushWakeSignal(nowMs = { 1_000_000 })
        val decision = signal.decide(PushWakeSignal.Candidate(material = null, lastWakeAtMs = 0))
        assertEquals(PushWakeSignal.Decision.NotRegistered, decision)
    }

    @Test
    fun aReceiverCapabilityCannotBeTurnedIntoAWake() {
        // Derivation in the receiver direction yields material that must never be
        // able to address the other side, even when the budget is untouched.
        val signal = PushWakeSignal(nowMs = { 1_000_000 })
        val decision = signal.decide(
            PushWakeSignal.Candidate(material = material(canWake = false), lastWakeAtMs = 0),
        )
        assertEquals(PushWakeSignal.Decision.NotRegistered, decision)
    }

    @Test
    fun aSenderCapabilityProducesOneWakeAndOnlyOutsideTheInterval() {
        val target = material(canWake = true)
        assertNotNull(target)
        try {
            val sender = PushWakeSignal(nowMs = { 1_000_000 }, minIntervalMs = 60_000)
            val first = sender.decide(PushWakeSignal.Candidate(material = target, lastWakeAtMs = 0))
            assertTrue(first is PushWakeSignal.Decision.Send)
            assertTrue((first as PushWakeSignal.Decision.Send).request.isValid())

            // Inside the interval: suppressed, so a burst costs one provider call.
            val soon = sender.decide(
                PushWakeSignal.Candidate(material = target, lastWakeAtMs = 1_000_000 - 1),
            )
            assertEquals(PushWakeSignal.Decision.TooSoon, soon)

            // Exactly at the boundary the next wake is allowed again.
            val later = sender.decide(
                PushWakeSignal.Candidate(material = target, lastWakeAtMs = 1_000_000 - 60_000),
            )
            assertTrue(later is PushWakeSignal.Decision.Send)
        } finally {
            target?.close()
        }
    }

    @Test
    fun onlyAnAcceptedAnswerCountsAndItIsNotDelivery() {
        assertTrue(PushWakeSignal.wasAccepted(PushHttpClient.WakeResult.ACCEPTED))
        assertFalse(PushWakeSignal.wasAccepted(PushHttpClient.WakeResult.THROTTLED))
        assertFalse(PushWakeSignal.wasAccepted(PushHttpClient.WakeResult.REJECTED))
        assertFalse(PushWakeSignal.wasAccepted(PushHttpClient.WakeResult.UNAVAILABLE))
    }

    @Test
    fun thePerPeerIntervalIsBoundedAndNotZero() {
        // A zero interval would make a conversation cost one provider call per
        // message; an unbounded one would make a peer unreachable after one wake.
        assertTrue(PushWakeSignal.MIN_INTERVAL_MS >= 60_000)
        assertTrue(PushWakeSignal.MIN_INTERVAL_MS <= 60 * 60 * 1000)
    }

    @Test
    fun closedMaterialIsNotAddressed() {
        val target = material(canWake = true) ?: error("material")
        target.close()
        val signal = PushWakeSignal(nowMs = { 1_000_000 })
        assertEquals(
            PushWakeSignal.Decision.NotRegistered,
            signal.decide(PushWakeSignal.Candidate(material = target, lastWakeAtMs = 0)),
        )
        assertNull(target.wakeRequest())
    }
}
