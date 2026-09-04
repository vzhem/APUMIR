package com.vladimir.messenger.data.reaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Набор реакций - часть договорённости с владельцем: ровно 20 разных значков,
 * все доброжелательные. Тест сторожит и число, и отсутствие осуждающих.
 */
class ReactionPaletteTest {

    @Test
    fun `palette has twenty unique emoji`() {
        assertEquals(20, ReactionPalette.EMOJI.size)
        assertEquals(20, ReactionPalette.EMOJI.toSet().size)
    }

    @Test
    fun `palette has no negative emoji`() {
        val negative = listOf("\uD83D\uDC4E", "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDE20", "\uD83D\uDCA9")
        for (emoji in negative) {
            assertFalse("нашли осуждающий значок $emoji", ReactionPalette.EMOJI.contains(emoji))
        }
    }

    @Test
    fun `palette entries are not blank`() {
        assertTrue(ReactionPalette.EMOJI.all { it.isNotBlank() })
    }
}
