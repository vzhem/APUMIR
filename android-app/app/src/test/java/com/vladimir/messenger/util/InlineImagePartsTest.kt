package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фотографии поста едут кусками отдельными сообщениями. Проверяем нарезку,
 * склейку и то, что служебные строки не попадают в текст.
 */
class InlineImagePartsTest {

    @Test
    fun partsNeverExceedBrokerBudget() {
        val b64 = "A".repeat(InlineImage.MAX_B64_CHARS)
        val parts = InlineImage.splitPhoto(1, b64)
        assertEquals(3, parts.size)
        parts.forEach { text ->
            val part = InlineImage.parsePart(text)!!
            assertTrue(part.b64.length <= InlineImage.MAX_PART_B64_CHARS)
            assertEquals(3, part.total)
            assertTrue(InlineImage.isPart(text))
        }
        assertEquals(listOf(b64), InlineImage.assemble(parts))
    }

    @Test
    fun assembleOrdersPhotosAndToleratesShuffleAndDuplicates() {
        val one = "1".repeat(4000)
        val two = "2".repeat(100)
        val parts = InlineImage.splitPhoto(2, two) + InlineImage.splitPhoto(1, one)
        val shuffled = parts.reversed() + parts.first()
        assertEquals(listOf(one, two), InlineImage.assemble(shuffled))
    }

    /** Фото без одного куска не показываем вовсе - лучше подождать, чем показать битое. */
    @Test
    fun incompletePhotoIsSkipped() {
        val full = "Z".repeat(7000)
        val parts = InlineImage.splitPhoto(1, full).drop(1)
        assertTrue(InlineImage.assemble(parts).isEmpty())
        // Другое, целое фото при этом собирается.
        val ok = InlineImage.splitPhoto(2, "Q".repeat(10))
        assertEquals(listOf("Q".repeat(10)), InlineImage.assemble(parts + ok))
    }

    @Test
    fun malformedPartsAreIgnored() {
        assertNull(InlineImage.parsePart("APUIMGP1:1/2:abc"))
        assertNull(InlineImage.parsePart("APUIMGP1:0/1/1:abc"))
        assertNull(InlineImage.parsePart("APUIMGP1:1/3/2:abc"))
        assertNull(InlineImage.parsePart("APUIMGP1:1/1/1:"))
        assertNull(InlineImage.parsePart("просто текст"))
        assertFalse(InlineImage.isPart("APUIMG1:abc"))
    }

    @Test
    fun photoCountMarkerIsHiddenFromTextAndCounted() {
        val body = InlineImage.withPhotoCount("Заголовок\nтекст", 3)
        assertEquals(3, InlineImage.photoCount(body))
        assertEquals("Заголовок\nтекст", InlineImage.stripImage(body))
        assertEquals(0, InlineImage.photoCount("без фото"))
        // Пост из одних фотографий: текст пустой, пометка есть.
        val onlyPhotos = InlineImage.withPhotoCount("", 2)
        assertEquals("", InlineImage.stripImage(onlyPhotos))
        assertEquals(2, InlineImage.photoCount(onlyPhotos))
        // Больше потолка не обещаем.
        assertEquals(InlineImage.MAX_PHOTOS, InlineImage.photoCount("APUIMGS1:99"))
    }

    /** Правка меняет слова, служебные строки фотографий остаются. */
    @Test
    fun replaceTextKeepsServiceLines() {
        val old = InlineImage.withPhotoCount("старый текст", 2)
        val edited = InlineImage.replaceText(old, "новый текст")
        assertEquals("новый текст", InlineImage.stripImage(edited))
        assertEquals(2, InlineImage.photoCount(edited))
        // Старый способ (картинка целиком в тексте) тоже переживает правку.
        val legacy = InlineImage.attach("подпись", "AAAA")
        val legacyEdited = InlineImage.replaceText(legacy, "другая подпись")
        assertEquals("AAAA", InlineImage.extractB64(legacyEdited))
        assertEquals("другая подпись", InlineImage.stripImage(legacyEdited))
        // Служебные строки в новом тексте не пролезают.
        val sneaky = InlineImage.replaceText(old, "текст\nAPUIMGS1:6")
        assertEquals(2, InlineImage.photoCount(sneaky))
    }
}
