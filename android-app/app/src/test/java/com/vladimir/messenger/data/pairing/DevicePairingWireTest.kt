package com.vladimir.messenger.data.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePairingWireTest {

    @Test
    fun comparisonCodeIsRecognizedButIsNotAcceptedAsQrOffer() {
        assertTrue(DevicePairingWire.looksLikeComparisonCode("649114"))
        assertTrue(DevicePairingWire.looksLikeComparisonCode("649 114"))
        assertNull(DevicePairingWire.parseQr("649114"))
        assertNull(DevicePairingWire.parseQr("649 114"))
    }

    @Test
    fun pastedQrTextMayContainClipboardWhitespace() {
        val offer = byteArrayOf(1, 2, 3, 4, 5, 6)
        val encoded = android.util.Base64.encodeToString(
            offer,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val pasted = "apupair:${encoded.substring(0, 3)}\n ${encoded.substring(3)}"
        assertArrayEquals(offer, DevicePairingWire.parseQr(pasted))
    }

    @Test
    fun emptyQrPayloadIsRejected() {
        assertNull(DevicePairingWire.parseQr("apupair:"))
    }

    @Test
    fun shortPairingCodeFindsTheFullOfferWithoutReplacingSas() {
        val offer = byteArrayOf(2, 4, 6, 8, 10, 12, 14, 16)
        val encoded = android.util.Base64.encodeToString(
            offer,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val qr = "apupair:$encoded"
        val code = DevicePairingWire.pairingCode(qr)
        assertNotNull(code)
        assertEquals(6, code!!.length)
        assertEquals(code, DevicePairingWire.normalizePairingCode(" ${code.substring(0, 3)} ${code.substring(3)} "))
        assertEquals(code.substring(0, 3) + " " + code.substring(3), DevicePairingWire.formatPairingCode(code))

        val body = DevicePairingWire.rendezvousPayload("pk_primary", code, qr)
        assertNotNull(body)
        val event = "APUPAIRCODE1:$code:$qr"
        val parsed = DevicePairingWire.parseRendezvous(event)
        assertNotNull(parsed)
        assertEquals(code, parsed!!.code)
        assertEquals(qr, parsed.qrText)
        assertTrue(body!!.startsWith("APUPAIRCODE1|pk_primary|$code|apupair:"))
    }

    @Test
    fun invalidPairingCodeAndRendezvousAreRejected() {
        assertNull(DevicePairingWire.normalizePairingCode("12345"))
        assertNull(DevicePairingWire.normalizePairingCode("1234567"))
        assertNull(DevicePairingWire.parseRendezvous("APUPAIRCODE1:123456:apupair:"))
        assertNull(DevicePairingWire.rendezvousPayload("not-a-node", "123456", "apupair:AQ"))
    }
}
