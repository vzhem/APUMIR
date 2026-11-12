package com.vladimir.messenger.data.mirror

import com.vladimir.messenger.data.reply.DirectReplyWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorReplyTrustTest {
    @Test fun pairedRoomKeyRequiresAnAuthenticatedFrameAndVerifiedSourceFlag() {
        val key = ByteArray(32) { it.toByte() }
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        val wire = MirrorFrameCipher.seal(key, "{\"ra\":true,\"r\":\"APUDMR1|example\"}")!!
        assertTrue(MirrorReplyTrust.verified(true, key, wire, { null }, { null }))
        assertFalse(MirrorReplyTrust.verified(false, key, wire, { error("must not read local") }, { error("must not read signer") }))
        assertFalse(MirrorReplyTrust.verified(true, otherKey, wire, { null }, { null }))
        val tampered = wire.dropLast(2) + if (wire.takeLast(2) == "AA") "BB" else "AA"
        assertFalse(MirrorReplyTrust.verified(true, key, tampered, { null }, { null }))
    }

    @Test fun legacyFallbackRequiresIdenticalLocalBindingNotJustAClaimedNodeId() {
        val local = byteArrayOf(1, 2, 3)
        val differentSelfClaim = byteArrayOf(1, 2, 4)
        assertFalse(MirrorReplyTrust.verified(false, null, "legacy", { error("must not read local") }, { error("must not read signer") }))
        assertFalse(MirrorReplyTrust.verified(true, null, "legacy", { local }, { differentSelfClaim }))
        assertFalse(MirrorReplyTrust.verified(true, null, "legacy", { null }, { local }))
        assertFalse(MirrorReplyTrust.verified(true, null, "legacy", { local }, { null }))
        assertTrue(MirrorReplyTrust.verified(true, null, "legacy", { local }, { local.copyOf() }))
    }

    @Test fun outerKindRelabelCannotExposeReplyInTheLegacyField() {
        val packet = DirectReplyWire.build("parent", "target", "Alice", "quoted")
        assertNull(MirrorReplyPayload.forSend(packet, false))
        val sent = MirrorReplyPayload.forSend(packet, true)!!
        assertEquals("reply2", sent.kind)
        assertEquals("", sent.legacyText)
        assertEquals(packet, MirrorReplyPayload.forReceive("reply2", sent.legacyText, sent.replyText))
        // A broker may change the unauthenticated outer kind; an old env
        // receiver reads only legacyText and cannot see the direct sidecar.
        assertEquals("", MirrorReplyPayload.forReceive("env", sent.legacyText, sent.replyText))
        assertNull(MirrorReplyPayload.forReceive("reply2", packet, ""))
        val ordinary = MirrorReplyPayload.forSend("ack|message", false)!!
        assertEquals("env", ordinary.kind)
        assertEquals("ack|message", MirrorReplyPayload.forReceive("env", ordinary.legacyText, ordinary.replyText))
    }
}
