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
