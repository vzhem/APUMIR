package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт настроек кнопки SOS (`soscfg`): сборка и строгий разбор.
 * Старые телефоны вид не знают и молча отбрасывают - это проверяется тем,
 * что разбор чужих и битых конвертов даёт null, а не исключение.
 */
class GroupWireSosTest {

    @Test
    fun sosSettingsRoundTrip() {
        for (role in GroupSos.PRESS_ROLES) {
            for (count in GroupSos.COUNT_CHOICES) {
                for (enabled in listOf(true, false)) {
                    val envelope = GroupWire.buildSosSettings("group-1", enabled, count, role)
                    val packet = GroupWire.parse(envelope)
                    assertTrue(packet is GroupWire.Packet.SosSettings)
                    val sos = packet as GroupWire.Packet.SosSettings
                    assertEquals("group-1", sos.groupId)
                    assertEquals(enabled, sos.enabled)
                    assertEquals(count, sos.count)
                    assertEquals(role, sos.pressRole)
                }
            }
        }
    }

    @Test
    fun buildClampsCountAndRole() {
        val packet = GroupWire.parse(GroupWire.buildSosSettings("g", true, 99, "bogus"))
        assertTrue(packet is GroupWire.Packet.SosSettings)
        val sos = packet as GroupWire.Packet.SosSettings
        assertEquals(GroupSos.MAX_COUNT, sos.count)
        assertEquals(GroupSos.PRESS_ALL, sos.pressRole)
    }

    @Test
    fun parseRejectsOutOfRangeCountsAndUnknownRoles() {
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|2|ALL"))
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|6|ALL"))
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|x|ALL"))
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|3|SOME"))
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|2|3|ALL"))
    }

    @Test
    fun parseRejectsWrongShape() {
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|3"))
        assertNull(GroupWire.parse("APUGRP1|soscfg|g|1|3|ALL|extra"))
        assertNull(GroupWire.parse("APUGRP1|soscfg||1|3|ALL"))
        assertNull(GroupWire.parse("soscfg|g|1|3|ALL"))
        assertNull(GroupWire.parse("APUGRP1|unknownkind|g|1|3|ALL"))
    }
}
