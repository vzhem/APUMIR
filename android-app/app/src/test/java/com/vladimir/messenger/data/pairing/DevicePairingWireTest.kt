package com.vladimir.messenger.data.pairing

import org.junit.Assert.assertArrayEquals
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
}
