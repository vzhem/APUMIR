package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupFileMarkerTest {
    private val sha = "ab".repeat(32)
    private val info = GroupFileMarker.Info(
        sha256 = sha,
        sizeBytes = 3_500_000L,
        mediaType = "application/pdf",
        displayName = "Отчёт за май: итоги | версия 2.pdf",
    )

    @Test
    fun buildAndParseRoundTrip() {
        val line = GroupFileMarker.build(info)
        assertTrue(line.startsWith(GroupFileMarker.PREFIX + sha + ":3500000:"))
        // Имя с двоеточием и разделителем конверта не ломает разбор: оно в base64url.
        assertEquals(4, line.removePrefix(GroupFileMarker.PREFIX).split(':').size)
        assertFalse('|' in line)
        assertEquals(info, GroupFileMarker.parse(line))
    }

    @Test
    fun composePutsCaptionThenCardLast() {
        val text = GroupFileMarker.compose("держите документ", info)
        val lines = text.split('\n')
        assertEquals(3, lines.size)
        assertEquals("держите документ", lines[0])
        assertEquals(GroupFileMarker.caption(info), lines[1])
        assertEquals(GroupFileMarker.build(info), lines[2])
        assertEquals(info, GroupFileMarker.parse(text))
        assertEquals(lines[2], GroupFileMarker.line(text))
        // Без слов - подпись и визитка.
        assertEquals(2, GroupFileMarker.compose("   ", info).split('\n').size)
    }

    @Test
    fun captionIsHumanReadableAndStripped() {
        val caption = GroupFileMarker.caption(info)
        assertEquals("📎 Отчёт за май: итоги | версия 2.pdf (3.3 МБ)", caption)
        val words = "держите документ\n" + caption
        assertEquals("держите документ", GroupFileMarker.stripCaption(words, info))
        // Своя подпись человека остаётся.
        assertEquals("моя подпись", GroupFileMarker.stripCaption("моя подпись", info))
    }

    @Test
    fun serviceLineIsHiddenByInlineImageStrip() {
        val text = GroupFileMarker.compose("текст", info)
        val stripped = InlineImage.stripImage(text)
        assertFalse(stripped.contains(GroupFileMarker.PREFIX))
        assertTrue(stripped.startsWith("текст"))
        // При правке слов визитка сохраняется, как строки фотографий.
        val edited = InlineImage.replaceText(text, "новые слова")
        assertEquals(info, GroupFileMarker.parse(edited))
        assertTrue(edited.startsWith("новые слова"))
    }

    @Test
    fun malformedCardsAreNotFiles() {
        assertNull(GroupFileMarker.parse("просто текст"))
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + "zz" + ":1:YQ:YQ"))
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + sha + ":-1:YQ:YQ"))
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + sha + ":1:YQ"))
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + sha + ":1:!!!:YQ"))
        // Тип без косой черты - не MIME.
        val badType = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("pdf".toByteArray())
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + sha + ":1:$badType:YQ"))
        // Слишком большой размер.
        assertNull(GroupFileMarker.parse(GroupFileMarker.PREFIX + sha + ":${GroupFileMarker.MAX_SIZE_BYTES + 1}:YQ:YQ"))
        assertFalse(GroupFileMarker.has("APUFILE1:"))
    }

    @Test
    fun keyAndSizeFormatting() {
        assertEquals("grp:" + sha, GroupFileMarker.key("grp", sha))
        assertEquals("512 Б", GroupFileMarker.formatSize(512))
        assertEquals("1.5 КБ", GroupFileMarker.formatSize(1536))
        assertEquals("2.0 МБ", GroupFileMarker.formatSize(2L * 1024 * 1024))
        assertTrue(GroupFileMarker.isSha256(sha))
        assertFalse(GroupFileMarker.isSha256(sha.uppercase()))
        assertFalse(GroupFileMarker.isSha256(sha.dropLast(1)))
    }

    /** K2: метка группы в манифесте - только знаки, которые принимает ядро (`is_group_scope`). */
    @Test
    fun groupScopeIsSafeForTheCore() {
        val uuid = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
        assertEquals("grp_$uuid", GroupFileMarker.scope(uuid))
        assertTrue(GroupFileMarker.isScope(GroupFileMarker.scope(uuid)))
        assertFalse(GroupFileMarker.isScope("pk_" + "ab".repeat(16)))
        val odd = GroupFileMarker.scope("id with spaces|and|bars")
        assertTrue(odd.startsWith("grp_"))
        assertEquals("grp_".length + 32, odd.length)
        assertTrue(odd.drop(4).all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(odd, GroupFileMarker.scope("id with spaces|and|bars"))
        assertTrue(GroupFileMarker.scope("x".repeat(100)).length <= 128)
        assertTrue(GroupFileMarker.scope("x".repeat(101)).length <= 128)
    }
}
