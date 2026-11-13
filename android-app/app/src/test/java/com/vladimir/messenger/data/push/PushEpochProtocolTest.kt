package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEpochProtocolTest {
    private val sender = byteArrayOf(1, 2, 3, 4)
    private val local = byteArrayOf(9, 8, 7, 6)
    private val senderHash = PushEpochProtocol.bindingHash(sender)
    private val localHash = PushEpochProtocol.bindingHash(local)
    private val epoch = "ab".repeat(16)

    private fun offer(generation: Long, value: String = epoch) =
        PushEpochProtocol.parse(PushEpochProtocol.encodeOffer(generation, senderHash, localHash, value))!!

    private fun accept(
        packet: PushEpochProtocol.Announcement,
        opened: Boolean = true,
        from: ByteArray? = sender,
        pin: ByteArray? = sender.copyOf(),
        localBinding: ByteArray? = local,
        previous: PushEpochProtocol.State? = null,
    ) = PushEpochProtocol.accept(packet, opened, from, pin, localBinding, previous)

    @Test fun strictOfferAndRevokeRoundTripWithoutLeakingEpochInToString() {
        val wire = PushEpochProtocol.encodeOffer(1, senderHash, localHash, epoch)!!
        assertTrue(wire.startsWith("APUTYP1|APUPUSH1|offer|1|"))
        val packet = PushEpochProtocol.parse(wire)!!
        assertEquals(PushEpochProtocol.Action.OFFER, packet.action)
        assertEquals(1L, packet.generation)
        assertEquals(epoch, packet.epochHex)
        assertFalse(packet.toString().contains(epoch))
        val result = accept(packet) as PushEpochProtocol.Verdict.Applied
        assertFalse(result.toString().contains(epoch))
        assertFalse(result.state.toString().contains(epoch))
        val revoke = PushEpochProtocol.parse(PushEpochProtocol.encodeRevoke(2, senderHash, localHash))!!
        assertEquals(PushEpochProtocol.Action.REVOKE, revoke.action)
        assertNull(revoke.epochHex)
        assertTrue(accept(revoke, previous = result.state) is PushEpochProtocol.Verdict.Applied)
    }

    @Test fun malformedAndNoncanonicalWireNeverParses() {
        val valid = PushEpochProtocol.encodeOffer(1, senderHash, localHash, epoch)!!
        val bad = listOf(
            "", "APUTYP1|APUPUSH1|", valid + "|extra", valid + "\n", " " + valid,
            valid.replace("|1|", "|01|"), valid.replace("|1|", "|0|"),
            valid.replace("|1|", "|-1|"), valid.replace("|1|", "|９|"),
            valid.replace("|1|", "|9007199254740992|"),
            valid.replace("offer", "unknown"), valid.replace(epoch, "0".repeat(32)),
            valid.replace(epoch, epoch.uppercase()), valid.replace(senderHash, senderHash.uppercase()),
            valid.replace(localHash, senderHash), valid.replace(epoch, "-"),
        )
        bad.forEach { assertNull(PushEpochProtocol.parse(it)) }
        assertTrue(PushEpochProtocol.isPacket("APUTYP1|APUPUSH1|garbage"))
        assertFalse(PushEpochProtocol.isPacket("hello"))
        assertNull(PushEpochProtocol.encodeOffer(0, senderHash, localHash, epoch))
        assertNull(PushEpochProtocol.encodeOffer(1, senderHash, localHash, "0".repeat(32)))
        assertNull(PushEpochProtocol.encodeRevoke(9_007_199_254_740_992L, senderHash, localHash))
        assertNotNull(PushEpochProtocol.encodeRevoke(9_007_199_254_740_991L, senderHash, localHash))
    }

    @Test fun requiresTheAlreadyPinnedSignerAndTheIntendedLocalBinding() {
        val packet = offer(1)
        assertTrue(accept(packet) is PushEpochProtocol.Verdict.Applied)
        assertTrue(accept(packet, opened = false) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(packet, from = null) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(packet, pin = null) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(packet, pin = byteArrayOf(1, 2, 3, 5)) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(packet, localBinding = byteArrayOf(8)) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(packet, from = ByteArray(513), pin = ByteArray(513)) is PushEpochProtocol.Verdict.Rejected)
    }

    @Test fun replayConflictAndRevokeCannotRollEpochBack() {
        val first = (accept(offer(4)) as PushEpochProtocol.Verdict.Applied).state
        assertTrue(accept(offer(4), previous = first) is PushEpochProtocol.Verdict.Duplicate)
        assertTrue(accept(offer(3), previous = first) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(offer(4, "cd".repeat(16)), previous = first) is PushEpochProtocol.Verdict.Rejected)
        val rotated = (accept(offer(5, "cd".repeat(16)), previous = first) as PushEpochProtocol.Verdict.Applied).state
        assertTrue(accept(offer(4), previous = rotated) is PushEpochProtocol.Verdict.Rejected)
        val revoke = PushEpochProtocol.parse(PushEpochProtocol.encodeRevoke(6, senderHash, localHash))!!
        val revoked = (accept(revoke, previous = rotated) as PushEpochProtocol.Verdict.Applied).state
        assertTrue(accept(revoke, previous = revoked) is PushEpochProtocol.Verdict.Duplicate)
        assertTrue(accept(offer(5), previous = revoked) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(offer(6), previous = revoked) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(offer(7), previous = revoked) is PushEpochProtocol.Verdict.Applied)
    }

    @Test fun invalidInMemoryPacketsOrPersistedStateFailClosed() {
        val invalid = PushEpochProtocol.Announcement.parsed(
            PushEpochProtocol.Action.OFFER, 0, senderHash, localHash, "0".repeat(32),
        )
        assertTrue(accept(invalid) is PushEpochProtocol.Verdict.Rejected)
        assertTrue(accept(offer(1), previous = PushEpochProtocol.State.from(invalid)) is PushEpochProtocol.Verdict.Rejected)
    }

    @Test fun changedPinOrProfileDoesNotSilentlyReuseOldGenerationState() {
        val first = (accept(offer(1)) as PushEpochProtocol.Verdict.Applied).state
        val newSender = byteArrayOf(7, 7, 7)
        val newLocal = byteArrayOf(8, 8, 8)
        val changedWire = PushEpochProtocol.encodeOffer(
            2, PushEpochProtocol.bindingHash(newSender), PushEpochProtocol.bindingHash(newLocal), epoch,
        )!!
        val changed = PushEpochProtocol.parse(changedWire)!!
        assertTrue(accept(changed, from = newSender, pin = newSender, localBinding = newLocal, previous = first)
            is PushEpochProtocol.Verdict.Rejected)
        // Explicitly clearing prior state must only occur as part of reviewed re-pairing.
        assertTrue(accept(changed, from = newSender, pin = newSender, localBinding = newLocal, previous = null)
            is PushEpochProtocol.Verdict.Applied)
    }
}
