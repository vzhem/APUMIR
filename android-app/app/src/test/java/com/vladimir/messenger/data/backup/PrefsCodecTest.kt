package com.vladimir.messenger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsCodecTest {

    @Test
    fun roundTripAllTypes() {
        val entries = linkedMapOf<String, Any>(
            "name" to "Иван Петров\nс переносом и пробелами",
            "flag" to true,
            "off" to false,
            "count" to 42,
            "big" to 1_700_000_000_000L,
            "ratio" to 0.1f,
            "tags" to setOf("a b", "c", ""),
            "empty_set" to emptySet<String>(),
            "ключ с пробелом" to "значение",
        )
        val decoded = PrefsCodec.decode(PrefsCodec.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun emptyMapRoundTrips() {
        assertEquals(emptyMap<String, Any>(), PrefsCodec.decode(PrefsCodec.encode(emptyMap<String, Any>())))
    }

    @Test
    fun nullAndUnknownValuesAreSkipped() {
        val entries = mapOf("a" to null, "b" to 1.5, "c" to "ok")
        assertEquals(mapOf("c" to "ok"), PrefsCodec.decode(PrefsCodec.encode(entries)))
    }

    @Test
    fun damagedTextGivesNothingAtAll() {
        assertNull(PrefsCodec.decode(""))
        assertNull(PrefsCodec.decode("apu-prefs-v2\nS YQ YQ\n"))
        assertNull(PrefsCodec.decode("apu-prefs-v1\nS YQ\n"))                 // не хватает значения
        assertNull(PrefsCodec.decode("apu-prefs-v1\nX YQ YQ\n"))              // неизвестный тип
        assertNull(PrefsCodec.decode("apu-prefs-v1\nI YQ notanumber\n"))
        assertNull(PrefsCodec.decode("apu-prefs-v1\nB YQ 2\n"))
        assertNull(PrefsCodec.decode("apu-prefs-v1\nS YQ ***\n"))             // битый base64
        // Одна битая строка портит всё: половину настроек не восстанавливаем.
        assertNull(PrefsCodec.decode("apu-prefs-v1\nS YQ YQ\nS YQ\n"))
    }
}
