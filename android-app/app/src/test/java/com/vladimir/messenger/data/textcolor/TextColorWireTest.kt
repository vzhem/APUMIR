package com.vladimir.messenger.data.textcolor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextColorWireTest {
    @Test
    fun emptyColoursKeepLegacyPlainText() {
        assertEquals("hello", TextColorWire.wrap("hello", "", ""))
        assertNull(TextColorWire.decode("hello"))
    }

    @Test
    fun envelopeRoundTripKeepsBodyAndBothColours() {
        val raw = TextColorWire.wrap("текст |\n с переводом", "blue", "pink")
        val decoded = TextColorWire.decode(raw) ?: error("missing colour envelope")
        assertEquals("текст |\n с переводом", decoded.text)
        assertEquals("blue", decoded.textColor)
        assertEquals("pink", decoded.nameColor)
    }

    @Test
    fun unknownColoursAreNormalizedToDefault() {
        val body = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("body".toByteArray(Charsets.UTF_8))
        val decoded = TextColorWire.decode("${TextColorWire.PREFIX}|not-a-colour|also-bad|$body")
            ?: error("missing colour envelope")
        assertEquals(TextColorPalette.DEFAULT_ID, decoded.textColor)
        assertEquals(TextColorPalette.DEFAULT_ID, decoded.nameColor)
    }
}
