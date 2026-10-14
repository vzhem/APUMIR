package com.vladimir.messenger.data.security

import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.file.FileUdpWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingPayloadPolicyTest {
    private val peer = "pk_${"a".repeat(64)}"
    private val envelope = SealedWire.encode(ByteArray(48) { 1 }, ByteArray(12) { 2 }, ByteArray(32) { 3 })

    @Test
    fun `private text is never forwarded when peer binding or local key is unavailable`() {
        assertNull(OutgoingPayloadPolicy.prepare(peer, "Привет", seal = { null }))
        assertNull(OutgoingPayloadPolicy.prepare(peer, "Привет", seal = { "Привет" }))
        assertEquals(envelope, OutgoingPayloadPolicy.prepare(peer, "Привет", seal = { envelope }))
        assertNull(OutgoingPayloadPolicy.prepare("grp_other", "Привет", seal = { envelope }))
    }

    @Test
    fun `first message after adding a contact cannot leak before HELLO`() {
        var called = false
        assertNull(OutgoingPayloadPolicy.prepare(peer, "first private message") {
            called = true
            null // peer has not sent a signed HELLO yet
        })
        assertTrue(called)
    }

    @Test
    fun `only valid public bootstrap and discovery bypass sealing`() {
        val hello = FileTransferWire.encodeHelloBinding(ByteArray(96) { 7 })
        val udp = FileUdpWire.buildUdpSignal(
            FileUdpWire.KIND_UFSEEK, ByteArray(96) { 7 },
            listOf("192.0.2.1" to 4242), ByteArray(FileUdpWire.SESSION_TAG_BYTES) { 1 },
        )
        for (signal in listOf(hello, "APULAN1|req", "APULAN1|offer|192.0.2.1|1234", udp)) {
            assertTrue(OutgoingPayloadPolicy.isPublicControl(signal))
            assertEquals(signal, OutgoingPayloadPolicy.prepare(peer, signal) { error("must not seal bootstrap") })
        }
        for (private in listOf("Привет", "apu-file1|encoded", "APUGRP1|post|body", "apu-file-hello1|!!!", "APULAN1|private", "APUUDP1|private")) {
            assertFalse(OutgoingPayloadPolicy.isPublicControl(private))
            assertNull(OutgoingPayloadPolicy.prepare(peer, private) { null })
        }
        assertFalse(OutgoingPayloadPolicy.isPublicControl("APULAN1|req|" + "x".repeat(300)))
    }

    @Test
    fun `malformed sealed prefix is not sent as an envelope`() {
        assertEquals(envelope, OutgoingPayloadPolicy.prepare(peer, envelope) { error("already sealed") })
        assertNull(OutgoingPayloadPolicy.prepare(peer, "APUSEAL1|not-an-envelope") {
            error("do not wrap an invalid sealed prefix")
        })
    }
}
