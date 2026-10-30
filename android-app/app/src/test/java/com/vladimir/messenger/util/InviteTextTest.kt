package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец 2026-10-30: «Сделай проще приглашение, как у топовых мессенджеров»
 * и «`vzhem` и `1985` — личная информация, в видимом тексте их быть не должно».
 */
class InviteTextTest {

    private val link = "https://p2p-relay.apu.workers.dev/s/SDrZhxj8XM"

    @Test
    fun `the personal invite is one sentence and one link`() {
        val text = InviteText.personal("Иван", link)
        assertEquals("Привет! Это Иван. Я в APU — давай общаться.\n\n$link", text)
    }

    @Test
    fun `the invite carries no instructions and no install line`() {
        val text = InviteText.personal("Иван", link)
        for (forbidden in listOf("вставь", "вставьте", "Добавь меня в контакты", "Скачать", "скачай")) {
            assertFalse("в приглашении не должно быть «$forbidden»: $text", text.contains(forbidden))
        }
    }

    @Test
    fun `the invite shows no personal data in the visible text`() {
        val text = InviteText.personal("Иван", link)
        assertFalse(text, text.contains("vzhem", ignoreCase = true))
        assertFalse(text, text.contains("1985"))
        assertFalse(text, text.contains("github", ignoreCase = true))
    }

    @Test
    fun `the link stands on its own line so it stays clickable`() {
        val text = InviteText.personal("Иван", link)
        val lastLine = text.lines().last()
        assertEquals(link, lastLine)
        assertTrue(text.lines().any { it.isEmpty() })
    }

    @Test
    fun `a missing name still gives a readable invite`() {
        val text = InviteText.personal("   ", link)
        assertTrue(text.startsWith("Привет! Это APU."))
        assertTrue(text.endsWith(link))
    }

    @Test
    fun `a group invite is one sentence and one link`() {
        assertEquals(
            "Присоединяйся к группе «Двор» в APU.\n\n$link",
            InviteText.community("Двор", link, isChannel = false),
        )
        assertEquals(
            "Присоединяйся к каналу «Новости» в APU.\n\n$link",
            InviteText.community("Новости", link, isChannel = true),
        )
    }

    @Test
    fun `several communities keep one link per line`() {
        val text = InviteText.communities(
            listOf(
                InviteText.Community("Двор", "$link", isChannel = false),
                InviteText.Community("Новости", "$link", isChannel = true),
            ),
        )
        assertFalse(text.contains("Скачать"))
        assertTrue(text.contains("Группа «Двор»:\n$link"))
        assertTrue(text.contains("Канал «Новости»:\n$link"))
    }
}
