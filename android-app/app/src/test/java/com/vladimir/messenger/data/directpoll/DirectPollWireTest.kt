package com.vladimir.messenger.data.directpoll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт опроса личного чата: сборка и разбор.
 *
 * Формат — это протокол между двумя телефонами, и ошибка здесь тихая: карточка
 * просто не появится. Поэтому проверяем не только «собрали-разобрали», но и
 * границы: разделитель `|` внутри вопроса, запятые в вариантах, испорченную
 * строку и чужой префикс.
 */
class DirectPollWireTest {

    @Test
    fun `open survives question with pipes and commas`() {
        val question = "Куда идём | в кино, или в парк?"
        val options = listOf("Кино, вечер", "Парк | утро", "Дома")
        val text = DirectPollWire.buildOpen(
            pollId = "dp:m1",
            messageId = "m1",
            chatId = "chat-1",
            question = question,
            options = options,
            anonymous = true,
            atMs = 1_700_000_000_000L,
        )

        val parsed = DirectPollWire.parseOpen(text)
        requireNotNull(parsed)
        assertEquals("dp:m1", parsed.pollId)
        assertEquals("m1", parsed.messageId)
        assertEquals("chat-1", parsed.chatId)
        assertEquals(question, parsed.question)
        assertEquals(options, parsed.options)
        assertTrue(parsed.anonymous)
        assertEquals(1_700_000_000_000L, parsed.atMs)
        assertTrue(DirectPollWire.isPollPacket(text))
    }

    @Test
    fun `vote carries sorted distinct choices`() {
        val text = DirectPollWire.buildVote("dp:m2", "m2", "chat", listOf(2, 0, 2), 42L)
        val parsed = DirectPollWire.parseVote(text)
        requireNotNull(parsed)
        assertEquals(listOf(0, 2), parsed.choices)
        assertEquals(42L, parsed.atMs)
        // Отозванный голос — валидный пакет с пустым списком.
        val revoked = DirectPollWire.parseVote(
            DirectPollWire.buildVote("dp:m2", "m2", "chat", emptyList(), 43L),
        )
        requireNotNull(revoked)
        assertTrue(revoked.choices.isEmpty())
    }

    @Test
    fun `close roundtrip`() {
        val text = DirectPollWire.buildClose("dp:m3", "m3", "chat", 7L)
        val parsed = DirectPollWire.parseClose(text)
        requireNotNull(parsed)
        assertEquals("dp:m3", parsed.pollId)
        assertEquals(7L, parsed.atMs)
        // Виды не путаются: close не разберётся как vote и наоборот.
        assertNull(DirectPollWire.parseVote(text))
        assertNull(DirectPollWire.parseOpen(text))
    }

    @Test
    fun `foreign and broken packets are ignored`() {
        assertFalse(DirectPollWire.isPollPacket("привет"))
        assertFalse(DirectPollWire.isPollPacket("APUREACT1|x"))
        assertFalse(DirectPollWire.isPollPacket(null))
        assertNull(DirectPollWire.parseOpen("APUPOLL1|open|только|три"))
        assertNull(DirectPollWire.parseVote("APUPOLL1|vote|нет|полей"))
        // Мусор в id — пакет не применяем.
        assertNull(DirectPollWire.parseVote("APUPOLL1|vote|%%%|m|chat|1|5"))
        assertTrue(DirectPollWire.isPollPacket("APUPOLL1|unknown|a|b|c"))
    }

    @Test
    fun `open needs at least two options`() {
        val single = DirectPollWire.buildOpen("dp:m4", "m4", "chat", "Вопрос", listOf("Один"), false, 1L)
        assertNull(DirectPollWire.parseOpen(single))
    }

    @Test
    fun `poll id is derived from the message id`() {
        assertEquals("dp:abc", DirectPollWire.pollIdOf("abc"))
        // Один и тот же вопрос — один и тот же опрос: повторная досылка не плодит дубль.
        assertEquals(DirectPollWire.pollIdOf("abc"), DirectPollWire.pollIdOf("abc"))
    }
}
