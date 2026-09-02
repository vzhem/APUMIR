package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обмен токеном по связи.
 *
 * Короткая ссылка `apu://` намеренно не несёт подписанный токен: он занимал 427
 * символов из 589 и превращал QR в густую сетку. Вместо этого приглашённый
 * просит токен у пригласившего сразу после знакомства, а тот отвечает той же
 * подписью, что раньше ехала в ссылке. Начисление ранга не изменилось.
 */
class ReferralTokenExchangeTest {

    private val me = "pk_" + "ab".repeat(16)
    private val other = "pk_" + "cd".repeat(16)
    private val token = ByteArray(120) { it.toByte() }

    @Test
    fun requestRoundTrips() {
        val packet = ReferralWire.parse(ReferralWire.buildTokenRequest(me))

        assertTrue(packet is ReferralWire.Packet.TokenRequest)
        assertEquals(me, (packet as ReferralWire.Packet.TokenRequest).fromNodeId)
    }

    @Test
    fun replyCarriesTheTokenUnchanged() {
        val packet = ReferralWire.parse(ReferralWire.buildTokenReply(other, token))

        assertTrue(packet is ReferralWire.Packet.TokenReply)
        val reply = packet as ReferralWire.Packet.TokenReply
        assertEquals(other, reply.fromNodeId)
        assertArrayEquals(token, ReferralWire.decode(reply.tokenB64))
    }

    /** Приёмник обязан узнавать оба конверта до тяжёлого разбора. */
    @Test
    fun bothEnvelopesAreRecognisedAsReferralPackets() {
        assertTrue(ReferralWire.isReferralPacket(ReferralWire.buildTokenRequest(me)))
        assertTrue(ReferralWire.isReferralPacket(ReferralWire.buildTokenReply(other, token)))
    }

    /** Обычная подписанная атрибуция не должна пострадать от новых веток разбора. */
    @Test
    fun signedAttributionStillParses() {
        val envelope = ReferralWire.buildSignedAttribution(
            inviteeNodeId = me,
            inviterNodeId = other,
            qualifiedAtMs = 1_700_000_000_000L,
            token = token,
            binding = ByteArray(142) { 7 },
        )

        assertTrue(ReferralWire.parse(envelope) is ReferralWire.Packet.SignedAttribution)
    }

    @Test
    fun malformedExchangeEnvelopesAreRejected() {
        assertNull(ReferralWire.buildTokenRequest("не узел"))
        assertNull(ReferralWire.buildTokenReply(other, ByteArray(0)))
        assertNull(ReferralWire.parse("APUREF1|tokq|2"))
        assertNull(ReferralWire.parse("APUREF1|tokq|2|не узел"))
        assertNull(ReferralWire.parse("APUREF1|tokr|2|$other"))
        assertNull(ReferralWire.parse("APUREF1|tokr|2|$other|не base64!"))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
