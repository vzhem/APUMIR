package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Длинный текст поста едет кусками отдельными сообщениями (рой, этап 3).
 * Проверяем нарезку по байтам UTF-8, склейку, правки и то, что прошлые
 * версии такие куски прячут, а фотографией не считают.
 */
class InlineTextPartsTest {

    private val cyrillic = "Съешь же ещё этих мягких французских булок, да выпей чаю. "

    @Test
    fun shortTextIsSingleChunk() {
        assertEquals(listOf("привет"), InlineImage.splitText("привет"))
        assertEquals(listOf(""), InlineImage.splitText(""))
        assertNull(InlineImage.textTail("привет"))
        assertEquals("привет", InlineImage.headContent("привет", 0, 0, 0))
    }

    @Test
    fun chunksRespectByteBudgetAndConcatenateBack() {
        val body = cyrillic.repeat(200) // ~22 000 байт UTF-8
        val chunks = InlineImage.splitText(body)
        assertTrue("chunks=${chunks.size}", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= InlineImage.MAX_TEXT_PART_BYTES)
            assertTrue(chunk.isNotEmpty())
        }
        assertEquals(body, chunks.joinToString(""))
        // Разрез по словам: следующий кусок начинается с пробела, а не с
        // середины слова, и куски заполнены почти под завязку.
        chunks.drop(1).forEach { chunk -> assertTrue(chunk.first().isWhitespace()) }
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size >= InlineImage.MAX_TEXT_PART_BYTES * 9 / 10)
        }
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val emoji = "\uD83D\uDE00" // 😀, 4 байта
        val body = emoji.repeat(2_000)
        val chunks = InlineImage.splitText(body, 10)
        chunks.forEach { chunk ->
            assertEquals(0, chunk.length % 2)
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 10)
        }
        assertEquals(body, chunks.joinToString(""))
    }

    @Test
    fun elevenThousandCyrillicLettersFitInMaxParts() {
        val body = cyrillic.repeat(11_000 / cyrillic.length + 1).take(11_000)
        val chunks = InlineImage.splitText(body)
        assertTrue("tail=${chunks.size - 1}", chunks.size - 1 <= InlineImage.MAX_TEXT_PARTS)
    }

    @Test
    fun headContentAndTailRoundTrip() {
        val head = InlineImage.headContent("первый кусок ", 7, 3, 2)
        val lines = head.split('\n')
        assertEquals("первый кусок ", lines[0])
        assertEquals(InlineImage.textTailLine(7, 3), lines[1])
        assertEquals(InlineImage.SET_MARKER + "2", lines[2])
        val tail = InlineImage.textTail(head)!!
        assertEquals(7, tail.rev)
        assertEquals(3, tail.count)
        assertEquals(2, InlineImage.photoCount(head))
        assertTrue(InlineImage.expectsParts(head))
        // Слова без служебных строк - как раньше.
        assertEquals("первый кусок", InlineImage.stripImage(head))
        assertEquals("первый кусок ", InlineImage.headWords(head))
    }

    @Test
    fun textPartRoundTripAndLegacyBehaviour() {
        val part = InlineImage.buildTextPart("msg-1", 5, 2, 3, "текст куска")
        val parsed = InlineImage.parseTextPart(part)!!
        assertEquals("msg-1", parsed.headId)
        assertEquals(5, parsed.rev)
        assertEquals(2, parsed.index)
        assertEquals(3, parsed.total)
        assertEquals("текст куска", parsed.text)
        // Прошлые версии: кусок текста прячется как служебный, но фото не считается.
        assertTrue(InlineImage.isPart(part))
        assertTrue(InlineImage.isTextPart(part))
        assertNull(InlineImage.parsePart(part))
        assertTrue(InlineImage.assemble(listOf(part)).isEmpty())
        // Двоеточие внутри текста куска не ломает разбор.
        val colon = InlineImage.buildTextPart("m", 0, 1, 1, "a:b:c")
        assertEquals("a:b:c", InlineImage.parseTextPart(colon)!!.text)
        // Пометка о продолжении - не кусок.
        assertFalse(InlineImage.isTextPart(InlineImage.textTailLine(0, 2)))
        assertNull(InlineImage.parseTextPart(InlineImage.textTailLine(0, 2)))
    }

    @Test
    fun rejectsBrokenParts() {
        assertNull(InlineImage.parseTextPart("APUIMGP1:0/1/1:"))
        assertNull(InlineImage.parseTextPart("APUIMGP1:0/2/1:m/0:x"))
        assertNull(InlineImage.parseTextPart("APUIMGP1:0/1/1:m:x"))
        assertNull(InlineImage.parseTextPart("APUIMGP1:0/1/1:/0:x"))
        assertNull(InlineImage.parseTextPart("APUIMGP1:1/1/1:m/0:x"))
        assertNull(InlineImage.textTail("APUIMGP1:0/0/99:0"))
        assertNull(InlineImage.textTail("APUIMGP1:0/0/x:0"))
    }

    @Test
    fun fullTextAssemblesOnlyMatchingRevisionAndHead() {
        val body = cyrillic.repeat(200)
        val chunks = InlineImage.splitText(body)
        val tail = chunks.drop(1)
        val head = InlineImage.headContent(chunks.first(), 3, tail.size, 0)
        val parts = tail.mapIndexed { i, piece -> InlineImage.buildTextPart("post", 3, i + 1, tail.size, piece) }
        val stale = tail.mapIndexed { i, _ -> InlineImage.buildTextPart("post", 2, i + 1, tail.size, "старьё") }
        val foreign = tail.mapIndexed { i, _ -> InlineImage.buildTextPart("other", 3, i + 1, tail.size, "чужое") }
        val photo = InlineImage.splitPhoto(1, "Q".repeat(100))

        val assembled = InlineImage.fullText("post", head, (stale + foreign + photo + parts).shuffled())
        assertTrue(assembled.complete)
        assertEquals(body.trim(), assembled.text)
        assertTrue(InlineImage.textComplete("post", head, parts))

        val missing = InlineImage.fullText("post", head, parts.drop(1))
        assertFalse(missing.complete)
        assertTrue(missing.text.endsWith("…"))
        assertTrue(missing.text.startsWith(chunks.first().trim()))
        assertFalse(InlineImage.textComplete("post", head, stale))
    }

    @Test
    fun replaceTextKeepsPhotosAndTakesTailFromNewText() {
        val old = InlineImage.headContent("старый текст", 1, 2, 3)
        val shorter = InlineImage.replaceText(old, "короткий")
        assertNull(InlineImage.textTail(shorter))
        assertEquals(3, InlineImage.photoCount(shorter))
        assertEquals("короткий", InlineImage.stripImage(shorter))

        val longer = InlineImage.replaceText(old, InlineImage.headContent("новая голова ", 9, 4, 0))
        assertEquals(9, InlineImage.textTail(longer)!!.rev)
        assertEquals(4, InlineImage.textTail(longer)!!.count)
        assertEquals(3, InlineImage.photoCount(longer))
        assertEquals("новая голова ", InlineImage.headWords(longer))
    }

    @Test
    fun expandContentInlinesPiecesAndKeepsPhotoMarker() {
        val head = InlineImage.headContent("раз ", 0, 1, 1)
        val part = InlineImage.buildTextPart("id", 0, 1, 1, "два")
        val expanded = InlineImage.expandContent("id", head, listOf(part))
        assertEquals("раз два\n" + InlineImage.SET_MARKER + "1", expanded)
        assertNull(InlineImage.textTail(expanded))
        assertEquals("как есть", InlineImage.expandContent("id", "как есть", emptyList()))
    }

    @Test
    fun textPartPatternMatchesOnlyOwnParts() {
        val pattern = InlineImage.textPartPattern("abc")
        val regex = Regex("^" + Regex.escape(pattern).replace("%", "\\E.*\\Q") + "$")
        assertTrue(regex.matches(InlineImage.buildTextPart("abc", 4, 1, 2, "x")))
        assertFalse(regex.matches(InlineImage.buildTextPart("abd", 4, 1, 2, "x")))
        assertFalse(regex.matches(InlineImage.splitPhoto(1, "Q".repeat(10)).first()))
    }

    @Test
    fun revisionIsPositiveAndDistinctAcrossSeconds() {
        val a = InlineImage.revisionAt(1_757_440_000_000L)
        val b = InlineImage.revisionAt(1_757_440_001_000L)
        assertTrue(a > 0)
        assertTrue(a != b)
    }
}
