package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Room dispatcher and actual transport are exercised separately. */
class MirrorOutgoingFrameTest {
    @Test fun openingPrecedesTrustAndBothSeeTheSameWire() {
        val calls = mutableListOf<String>()
        val row = MirrorOutgoingFrame.checked("wire", {
            calls += "open:$it"
            "row"
        }, {
            calls += "verify:$it"
            true
        })
        assertEquals("row", row)
        assertEquals(listOf("open:wire", "verify:wire"), calls)
    }

    @Test fun failedOpenNeverConsultsTrustOrProducesARow() {
        var checks = 0
        val row = MirrorOutgoingFrame.checked<String>("wire", { null }, { checks++; true })
        assertNull(row)
        assertEquals(0, checks)
    }

    @Test fun pairedRoomKeyAcceptsOnlyAnIntactAuthenticatedFrame() {
        val key = ByteArray(32) { it.toByte() }
        val wire = MirrorFrameCipher.seal(key, "outgoing row")!!
        fun open(value: String) = MirrorFrameCipher.open(key, value)
        fun trust(value: String) = MirrorReplyTrust.verified(true, key, value,
            { error("room key should suffice") }, { error("room key should suffice") })
        assertEquals("outgoing row", MirrorOutgoingFrame.checked(wire, ::open, ::trust))
        val index = wire.length / 2
        val replacement = if (wire[index] == 'A') 'B' else 'A'
        val tampered = wire.substring(0, index) + replacement + wire.substring(index + 1)
        assertNull(MirrorOutgoingFrame.checked(tampered, ::open, ::trust))
    }

    @Test fun anOpenedPayloadStillNeedsTheCorrectPairingKey() {
        val key = ByteArray(32) { it.toByte() }
        val wrong = ByteArray(32) { (it + 1).toByte() }
        val wire = MirrorFrameCipher.seal(key, "outgoing row")!!
        val row = MirrorOutgoingFrame.checked(wire, { MirrorFrameCipher.open(key, it) }, {
            MirrorReplyTrust.verified(true, wrong, it, { null }, { null })
        })
        assertNull(row)
    }

    @Test fun legacyFallbackRequiresTheExactLocalBindingNotAClaimedNode() {
        val local = byteArrayOf(1, 2, 3)
        val differentSigner = byteArrayOf(1, 2, 4)
        fun trySigner(signer: ByteArray?, binding: ByteArray? = local): String? =
            MirrorOutgoingFrame.checked("legacy", { "opened" }, {
                MirrorReplyTrust.verified(true, null, it, { binding }, { signer })
            })
        assertNull(trySigner(differentSigner))
        assertNull(trySigner(null))
        assertNull(trySigner(local, null))
        assertEquals("opened", trySigner(local.copyOf()))
    }

    @Test fun exceptionsCannotTurnAnUnverifiedFrameIntoAnAcceptedRow() {
        var checks = 0
        val openFailure = runCatching {
            MirrorOutgoingFrame.checked<String>("wire", { error("open") }, { checks++; true })
        }
        assertTrue(openFailure.isFailure)
        assertEquals(0, checks)
        val trustFailure = runCatching {
            MirrorOutgoingFrame.checked("wire", { "opened" }, { error("trust") })
        }
        assertTrue(trustFailure.isFailure)
    }
}
