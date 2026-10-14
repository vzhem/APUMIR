package com.vladimir.messenger.data.schedule

import org.junit.Assert.*
import org.junit.Test

class DeferredDeliveryPolicyTest {
    private val letter = DeferredWire.Letter("deferred_job1", 1_900_000_000_000L, "", "", "", "Будущее сообщение")
    private val wire = DeferredWire.encode(letter)
    private val receipt = DeferredWire.parseReceipt(DeferredWire.receipt(letter))!!
    private val peers = listOf("pk_alice001", "pk_bob00001")

    @Test fun onlyAnExpectedPeerWithTheMatchingSavedPayloadAndIdentityCanConfirm() {
        fun accepts(r: DeferredWire.Receipt = receipt, peer: String = peers[0], owner: String = "pk_self0001",
                    current: String = "pk_self0001") = DeferredDeliveryPolicy.acceptsReceipt(
            r, "job1", owner, current, peers, peer, wire)
        assertTrue(accepts())
        assertFalse(accepts(peer = "pk_other001"))
        assertFalse(accepts(owner = ""))
        assertFalse(accepts(current = "pk_new00001"))
        assertFalse(accepts(r = receipt.copy(id = "deferred_another")))
        assertFalse(accepts(r = receipt.copy(digest = "0".repeat(64))))
        assertNull(DeferredWire.parseReceipt("ack|${letter.id}"))
    }

    @Test fun groupFallbackIsPerRecipientAndDoesNotSendAgainToConfirmedPhones() {
        assertFalse(DeferredDeliveryPolicy.needsFallback(peers[0], listOf(peers[0]), emptyList()))
        assertTrue(DeferredDeliveryPolicy.needsFallback(peers[1], listOf(peers[0]), emptyList()))
        assertFalse(DeferredDeliveryPolicy.needsFallback(peers[1], emptyList(), listOf(peers[1])))
    }

    @Test fun retriesAreBoundedRotatingAndSkipConfirmedRecipients() {
        val group = (0 until 30).map { "pk_peer000$it" }
        val first = DeferredDeliveryPolicy.batch(group, 0, emptyList())
        assertEquals((0..7).toList(), first.map { it.first })
        val next = DeferredDeliveryPolicy.batch(group, first.last().first + 1, emptyList())
        assertEquals((8..15).toList(), next.map { it.first })
        val wrap = DeferredDeliveryPolicy.batch(group, 29, listOf(group[0]))
        assertEquals(listOf(29, 1, 2, 3, 4, 5, 6, 7), wrap.map { it.first })
        assertTrue(DeferredDeliveryPolicy.batch(emptyList(), 500, emptyList()).isEmpty())
        assertTrue(DeferredDeliveryPolicy.batch(group, -1, group).isEmpty())
    }
}
