package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила зачисления приглашения (MASTER_PLAN 2.5.2A, полная версия).
 *
 * На вход подаётся уже подтверждённый receipt: считается, что подписи токена и
 * привязки identity проверены ядром, поэтому все его поля аутентичны, кроме
 * открытой метки [VerifiedReceipt.qualifiedAtMs].
 */
class ReferralCreditPolicyTest {

    private val own = ReferralFixtures.INVITER
    private val invitee = ReferralFixtures.INVITEE
    private val stranger = ReferralFixtures.STRANGER
    private val now = ReferralFixtures.NOW

    /** Ссылка создана сегодня, identity приглашённого — минуту назад. */
    private fun receipt(
        inviteeId: String = invitee,
        identityCreatedAtMs: Long = now - 60_000,
        inviterId: String = own,
        tokenCreatedAtMs: Long = now - 60_000,
        tokenExpiresAtMs: Long = now + ReferralFixtures.WEEK_MS,
        qualifiedAtMs: Long = now,
    ) = VerifiedReceipt(
        inviteeNodeId = inviteeId,
        inviteeIdentityCreatedAtMs = identityCreatedAtMs,
        inviterNodeId = inviterId,
        tokenCreatedAtMs = tokenCreatedAtMs,
        tokenExpiresAtMs = tokenExpiresAtMs,
        qualifiedAtMs = qualifiedAtMs,
    )

    private fun decide(
        verified: VerifiedReceipt = receipt(),
        sender: String = invitee,
        ownNodeId: String? = own,
        credited: Set<String> = emptySet(),
        nowMs: Long = now,
    ) = ReferralCreditPolicy.decide(verified, sender, ownNodeId, credited, nowMs)

    private fun reason(decision: ReferralCreditPolicy.Decision): String =
        (decision as ReferralCreditPolicy.Decision.Reject).reason

    @Test
    fun newcomerWithASignedInviteIsCredited() {
        val decision = decide()
        assertTrue(decision is ReferralCreditPolicy.Decision.Credit)
        assertEquals(invitee, (decision as ReferralCreditPolicy.Decision.Credit).inviteeNodeId)
    }

    /**
     * Зачисляется узел из подписанной привязки identity, совпадающий с
     * фактическим отправителем пакета. Пересланный чужой receipt не работает:
     * узел в нём не совпадёт с тем, кто реально прислал конверт.
     */
    @Test
    fun forwardedReceiptIsRejected() {
        val decision = decide(verified = receipt(inviteeId = stranger), sender = invitee)
        assertEquals("receipt does not match the transport sender", reason(decision))
    }

    @Test
    fun creditFollowsTheTransportSender() {
        val decision = decide(verified = receipt(inviteeId = stranger), sender = stranger)
        assertEquals(stranger, (decision as ReferralCreditPolicy.Decision.Credit).inviteeNodeId)
    }

    @Test
    fun tokenAddressedToAnotherNodeIsRejected() {
        assertEquals("token is addressed to another node", reason(decide(verified = receipt(inviterId = stranger))))
    }

    @Test
    fun selfReferralIsRejected() {
        val decision = decide(verified = receipt(inviteeId = own), sender = own)
        assertEquals("self referral", reason(decision))
    }

    /** Повторная установка/клон той же identity счётчик не двигает. */
    @Test
    fun secondReceiptFromTheSameIdentityIsRejected() {
        assertEquals("already credited", reason(decide(credited = setOf(invitee))))
    }

    @Test
    fun duplicateCheckIgnoresNodeIdCase() {
        assertEquals("already credited", reason(decide(credited = setOf(invitee.uppercase()))))
    }

    @Test
    fun expiredAttributionIsRejected() {
        val stale = now - ReferralCreditPolicy.MAX_ATTRIBUTION_AGE_MS - 1
        assertEquals("attribution expired", reason(decide(verified = receipt(qualifiedAtMs = stale))))
    }

    @Test
    fun attributionAtTheAgeLimitIsStillAccepted() {
        val edge = now - ReferralCreditPolicy.MAX_ATTRIBUTION_AGE_MS
        assertTrue(decide(verified = receipt(qualifiedAtMs = edge)) is ReferralCreditPolicy.Decision.Credit)
    }

    @Test
    fun qualificationFromTheFutureBeyondSkewIsRejected() {
        val future = now + ReferralCreditPolicy.CLOCK_SKEW_MS + 1
        assertEquals("qualified in the future", reason(decide(verified = receipt(qualifiedAtMs = future))))
    }

    @Test
    fun smallClockSkewIsTolerated() {
        val skewed = now + ReferralCreditPolicy.CLOCK_SKEW_MS
        assertTrue(decide(verified = receipt(qualifiedAtMs = skewed)) is ReferralCreditPolicy.Decision.Credit)
    }

    @Test
    fun expiredInviteTokenIsRejected() {
        val dead = receipt(tokenExpiresAtMs = now - ReferralCreditPolicy.CLOCK_SKEW_MS - 1)
        assertEquals("invite token expired", reason(decide(verified = dead)))
    }

    @Test
    fun inviteTokenAtTheExpiryEdgeIsAccepted() {
        val edge = receipt(tokenExpiresAtMs = now - ReferralCreditPolicy.CLOCK_SKEW_MS)
        assertTrue(decide(verified = edge) is ReferralCreditPolicy.Decision.Credit)
    }

    @Test
    fun identityCreatedInTheFutureIsRejected() {
        val impossible = receipt(identityCreatedAtMs = now + ReferralCreditPolicy.CLOCK_SKEW_MS + 1)
        assertEquals("invitee identity created in the future", reason(decide(verified = impossible)))
    }

    /**
     * Правило «только новенькие» (решение владельца от 2026-08-29): identity,
     * созданная задолго до ссылки, не засчитывается. Сутки запаса — только на
     * перекос часов между телефонами.
     */
    @Test
    fun existingIdentityIsNotANewcomer() {
        val old = receipt(
            identityCreatedAtMs = now - ReferralCreditPolicy.NEW_IDENTITY_SKEW_MS - 1,
            tokenCreatedAtMs = now,
        )
        assertEquals("invitee identity is not new", reason(decide(verified = old)))
    }

    /** Реальный случай двух телефонов: identity старше ссылки на несколько дней. */
    @Test
    fun identityFromDaysAgoIsRejected() {
        val threeDays = 3L * 24 * 60 * 60 * 1000
        val decision = decide(verified = receipt(identityCreatedAtMs = now - threeDays, tokenCreatedAtMs = now))
        assertEquals("invitee identity is not new", reason(decision))
    }

    @Test
    fun identitySlightlyOlderThanTheTokenIsStillNew() {
        val edge = receipt(
            identityCreatedAtMs = now - ReferralCreditPolicy.NEW_IDENTITY_SKEW_MS,
            tokenCreatedAtMs = now,
        )
        assertTrue(decide(verified = edge) is ReferralCreditPolicy.Decision.Credit)
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
     * Повторный прогон той же подписанной пары (token + identity) после того, как
     * узел уже зачислен, ничего не добавляет: идемпотентность держится на узле, а
     * не на содержимом конверта.
     */
    @Test
    fun replayedReceiptDoesNotDoubleCount() {
        val first = decide()
        assertTrue(first is ReferralCreditPolicy.Decision.Credit)
        val replay = decide(credited = setOf((first as ReferralCreditPolicy.Decision.Credit).inviteeNodeId))
        assertEquals("already credited", reason(replay))
    }
}
