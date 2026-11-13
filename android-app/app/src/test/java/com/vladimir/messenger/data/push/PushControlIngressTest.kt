package com.vladimir.messenger.data.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushControlIngressTest {
    @Test fun canonicalControlsAndMalformedFamilyAreReserved() {
        val owner = "ab".repeat(32)
        val target = "cd".repeat(32)
        val offer = PushEpochProtocol.encodeOffer(42, owner, target, "ef".repeat(16))!!
        val revoke = PushEpochProtocol.encodeRevoke(43, owner, target)!!
        listOf(
            offer, revoke, "APUTYP1|APUPUSH1", "APUTYP1|APUPUSH1|",
            "APUTYP1|APUPUSH1|garbage", "APUTYP1|APUPUSH1extra",
            " \t\n$offer", "\u2003APUTYP1|APUPUSH1|bad", "APUTYP1|APUPUSH1|" + "x".repeat(200_000),
        ).forEach { assertTrue(PushControlIngress.isReserved(it)) }
    }

    @Test fun unrelatedTextTypingRankAndHeartPacketsKeepTheirExistingRoutes() {
        listOf(
            "", " ", "hello", "a APUTYP1|APUPUSH1|offer", "APUTYP1|",
            "APUTYP1|0", "APUTYP1|1", "APUTYP1|APURANK1|1|node|3|123",
            "APUTYP1|APURANKREQ1|1", "APUTYP1|APUGAME1|test", "APUTYP1|APUGAMEREQ1|test",
            "APUTYP1|APUPUSH", "APUTYP1|apupush1|", "APUSEAL1|opaque",
        ).forEach { assertFalse(PushControlIngress.isReserved(it)) }
        assertFalse(PushControlIngress.isReserved(null))
    }

    @Test fun reservationIsNotAnAuthenticityOrProtocolValidityDecision() {
        val invalid = "  APUTYP1|APUPUSH1|offer|1|not-a-hash|other|not-an-epoch"
        assertTrue(PushControlIngress.isReserved(invalid))
        assertFalse(PushEpochProtocol.isPacket(invalid))
        assertTrue(PushEpochProtocol.parse(invalid) == null)
    }
}
