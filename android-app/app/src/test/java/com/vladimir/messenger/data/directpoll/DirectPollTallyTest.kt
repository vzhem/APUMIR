package com.vladimir.messenger.data.directpoll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила опроса личного чата: черновик, переголосование, закрытие.
 *
 * Здесь же фиксируется требование владельца: создавать опрос можно с ранга
 * «Круг друзей» (3), голосовать — всегда. Правило живёт в `FeatureRankPolicy`,
 * а его связь с опросом проверяет `DirectPollRankTest`.
 */
class DirectPollTallyTest {

    @Test
    fun `draft keeps only real options`() {
        val clean = DirectPollTally.normalize(
            question = "  Обед?  ",
            options = listOf(" Пицца ", "", "  ", "Суп"),
        )
        requireNotNull(clean)
        assertEquals("Обед?", clean.first)
        assertEquals(listOf("Пицца", "Суп"), clean.second)
    }

    @Test
    fun `draft with one option is refused`() {
        assertNull(DirectPollTally.normalize("Вопрос", listOf("Один", "   ")))
        assertNull(DirectPollTally.normalize("   ", listOf("Один", "Два")))
    }

    @Test
    fun `draft is capped`() {
        val clean = DirectPollTally.normalize(
            question = "В".repeat(500),
            options = (1..20).map { "вариант $it" },
        )
        requireNotNull(clean)
        assertEquals(DirectPollTally.MAX_QUESTION_CHARS, clean.first.length)
        assertEquals(DirectPollTally.MAX_OPTIONS, clean.second.size)
        assertTrue(clean.second.all { it.length <= DirectPollTally.MAX_OPTION_CHARS })
    }

    @Test
    fun `second tap on own option revokes the vote`() {
        assertEquals(listOf(1), DirectPollTally.toggle(emptyList(), 1))
        assertEquals(emptyList<Int>(), DirectPollTally.toggle(listOf(1), 1))
        // Передумал — голос переезжает, а не удваивается.
        assertEquals(listOf(2), DirectPollTally.toggle(listOf(1), 2))
    }

    @Test
    fun `voting is only inside a live poll`() {
        assertTrue(DirectPollTally.canVote(closed = false, optionCount = 3, index = 2))
        assertFalse(DirectPollTally.canVote(closed = false, optionCount = 3, index = 3))
        assertFalse(DirectPollTally.canVote(closed = true, optionCount = 3, index = 0))
        assertFalse(DirectPollTally.canVote(closed = false, optionCount = 3, index = -1))
    }

    @Test
    fun `only the author closes the poll`() {
        assertTrue(DirectPollTally.canClose(isMine = true, closed = false))
        assertFalse(DirectPollTally.canClose(isMine = false, closed = false))
        assertFalse(DirectPollTally.canClose(isMine = true, closed = true))
    }
}
