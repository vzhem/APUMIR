package com.vladimir.messenger.data.schedule

import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.security.SealedWire
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DeferredWireTest {
    private val letter = DeferredWire.Letter("deferred_01234567-89ab-cdef", 1_900_000_000_000L,
        "group-1", "topic-2", "Автор", "Текст | с разделителями\nи emoji 🕒")

    @Test fun roundTripsUnicodeAndSeparatorsWithoutRevealingAGroupMessageToOldParsers() {
        val wire = DeferredWire.encode(letter)
        assertEquals(letter, DeferredWire.parse(wire))
        assertTrue(GroupWire.isGroupPacket(wire))
        assertNull(GroupWire.parse(wire)) // old GroupRouter consumes the unknown kind
        assertNull(GroupWire.parse(DeferredWire.receipt(letter)))
    }

    @Test fun directMessagesUseTheSameReservedWrapper() {
        val direct = letter.copy(groupId = "", topicId = "", senderName = "")
        assertEquals(direct, DeferredWire.parse(DeferredWire.encode(direct)))
        assertNull(GroupWire.parse(DeferredWire.encode(direct)))
    }

    @Test fun capabilityProbeIsSmallAndCannotBeMistakenForAStoredReceipt() {
        val probe = DeferredWire.probe(letter)
        assertTrue(probe.length < 220)
        assertTrue(GroupWire.isGroupPacket(probe))
        assertNull(GroupWire.parse(probe))
        val parsed = DeferredWire.parseProbe(probe)!!
        val ready = DeferredWire.ready(parsed)
        assertEquals(parsed, DeferredWire.parseReady(ready))
        assertNull(DeferredWire.parseReceipt(probe))
        assertNull(DeferredWire.parseReceipt(ready))
        assertNull(GroupWire.parse(ready))
        assertTrue(DeferredWire.isPacket(probe))
        assertTrue(DeferredWire.isPacket(ready))
    }

    @Test fun cancellationHasItsOwnBoundedRequestAndConfirmationNotAStoredReceipt() {
        val wire = DeferredWire.cancel(letter)
        val request = DeferredWire.parseCancel(wire)!!
        assertEquals(letter.id, request.id)
        assertEquals(DeferredWire.digest(DeferredWire.encode(letter)), request.digest)
        val ack = DeferredWire.cancelAck(request)
        assertEquals(request, DeferredWire.parseCancelAck(ack))
        assertNull(DeferredWire.parseReceipt(wire))
        assertNull(DeferredWire.parseReceipt(ack))
        assertNull(DeferredWire.parseCancelAck(DeferredWire.receipt(letter)))
        assertNull(GroupWire.parse(wire))
        assertNull(GroupWire.parse(ack))
        assertTrue(DeferredWire.isPacket(wire))
        assertTrue(DeferredWire.isPacket(ack))
        assertTrue(wire.length < 220)
        assertNull(DeferredWire.parseCancel(wire + "|extra"))
        assertNull(DeferredWire.parseCancel(DeferredWire.CANCEL_PREFIX + letter.id + "|bad"))
    }

    @Test fun lateContinuationPartsHaveTheSameCancellationIdentityAsTheirHead() {
        assertEquals(letter.id, DeferredWire.headId(letter.id))
        (1..8).forEach { assertEquals(letter.id, DeferredWire.headId("${letter.id}_part$it")) }
        assertEquals("${letter.id}_party", DeferredWire.headId("${letter.id}_party"))
    }

    @Test fun receiptBindsIdAndEveryPayloadField() {
        val receipt = DeferredWire.parseReceipt(DeferredWire.receipt(letter))!!
        assertEquals(letter.id, receipt.id)
        assertEquals(DeferredWire.digest(DeferredWire.encode(letter)), receipt.digest)
        listOf(letter.copy(text = "Изменено"), letter.copy(showAtMs = letter.showAtMs + 1),
            letter.copy(groupId = "other"), letter.copy(topicId = "other"), letter.copy(senderName = "Другой"))
            .forEach { assertNotEquals(receipt.digest, DeferredWire.parseReceipt(DeferredWire.receipt(it))!!.digest) }
    }

    @Test fun rejectsMalformedPacketsBoundsDatesAndNestedFields() {
        val wire = DeferredWire.encode(letter)
        listOf(wire.replace("1900000000000", "0"), wire.replace("1900000000000", "-1"),
            wire.replace("1900000000000", "9223372036854775807"), wire.replace("Z3JvdXAtMQ", "***"), "APUGRP1|defer1|bad")
            .forEach { assertNull(it, DeferredWire.parse(it)) }
        assertNull(DeferredWire.parseReceipt("ack|${letter.id}"))
        assertNull(DeferredWire.parseReceipt(DeferredWire.ACK_PREFIX + letter.id + "|wrong"))
        assertTrue(DeferredWire.isPacket("APUGRP1|defer1|bad"))
        assertTrue(DeferredWire.isPacket("APUGRP1|deferack1|bad"))
    }

    @Test fun rejectsOversizedPayloadBeforeEncodingOrDecoding() {
        assertTrue(runCatching { DeferredWire.encode(letter.copy(text = "x".repeat(34_001))) }.isFailure)
        assertNull(DeferredWire.parse(DeferredWire.DATA_PREFIX + "x".repeat(34_501)))
        val max = letter.copy(text = "x".repeat(34_000))
        assertEquals(max, DeferredWire.parse(DeferredWire.encode(max)))
    }

    @Test fun senderBindingExtractionIsBoundedAndRejectsMalformedEnvelopeVersions() {
        val binding = byteArrayOf(4, 5, 6)
        val envelope = ByteArray(203 + binding.size)
        envelope[0] = 1
        envelope[2] = binding.size.toByte()
        binding.copyInto(envelope, 3)
        fun wire(bytes: ByteArray) = SealedWire.encode(bytes, ByteArray(12), byteArrayOf(1))
        assertArrayEquals(binding, SealedWire.senderBinding(wire(envelope)))
        assertNull(SealedWire.senderBinding(wire(envelope.copyOf(envelope.size - 1))))
        envelope[0] = 2
        assertNull(SealedWire.senderBinding(wire(envelope)))
        assertNull(SealedWire.senderBinding("not sealed"))
    }

    @Test fun largestWireLeavesRoomForSealingAndRelayBase64() {
        // Rust FileKeyEnvelopeV1: max binding 512 bytes + 203 bytes of framing.
        // Reserve another 2 KiB for relay metadata, IDs and framing.
        val sealed = SealedWire.encode(ByteArray(715), ByteArray(12), ByteArray(34_500 + 16))
        val relayBytes = Base64.getEncoder().encode(sealed.toByteArray(Charsets.UTF_8)).size + 2048
        assertTrue("relay size=$relayBytes", relayBytes < 64 * 1024)
    }

    @Test fun timerNeverRevealsEarlyAndCanRevealOfflineAfterRestartOrLateArrival() {
        assertFalse(DeferredWire.due(letter.showAtMs, letter.showAtMs - 1))
        assertTrue(DeferredWire.due(letter.showAtMs, letter.showAtMs))
        assertTrue(DeferredWire.due(letter.showAtMs, letter.showAtMs + 86_400_000L))
        // Wall clock moved back after restart: must remain hidden, not trust the old timer.
        assertFalse(DeferredWire.due(letter.showAtMs, letter.showAtMs - 60_000L))
    }

    @Test fun transportIdsAreStableButDifferentForEveryPhasePeerAndPart() {
        val first = DeferredWire.transportId(letter.id, "pk_alice001", "early")
        assertEquals(first, DeferredWire.transportId(letter.id, "pk_alice001", "early"))
        assertNotEquals(first, DeferredWire.transportId(letter.id, "pk_alice001", "normal0"))
        assertNotEquals(first, DeferredWire.transportId(letter.id, "pk_bob00001", "early"))
        assertNotEquals(DeferredWire.transportId(letter.id, "pk_alice001", "normal0"),
            DeferredWire.transportId(letter.id, "pk_alice001", "normal1"))
    }
}
