package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Репост нескольких фото уходит одной картинкой-сеткой с подписью.
 * Проверяем раскладку (без Android) и укорачивание подписи.
 */
class PhotoCollageTest {

    @Test
    fun rowsAreBalancedFullerFirst() {
        assertEquals(listOf(1), PhotoCollage.rows(1))
        assertEquals(listOf(2), PhotoCollage.rows(2))
        assertEquals(listOf(2, 1), PhotoCollage.rows(3))
        assertEquals(listOf(2, 2), PhotoCollage.rows(4))
        assertEquals(listOf(3, 2), PhotoCollage.rows(5))
        assertEquals(listOf(3, 3), PhotoCollage.rows(6))
    }

    @Test
    fun cellsFillWidthAndDoNotOverlap() {
        for (count in 1..InlineImage.MAX_PHOTOS) {
            val cells = PhotoCollage.layout(count, width = 1080, gap = 6)
            assertEquals(count, cells.size)
            // Каждый ряд начинается с нуля и заканчивается на правом краю.
            val byRow = cells.groupBy { it.top }.values
            byRow.forEach { row ->
                assertEquals(0, row.minOf { it.left })
                assertEquals(1080, row.maxOf { it.right })
                row.sortedBy { it.left }.zipWithNext().forEach { (a, b) ->
                    assertEquals(a.right + 6, b.left)
                }
            }
            // Ряды идут сверху вниз с зазором.
            byRow.map { it.first() }.zipWithNext().forEach { (a, b) ->
                assertEquals(a.bottom + 6, b.top)
            }
        }
    }

    @Test
    fun singlePhotoCellIsNotAbsurdlyTall() {
        val cell = PhotoCollage.layout(1, width = 1080).single()
        assertTrue(cell.height <= 810)
        val portrait = PhotoCollage.layout(1, width = 1080, portrait = true).single()
        assertTrue(portrait.height <= 1080)
    }

    @Test
    fun cropKeepsCellProportionsAndCentres() {
        // Широкий исходник в квадратную клетку - режем бока поровну.
        val wide = PhotoCollage.crop(400, 200, 100, 100)
        assertEquals(200, wide.width)
        assertEquals(200, wide.height)
        assertEquals(100, wide.left)
        assertEquals(0, wide.top)
        // Высокий исходник в широкую клетку - режем верх и низ.
        val tall = PhotoCollage.crop(200, 400, 200, 100)
        assertEquals(200, tall.width)
        assertEquals(100, tall.height)
        assertEquals(150, tall.top)
    }

    @Test
    fun captionKeepsLinkWhenTrimmed() {
        val link = "https://p2p-relay.1985vzhem.workers.dev/i/abc"
        val body = "слово ".repeat(400)
        val text = PhotoShare.buildText("Заголовок", body, link)
        assertTrue(text.length > PhotoShare.MAX_CAPTION_CHARS)
        val caption = PhotoShare.captionFor(text, link)
        assertTrue(caption.length <= PhotoShare.MAX_CAPTION_CHARS)
        assertTrue(caption.endsWith("Открыть в APU:\n" + link))
        assertTrue(caption.startsWith("Заголовок"))
        assertTrue(caption.contains("…"))
    }

    @Test
    fun shortCaptionIsUntouched() {
        val text = PhotoShare.buildText("Заголовок", "Текст", "https://x.y/i/1")
        assertEquals(text, PhotoShare.captionFor(text, "https://x.y/i/1"))
        assertEquals("Заголовок\n\nТекст\n\nОткрыть в APU:\nhttps://x.y/i/1", text)
    }

    @Test
    fun textWithoutLinkOrDuplicateTitle() {
        assertEquals("Только текст", PhotoShare.buildText("", "Только текст", null))
        assertEquals("Одно и то же", PhotoShare.buildText("Одно и то же", "Одно и то же", null))
    }
}
