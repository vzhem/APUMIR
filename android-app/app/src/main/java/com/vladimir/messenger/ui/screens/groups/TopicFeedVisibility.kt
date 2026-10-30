package com.vladimir.messenger.ui.screens.groups

/**
 * Что человек реально видит на экране группы: открытую тему и на переднем ли
 * плане приложение.
 *
 * Вынесено из `GroupChatViewModel` отдельным маленьким классом, потому что
 * god-файл расти не должен (AGENTS.md п. 8), а состояние нужно двум местам:
 * ленте (можно ли считать тему прочитанной) и экрану (что сообщить модели).
 *
 * Правило прочтения — в [TopicReadingPolicy]; здесь только хранение.
 */
class TopicFeedVisibility {

    /** Тема открытой ленты; `null` — список тем, другой экран или фон. */
    @Volatile
    var visibleTopicId: String? = null
        private set

    /** Приложение на переднем плане: свёрнутое окно ничего не «прочитывает». */
    @Volatile
    var appResumed: Boolean = false
        private set

    /** Можно ли сейчас считать прочитанным обновление ленты этой темы. */
    fun canMarkRead(topicId: String): Boolean =
        TopicReadingPolicy.canCaptureUnreadJump(visibleTopicId, topicId, appResumed)

    /**
     * Экран сообщил новое состояние.
     *
     * @return `true`, если тема только что стала видимой — тогда ленту надо
     *   перезапустить, чтобы захват «первого непрочитанного» прошёл до сброса
     *   счётчика, а не после.
     */
    fun update(topicId: String?, resumed: Boolean): Boolean {
        val wasVisible = visibleTopicId != null &&
            TopicReadingPolicy.shouldMarkRead(visibleTopicId, topicId.orEmpty(), appResumed)
        visibleTopicId = topicId
        appResumed = resumed
        val nowVisible = topicId != null &&
            TopicReadingPolicy.shouldMarkRead(topicId, topicId, resumed)
        return nowVisible && !wasVisible
    }
}
