package com.vladimir.messenger.ui.screens.groups

/**
 * Когда сообщения темы можно считать прочитанными.
 *
 * Владелец 2026-10-30 (скрин списка тем с SOS): «только начал смотреть темы —
 * сообщения сразу отмечаются прочитанными, хотя внутрь темы ещё не заходил».
 *
 * Причина была не в сети и не в счётчике: при показе списка тем экран
 * автоматически выбирал первую тему (`General`), а модель запускал ленту этой
 * темы и на каждом её обновлении снимал непрочитанное. Человек видел список
 * тем, а `General` уже был «прочитан» — тревожные сообщения SOS гасли, не
 * будучи увиденными.
 *
 * Теперь прочтение требует двух условий сразу: лента этой темы открыта на
 * экране и приложение на переднем плане. Список тем, свёрнутое приложение и
 * автовыбор темы прочитанным не считаются.
 *
 * Android-зависимостей нет — правило проверяется обычным JVM-тестом
 * (`TopicReadingPolicyTest`).
 */
object TopicReadingPolicy {

    /**
     * @param visibleTopicId тема, чья лента реально видна человеку; `null` —
     *   список тем, другой экран или приложение в фоне
     * @param topicId тема, для которой пришло обновление ленты
     * @param appResumed приложение на переднем плане
     */
    fun shouldMarkRead(
        visibleTopicId: String?,
        topicId: String,
        appResumed: Boolean,
    ): Boolean {
        if (!appResumed) return false
        if (visibleTopicId.isNullOrBlank()) return false
        return visibleTopicId == topicId
    }

    /**
     * Можно ли сейчас прыгнуть к первому непрочитанному.
     *
     * Тот же принцип: непрочитанное место ищется только в открытой теме, иначе
     * захваченный «первый непрочитанный» относился бы к теме, которую человек
     * ещё не открывал.
     */
    fun canCaptureUnreadJump(
        visibleTopicId: String?,
        topicId: String,
        appResumed: Boolean,
    ): Boolean = shouldMarkRead(visibleTopicId, topicId, appResumed)
}
