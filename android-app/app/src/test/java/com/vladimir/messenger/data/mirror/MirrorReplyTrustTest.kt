package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorReplyTrustTest {
    @Test fun pairedRoomKeyRequiresAnAuthenticatedFrameAndVerifiedSourceFlag() {
        val key = ByteArray(32) { it.toByte() }
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        val wire = MirrorFrameCipher.seal(key, "{\"ra\":true,\"t\":\"APUDMR1|example\"}")!!
        assertTrue(MirrorReplyTrust.verified(true, key, wire) { false })
        assertFalse(MirrorReplyTrust.verified(false, key, wire) { true })
        assertFalse(MirrorReplyTrust.verified(true, otherKey, wire) { false })
        val tampered = wire.dropLast(2) + if (wire.takeLast(2) == "AA") "BB" else "AA"
        assertFalse(MirrorReplyTrust.verified(true, key, tampered) { false })
    }

    @Test fun legacyFallbackRequiresVerifiedSelfSignerAndSourceFlag() {
        var calls = 0
        assertFalse(MirrorReplyTrust.verified(false, null, "legacy") { calls++; true })
        assertTrue(calls == 0)
        assertFalse(MirrorReplyTrust.verified(true, null, "contact-signed") { false })
        assertTrue(MirrorReplyTrust.verified(true, null, "self-signed") { true })
    }
}
