package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакеты роя APK (docs/UPDATE_SEEDING.md): `upk`, `upwant`, `upnone`.
 * Старые телефоны эти виды не знают и молча отбрасывают — проверяем,
 * что наши строки разборчивы только в свои виды и не ломают разбор
 * чужих.
 */
class GroupWireUpdateTest {

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun updatePackRoundTrip() {
        val text = GroupWire.buildUpdatePack("11.70.29", sha, 37_930_406L, 1_758_000_000_000L)
        val parsed = GroupWire.parse(text)
        assertTrue(parsed is GroupWire.Packet.UpdatePack)
        val pack = parsed as GroupWire.Packet.UpdatePack
        assertEquals("11.70.29", pack.version)
        assertEquals(sha, pack.sha256)
        assertEquals(37_930_406L, pack.sizeBytes)
        assertEquals(1_758_000_000_000L, pack.atMs)
    }

    @Test
    fun updateWantRoundTripWithBinding() {
        val binding = ByteArray(180) { it.toByte() }
        val text = GroupWire.buildUpdateWant("11.70.29", sha, binding)
        val parsed = GroupWire.parse(text)
        assertTrue(parsed is GroupWire.Packet.UpdateWant)
        val want = parsed as GroupWire.Packet.UpdateWant
        assertEquals("11.70.29", want.version)
        assertEquals(sha, want.sha256)
        assertEquals(binding.toList(), want.binding.toList())
    }

    @Test
    fun updateWantRoundTripWithoutBinding() {
        val text = GroupWire.buildUpdateWant("11.70.29", sha, ByteArray(0))
        val parsed = GroupWire.parse(text) as? GroupWire.Packet.UpdateWant
        assertTrue(parsed != null)
        assertTrue(parsed!!.binding.isEmpty())
    }

    @Test
    fun updateNoneRoundTrip() {
        val text = GroupWire.buildUpdateNone("11.70.29", sha)
        val parsed = GroupWire.parse(text)
        assertTrue(parsed is GroupWire.Packet.UpdateNone)
        val none = parsed as GroupWire.Packet.UpdateNone
        assertEquals("11.70.29", none.version)
        assertEquals(sha, none.sha256)
    }

    @Test
    fun malformedUpdatePacketsAreDropped() {
        // Неверная версия.
        assertNull(GroupWire.parse("APUGRP1|upk|v11.70|${sha}|1|1"))
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29-beta|${sha}|1|1"))
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29.1.9|${sha}|1|1"))
        // Неверный хэш.
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29|zz|1|1"))
        // Отрицательный размер.
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29|${sha}|-1|1"))
        // Избыток/недостаток полей.
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29|${sha}|1"))
        assertNull(GroupWire.parse("APUGRP1|upk|11.70.29|${sha}|1|1|extra"))
        assertNull(GroupWire.parse("APUGRP1|upnone|11.70.29"))
        // Сломанная base64 в привязке.
        assertNull(GroupWire.parse("APUGRP1|upwant|11.70.29|${sha}|$$$"))
        // Пустая версия в слоте группы отбрасывается общим стражем разбора.
        assertNull(GroupWire.parse("APUGRP1|upk||${sha}|1|1"))
    }

    @Test
    fun updatePackDoesNotClashWithFileNone() {
        // fnone — 4 поля, upnone — 4 поля: разбор по виду, не по длине.
        val want = GroupWire.buildFileNone("grp1", sha)
        assertTrue(GroupWire.parse(want) is GroupWire.Packet.FileNone)
        val none = GroupWire.buildUpdateNone("11.70.29", sha)
        assertTrue(GroupWire.parse(none) is GroupWire.Packet.UpdateNone)
    }

    @Test
    fun isUpdateVersionStrictlyNumeric() {
        assertTrue(GroupWire.isUpdateVersion("11.70.29"))
        assertTrue(GroupWire.isUpdateVersion("1"))
        assertTrue(GroupWire.isUpdateVersion("11.70.29.1"))
        assertFalse(GroupWire.isUpdateVersion(""))
        assertFalse(GroupWire.isUpdateVersion("v11.70"))
        assertFalse(GroupWire.isUpdateVersion("11.70.29 " ))
        assertFalse(GroupWire.isUpdateVersion("11..70"))
        assertFalse(GroupWire.isUpdateVersion("11.70.29.1.1"))
        assertFalse(GroupWire.isUpdateVersion("12345.1"))
    }

    @Test
    fun bindingLimitHoldsForUpdateWant() {
        val ok = ByteArray(512)
        GroupWire.buildUpdateWant("11.70.29", sha, ok)
        try {
            GroupWire.buildUpdateWant("11.70.29", sha, ByteArray(513))
            throw AssertionError("binding too long must be rejected")
        } catch (expected: IllegalArgumentException) {
            // Ожидаемо.
        }
    }
}
