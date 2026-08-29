package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferralWireTest {

    private val invitee = "pk_" + "11".repeat(32)
    private val inviter = "pk_" + "22".repeat(32)
    private val shortInvitee = "pk_" + "ab".repeat(16)
    private val nonce = ByteArray(ReferralWire.NONCE_BYTES) { it.toByte() }
    private val createdAt = 1_800_000_000_000L

    private fun build(
        from: String = invitee,
        to: String = inviter,
        at: Long = createdAt,
        nonceBytes: ByteArray = nonce,
    ): String? = ReferralWire.buildAttribution(from, to, at, nonceBytes)

    @Test
    fun attributionRoundTrip() {
        val envelope = build()
        assertNotNull(envelope)
        val parsed = ReferralWire.parse(envelope)
        assertNotNull(parsed)
        assertEquals(invitee, parsed!!.inviteeNodeId)
        assertEquals(inviter, parsed.inviterNodeId)
        assertEquals(createdAt, parsed.createdAtMs)
        assertTrue(parsed.nonce.isNotEmpty())
    }

    /**
     * Регистр не должен создавать два разных узла: иначе один и тот же
     * приглашённый засчитался бы дважды, придя из ссылок разного вида.
     */
    @Test
    fun upperCaseNodeIdsAreCanonicalisedToLowercase() {
        val envelope = build(from = invitee.uppercase(), to = inviter.uppercase())
        val parsed = ReferralWire.parse(envelope)
        assertEquals(invitee, parsed?.inviteeNodeId)
        assertEquals(inviter, parsed?.inviterNodeId)
    }

    @Test
    fun shortNodeIdFormIsAccepted() {
        val parsed = ReferralWire.parse(build(from = shortInvitee))
        assertEquals(shortInvitee, parsed?.inviteeNodeId)
    }

    @Test
    fun envelopeStaysWithinTheDeclaredBound() {
        val envelope = build()!!
        assertTrue(envelope.length <= ReferralWire.MAX_ENVELOPE_CHARS)
    }

    @Test
    fun ownInviteCannotBeBuilt() {
        assertNull(build(from = inviter, to = inviter))
    }

    @Test
    fun buildRejectsBadInput() {
        assertNull(build(nonceBytes = ByteArray(ReferralWire.NONCE_BYTES - 1)))
        assertNull(build(at = 0L))
        assertNull(build(at = -1L))
        assertNull(build(from = "not-a-node"))
        assertNull(build(to = "pk_ZZ" + "22".repeat(31)))
        assertNull(build(from = "pk_" + "11".repeat(31)))
    }

    @Test
    fun parseRejectsForeignAndBrokenEnvelopes() {
        val good = build()!!
        assertNull(ReferralWire.parse(null))
        assertNull(ReferralWire.parse(""))
        assertNull(ReferralWire.parse("обычное сообщение чата"))
        assertNull(ReferralWire.parse("APUGRP1|msg|grp|topic|dGV4dA"))
        assertNull(ReferralWire.parse(good.removePrefix("APUREF1|")))
        assertNull(ReferralWire.parse(good.replace("|attr|", "|other|")))
        assertNull(ReferralWire.parse(good.replace("|1|", "|2|")))
        assertNull(ReferralWire.parse("$good|extra"))
        assertNull(ReferralWire.parse(good.substringBeforeLast('|')))
        assertNull(ReferralWire.parse(good.replace(invitee, "pk_nonsense")))
        assertNull(ReferralWire.parse(good.replace(createdAt.toString(), "не-число")))
        assertNull(ReferralWire.parse(good.replace(createdAt.toString(), "0")))
        // nonce короче 16 байт: 11 символов base64url дают 8 байт
        assertNull(ReferralWire.parse(good.substringBeforeLast('|') + "|AAAAAAAAAAA"))
    }

    /** Конверт с собственным адресом в поле inviter — это самоприглашение. */
    @Test
    fun parseRejectsSelfAddressedEnvelope() {
        val parts = build()!!.split('|').toMutableList()
        parts[3] = inviter
        assertNull(ReferralWire.parse(parts.joinToString("|")))
    }

    @Test
    fun isReferralPacketMatchesOnlyOurPrefixAndBound() {
        assertTrue(ReferralWire.isReferralPacket(build()))
        assertTrue(ReferralWire.isReferralPacket("APUREF1|broken"))
        assertFalse(ReferralWire.isReferralPacket(null))
        assertFalse(ReferralWire.isReferralPacket("APUGRP1|msg|g|t|dA"))
        assertFalse(ReferralWire.isReferralPacket("привет"))
        assertFalse(ReferralWire.isReferralPacket("APUREF1|" + "x".repeat(ReferralWire.MAX_ENVELOPE_CHARS)))
    }

    @Test
    fun canonicalNodeIdRules() {
        assertEquals(invitee, ReferralWire.canonicalNodeId("  $invitee  "))
        assertEquals(invitee, ReferralWire.canonicalNodeId(invitee.uppercase()))
        assertNull(ReferralWire.canonicalNodeId(null))
        assertNull(ReferralWire.canonicalNodeId(""))
        assertNull(ReferralWire.canonicalNodeId("pk_"))
        assertNull(ReferralWire.canonicalNodeId(invitee + "0"))
        assertNull(ReferralWire.canonicalNodeId(invitee.replaceFirst("1", "G")))
    }
}
