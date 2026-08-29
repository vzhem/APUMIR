package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проводной конверт атрибуции: сборка, строгий разбор и отсеивание подделок.
 *
 * Криптографию здесь заменяют синтетические конверты ([ReferralFixtures]):
 * проверяется, что конверт сохраняет поля, не выходит за пределы транспорта и
 * не разбирается при любом искажении.
 */
class ReferralWireTest {

    private val invitee = ReferralFixtures.INVITEE
    private val inviter = ReferralFixtures.INVITER
    private val now = ReferralFixtures.NOW

    @Test
    fun signedEnvelopeRoundTripsAllFields() {
        val token = ReferralFixtures.token(inviter)
        val binding = ReferralFixtures.binding(invitee)
        val envelope = ReferralWire.buildSignedAttribution(invitee, inviter, now, token, binding)
        assertNotNull(envelope)

        val packet = ReferralWire.parse(envelope)
        assertTrue(packet is ReferralWire.Packet.SignedAttribution)
        val signed = packet as ReferralWire.Packet.SignedAttribution
        assertEquals(invitee, signed.inviteeNodeId)
        assertEquals(inviter, signed.inviterNodeId)
        assertEquals(now, signed.qualifiedAtMs)
        assertTrue(token.contentEquals(ReferralWire.decode(signed.tokenB64)!!))
        assertTrue(binding.contentEquals(ReferralWire.decode(signed.bindingB64)!!))
    }

    @Test
    fun signedEnvelopeNormalisesNodeIdsAndStaysInsideTheLimit() {
        val envelope = ReferralWire.buildSignedAttribution(
            invitee.uppercase(),
            "  $inviter  ",
            now,
            ReferralFixtures.token(inviter),
            ReferralFixtures.binding(invitee),
        )
        assertNotNull(envelope)
        val packet = ReferralWire.parse(envelope) as ReferralWire.Packet.SignedAttribution
        assertEquals(invitee, packet.inviteeNodeId)
        assertEquals(inviter, packet.inviterNodeId)
        assertTrue(envelope!!.length <= ReferralWire.MAX_ENVELOPE_CHARS)
        assertTrue(ReferralWire.isReferralPacket(envelope))
    }

    @Test
    fun buildRefusesGarbage() {
        val token = ReferralFixtures.token(inviter)
        val binding = ReferralFixtures.binding(invitee)
        // Самоприглашение
        assertNull(ReferralWire.buildSignedAttribution(invitee, invitee, now, token, binding))
        // Неканонический узел
        assertNull(ReferralWire.buildSignedAttribution("pk_ZZ", inviter, now, token, binding))
        // Пустые криптоконверты
        assertNull(ReferralWire.buildSignedAttribution(invitee, inviter, now, ByteArray(0), binding))
        assertNull(ReferralWire.buildSignedAttribution(invitee, inviter, now, token, ByteArray(0)))
        // Криптоконверт больше предела
        assertNull(
            ReferralWire.buildSignedAttribution(
                invitee,
                inviter,
                now,
                ByteArray(ReferralReceipt.MAX_TOKEN_BYTES + 1),
                binding,
            ),
        )
        // Нулевая метка времени
        assertNull(ReferralWire.buildSignedAttribution(invitee, inviter, 0L, token, binding))
    }

    @Test
    fun unsignedEnvelopeIsRecognisedButNeverCredited() {
        val nonce = ReferralWire.encode(ByteArray(16))
        val legacy = listOf(
            ReferralWire.PREFIX,
            ReferralWire.KIND_ATTRIBUTION,
            ReferralWire.VERSION_UNSIGNED,
            invitee,
            inviter,
            now.toString(),
            nonce,
        ).joinToString("|")

        val packet = ReferralWire.parse(legacy)
        assertTrue(packet is ReferralWire.Packet.UnsignedAttribution)
        val unsigned = packet as ReferralWire.Packet.UnsignedAttribution
        assertEquals(invitee, unsigned.inviteeNodeId)
        assertEquals(inviter, unsigned.inviterNodeId)
        // Подписанный разбор его не принимает
        assertNull(ReferralWire.parse(legacy.replace("|1|", "|${ReferralWire.VERSION_SIGNED}|")))
    }

    @Test
    fun openFieldsMayLieAndAreCaughtByTheVerifier() {
        val envelope = ReferralFixtures.signedEnvelope()
        val fields = envelope.split('|').toMutableList()

        // Открытая часть конверта не подписана: подменить узлы в ней можно, но
        // зачисление идёт по подписанным конвертам внутри (ReferralReceiptVerifier
        // сверяет их с этими полями и с фактическим отправителем).
        val swapped = fields.toMutableList()
        swapped[3] = ReferralFixtures.STRANGER
        val packet = ReferralWire.parse(swapped.joinToString("|"))
        assertTrue(packet is ReferralWire.Packet.SignedAttribution)
        val signed = packet as ReferralWire.Packet.SignedAttribution
        assertEquals(ReferralFixtures.STRANGER, signed.inviteeNodeId)
        assertTrue(ReferralWire.decode(signed.tokenB64)!!.contentEquals(ReferralFixtures.token(inviter)))
    }

    @Test
    fun tamperedSignedEnvelopesAreRejected() {
        val envelope = ReferralFixtures.signedEnvelope()
        val fields = envelope.split('|').toMutableList()

        // Самоприглашение
        val selfInvite = fields.toMutableList()
        selfInvite[4] = fields[3]
        assertNull(ReferralWire.parse(selfInvite.joinToString("|")))

        // Нечисловая метка времени
        val badTime = fields.toMutableList()
        badTime[5] = "вчера"
        assertNull(ReferralWire.parse(badTime.joinToString("|")))

        // Пустой криптоконверт
        val emptyToken = fields.toMutableList()
        emptyToken[6] = ""
        assertNull(ReferralWire.parse(emptyToken.joinToString("|")))

        // Лишний/недостающий сегмент
        assertNull(ReferralWire.parse((fields + "хвост").joinToString("|")))
        assertNull(ReferralWire.parse(fields.dropLast(1).joinToString("|")))
        // Чужой префикс
        assertNull(ReferralWire.parse(envelope.replace("APUREF1", "APUGRP1")))
        assertNull(ReferralWire.parse(null))
    }

    @Test
    fun oversizedEnvelopeIsDropped() {
        val huge = ReferralFixtures.signedEnvelope() + "x".repeat(ReferralWire.MAX_ENVELOPE_CHARS)
        assertTrue(huge.length > ReferralWire.MAX_ENVELOPE_CHARS)
        assertNull(ReferralWire.parse(huge))
        assertTrue(!ReferralWire.isReferralPacket(huge))
    }

    @Test
    fun isReferralPacketOnlyMatchesOwnPrefix() {
        assertTrue(ReferralWire.isReferralPacket(ReferralFixtures.signedEnvelope()))
        assertTrue(!ReferralWire.isReferralPacket("APUGRP1|msg|1|payload"))
        assertTrue(!ReferralWire.isReferralPacket("привет, APUREF1|attr|2"))
        assertTrue(!ReferralWire.isReferralPacket(null))
    }

    @Test
    fun canonicalNodeIdAcceptsBothFormsAndRejectsTheRest() {
        assertEquals("pk_" + "ab".repeat(16), ReferralWire.canonicalNodeId(" PK_" + "AB".repeat(16) + " "))
        assertEquals("pk_" + "ab".repeat(32), ReferralWire.canonicalNodeId("pk_" + "AB".repeat(32)))
        assertNull(ReferralWire.canonicalNodeId("pk_" + "ab".repeat(15)))
        assertNull(ReferralWire.canonicalNodeId("pk_" + "ab".repeat(33)))
        assertNull(ReferralWire.canonicalNodeId("pk_" + "z".repeat(32)))
        assertNull(ReferralWire.canonicalNodeId(""))
        assertNull(ReferralWire.canonicalNodeId(null))
    }

    @Test
    fun base64UrlHelpersRoundTrip() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        val encoded = ReferralWire.encode(bytes)
        assertTrue(encoded.none { it == '=' || it == '+' || it == '/' })
        assertTrue(bytes.contentEquals(ReferralWire.decode(encoded)!!))
        assertNull(ReferralWire.decode("не base64url !!!"))
    }

    @Test
    fun worstCaseEnvelopeStillFitsTheTransportLimit() {
        // Худший случай: длинные узлы (64 hex) и максимальный токен 512 байта.
        val longInvitee = "pk_" + "ab".repeat(32)
        val longInviter = "pk_" + "cd".repeat(32)
        val envelope = ReferralWire.buildSignedAttribution(
            longInvitee,
            longInviter,
            now,
            ByteArray(ReferralReceipt.MAX_TOKEN_BYTES),
            ReferralFixtures.binding(longInvitee),
        )
        assertNotNull(envelope)
        // 512 байт токена в base64url дают 683 символа: конверт остаётся в
        // пределе 2048 и тем более в транспортном пределе 16 КБ.
        assertTrue(envelope!!.length <= ReferralWire.MAX_ENVELOPE_CHARS)
        assertTrue(ReferralWire.parse(envelope) is ReferralWire.Packet.SignedAttribution)
    }
}
