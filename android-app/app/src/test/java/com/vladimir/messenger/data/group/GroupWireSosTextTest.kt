package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт своего текста тревоги (`sostxt`): сборка и строгий разбор.
 * Отдельный вид нужен, чтобы телефоны без него не теряли остальные
 * настройки SOS из конверта `soscfg`.
 */
class GroupWireSosTextTest {

    @Test
    fun sosTextRoundTripKeepsArbitraryText() {
        val text = "🆘 Пожар в доме 5, нужна помощь! | срочно\nвторая строка"
        val packet = GroupWire.parse(GroupWire.buildSosText("group-1", text))
        assertTrue(packet is GroupWire.Packet.SosText)
        val sostxt = packet as GroupWire.Packet.SosText
        assertEquals("group-1", sostxt.groupId)
        assertEquals(text, sostxt.text)
    }

    @Test
    fun blankTextRoundTripsAsResetToDefault() {
        val packet = GroupWire.parse(GroupWire.buildSosText("g", "   "))
        assertTrue(packet is GroupWire.Packet.SosText)
        assertEquals("", (packet as GroupWire.Packet.SosText).text)
    }

    @Test
    fun buildTrimsAndClampsToMax() {
        val long = "я".repeat(GroupSos.MAX_TEXT_CHARS + 50)
        val packet = GroupWire.parse(GroupWire.buildSosText("g", "  $long  "))
        assertTrue(packet is GroupWire.Packet.SosText)
        assertEquals(GroupSos.MAX_TEXT_CHARS, (packet as GroupWire.Packet.SosText).text.length)
    }

    @Test
    fun parseRejectsOversizeAndWrongShape() {
        fun enc(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val oversize = enc("я".repeat(GroupSos.MAX_TEXT_CHARS + 1))
        assertNull(GroupWire.parse("APUGRP1|sostxt|g|$oversize"))
        assertNull(GroupWire.parse("APUGRP1|sostxt|g"))
        assertNull(GroupWire.parse("APUGRP1|sostxt||${enc("текст")}"))
        assertNull(GroupWire.parse("APUGRP1|sostxt|g|%%%"))
    }

    @Test
    fun effectiveTextFallsBackToDefault() {
        assertEquals(
            com.vladimir.messenger.util.SosMessage.DEFAULT_TEXT,
            GroupSos.effectiveText(null),
        )
        assertEquals(
            com.vladimir.messenger.util.SosMessage.DEFAULT_TEXT,
            GroupSos.effectiveText("   "),
        )
        assertEquals("На помощь!", GroupSos.effectiveText("  На помощь!  "))
    }
}
