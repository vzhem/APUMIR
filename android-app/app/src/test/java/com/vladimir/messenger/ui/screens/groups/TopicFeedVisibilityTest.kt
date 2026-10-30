package com.vladimir.messenger.ui.screens.groups

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец 2026-10-30: сообщения SOS отмечались прочитанными, пока он только
 * смотрел список тем. Здесь проверяется, что экран сообщает модели правду и
 * что прочтение включается только у открытой темы на переднем плане.
 */
class TopicFeedVisibilityTest {

    @Test
    fun `the open topic on the foreground is read`() {
        val visibility = TopicFeedVisibility()
        visibility.update("general", resumed = true)
        assertTrue(visibility.canMarkRead("general"))
    }

    @Test
    fun `the topics list alone reads nothing`() {
        val visibility = TopicFeedVisibility()
        visibility.update(null, resumed = true)
        assertFalse(visibility.canMarkRead("general"))
    }

    @Test
    fun `another topic stays unread while one is open`() {
        val visibility = TopicFeedVisibility()
        visibility.update("general", resumed = true)
        assertFalse(visibility.canMarkRead("flood"))
    }

    @Test
    fun `opening a topic asks the feed to restart`() {
        val visibility = TopicFeedVisibility()
        // Список тем на экране: перезапускать нечего, читать нечего.
        assertFalse(visibility.update(null, resumed = true))
        // Человек вошёл в тему: ленту надо перезапустить до сброса счётчика.
        assertTrue(visibility.update("general", resumed = true))
        // Повторный доклад о той же теме ленту не дёргает.
        assertFalse(visibility.update("general", resumed = true))
    }

    @Test
    fun `returning from the background reads the open topic`() {
        val visibility = TopicFeedVisibility()
        visibility.update("general", resumed = true)
        visibility.update("general", resumed = false)
        // В фоне тема не читалась, хотя была открыта.
        assertFalse(visibility.canMarkRead("general"))
        // Вернулись в приложение — лента перезапускается и снимает счётчик.
        assertTrue(visibility.update("general", resumed = true))
        assertTrue(visibility.canMarkRead("general"))
    }

    @Test
    fun `leaving the screen stops reading`() {
        val visibility = TopicFeedVisibility()
        visibility.update("general", resumed = true)
        visibility.update(null, resumed = false)
        assertFalse(visibility.canMarkRead("general"))
    }

    @Test
    fun `the topics list is not read even after a topic was open`() {
        val visibility = TopicFeedVisibility()
        visibility.update("general", resumed = true)
        // Назад в список тем: открытая раньше тема больше не читается.
        assertFalse(visibility.update(null, resumed = true))
        assertFalse(visibility.canMarkRead("general"))
    }
}
