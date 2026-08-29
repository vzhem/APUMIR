package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferralCreditPolicyTest {

    private val own = "pk_" + "22".repeat(32)
    private val invitee = "pk_" + "11".repeat(32)
    private val stranger = "pk_" + "33".repeat(32)
    private val now = 1_800_000_000_000L

    private fun packet(
        inviteeId: String = invitee,
        inviterId: String = own,
        createdAtMs: Long = now,
    ) = ReferralWire.Attribution(
        inviteeNodeId = inviteeId,
        inviterNodeId = inviterId,
        createdAtMs = createdAtMs,
        nonce = "AAECAwQFBgcICQoLDA0ODw",
    )

    private fun decide(
        pkt: ReferralWire.Attribution = packet(),
        sender: String = invitee,
        ownNodeId: String? = own,
        credited: Set<String> = emptySet(),
        nowMs: Long = now,
    ) = ReferralCreditPolicy.decide(pkt, sender, ownNodeId, credited, nowMs)

    private fun reason(decision: ReferralCreditPolicy.Decision): String =
        (decision as ReferralCreditPolicy.Decision.Reject).reason

    @Test
    fun firstAttributionCreditsTheSender() {
        val decision = decide()
        assertTrue(decision is ReferralCreditPolicy.Decision.Credit)
        assertEquals(invitee, (decision as ReferralCreditPolicy.Decision.Credit).inviteeNodeId)
    }

    /**
     * Зачисляется фактический отправитель пакета, а не тот, кто назван внутри:
     * иначе любой узел мог бы приписать себе чужое приглашение.
     */
    @Test
    fun creditIsKeyedOnTransportSenderNotOnTheEnvelope() {
        val decision = decide(pkt = packet(inviteeId = stranger), sender = invitee)
        assertEquals(invitee, (decision as ReferralCreditPolicy.Decision.Credit).inviteeNodeId)
    }

    @Test
    fun packetAddressedToAnotherNodeIsRejected() {
        assertEquals(
            "packet is addressed to another node",
            reason(decide(pkt = packet(inviterId = stranger))),
        )
    }

    @Test
    fun selfReferralIsRejected() {
        assertEquals("self referral", reason(decide(sender = own)))
    }

    @Test
    fun secondAttributionFromTheSameInviteeIsRejected() {
        assertEquals("already credited", reason(decide(credited = setOf(invitee))))
    }

    /** Тот же узел в другом регистре не должен засчитываться второй раз. */
    @Test
    fun duplicateCheckIgnoresNodeIdCase() {
        assertEquals("already credited", reason(decide(credited = setOf(invitee.uppercase()))))
    }

    @Test
    fun expiredAttributionIsRejected() {
        val stale = now - ReferralCreditPolicy.MAX_ATTRIBUTION_AGE_MS - 1
        assertEquals("attribution expired", reason(decide(pkt = packet(createdAtMs = stale))))
    }

    @Test
    fun attributionAtTheAgeLimitIsStillAccepted() {
        val edge = now - ReferralCreditPolicy.MAX_ATTRIBUTION_AGE_MS
        assertTrue(decide(pkt = packet(createdAtMs = edge)) is ReferralCreditPolicy.Decision.Credit)
    }

    @Test
    fun attributionFromTheFutureBeyondSkewIsRejected() {
        val future = now + ReferralCreditPolicy.CLOCK_SKEW_MS + 1
        assertEquals("created in the future", reason(decide(pkt = packet(createdAtMs = future))))
    }

    @Test
    fun smallClockSkewIsTolerated() {
        val skewed = now + ReferralCreditPolicy.CLOCK_SKEW_MS
        assertTrue(decide(pkt = packet(createdAtMs = skewed)) is ReferralCreditPolicy.Decision.Credit)
    }

    @Test
    fun missingOwnNodeIdIsRejected() {
        assertEquals("own node id unavailable", reason(decide(ownNodeId = null)))
        assertEquals("own node id unavailable", reason(decide(ownNodeId = "offline")))
    }

    @Test
    fun senderWithoutNodeIdShapeIsRejected() {
        assertEquals("sender is not a node id", reason(decide(sender = "relay-broadcast")))
    }

    /**
     * Правило владельца от 2026-08-29: на первом шаге засчитывается любой,
     * кто пришёл по ссылке. Тест фиксирует текущее поведение, чтобы переход на
     * «только новая identity» был осознанным изменением, а не случайностью.
     */
    @Test
    fun anyInviteeIsCreditedForNow() {
        repeat(3) { index ->
            val peer = "pk_" + (index + 4).toString().repeat(64).take(64)
            val decision = decide(pkt = packet(inviteeId = peer), sender = peer)
            assertTrue(decision is ReferralCreditPolicy.Decision.Credit)
        }
    }
}
