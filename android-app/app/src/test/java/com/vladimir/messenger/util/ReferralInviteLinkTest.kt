package com.vladimir.messenger.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ReferralInviteLinkTest {
    @Test
    fun canonicalRoundTripUsesHttpsAndUnpaddedBase64Url() {
        val token = byteArrayOf(1, 2, 3, 0x7f, 0x80.toByte(), 0xff.toByte())
        val link = ReferralInviteLink.create(token)

        assertTrue(link.startsWith("https://apumir.app/i?r="))
        assertTrue(!link.substringAfter("?r=").contains('='))
        assertArrayEquals(token, ReferralInviteLink.parse(link)?.token)
    }

    @Test
    fun optionalOpaqueInvitePathIsAccepted() {
        val token = byteArrayOf(9, 8, 7)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        val parsed = ReferralInviteLink.parse("https://apumir.app/i/campaign_01?r=$encoded")

        assertArrayEquals(token, parsed?.token)
    }

    @Test
    fun duplicateTokenAmbiguityIsRejected() {
        assertNull(ReferralInviteLink.parse("https://apumir.app/i?r=AQ&r=Ag"))
    }

    @Test
    fun wrongSchemeHostPortUserInfoPathAndFragmentAreRejected() {
        val invalid = listOf(
            "http://apumir.app/i?r=AQ",
            "https://evil.example/i?r=AQ",
            "https://apumir.app.evil.example/i?r=AQ",
            "https://user@apumir.app/i?r=AQ",
            "https://apumir.app:444/i?r=AQ",
            "https://apumir.app/chat?r=AQ",
            "https://apumir.app/i?r=AQ#fragment",
        )
        invalid.forEach { assertNull("must reject $it", ReferralInviteLink.parse(it)) }
    }

    @Test
    fun malformedPaddedAndOversizedTokensAreRejectedWithoutThrowing() {
        assertNull(ReferralInviteLink.parse("https://apumir.app/i?r="))
        assertNull(ReferralInviteLink.parse("https://apumir.app/i?r=AQ=="))
        assertNull(ReferralInviteLink.parse("https://apumir.app/i?r=***"))
        assertNull(ReferralInviteLink.parse("not a URL"))

        val oversized = ByteArray(ReferralInviteLink.MAX_TOKEN_BYTES + 1) { 1 }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(oversized)
        assertNull(ReferralInviteLink.parse("https://apumir.app/i?r=$encoded"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun createRejectsEmptyToken() {
        ReferralInviteLink.create(byteArrayOf())
    }

    @Test
    fun parserDoesNotAliasCallerOrDecodedStorage() {
        val original = byteArrayOf(4, 5, 6)
        val link = ReferralInviteLink.create(original)
        original[0] = 99
        val parsed = ReferralInviteLink.parse(link)!!
        assertEquals(4, parsed.token[0].toInt())
    }
}
