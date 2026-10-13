package com.vladimir.messenger.data.story

/**
 * Досрочное удаление истории у всех.
 *
 * Пакет `kind=revoke` снимает историю с ленты друзей. Повторные куски фото
 * после отзыва не воскрешают снимок: держим надгробие до конца суток жизни.
 */
internal object StoryRevoke {
    const val KIND = "revoke"

    fun key(authorId: String, storyId: String): String = "$authorId\n$storyId"

    fun tombstoneUntilMs(createdAtMs: Long, lifetimeMs: Long = StoryStore.LIFETIME_MS): Long {
        if (createdAtMs < 0L) return lifetimeMs
        val room = Long.MAX_VALUE - createdAtMs
        return if (room < lifetimeMs) Long.MAX_VALUE else createdAtMs + lifetimeMs
    }

    fun isActive(untilMs: Long, nowMs: Long): Boolean = nowMs < untilMs

    fun remaining(
        items: List<StoryStore.Story>,
        authorId: String,
        storyId: String,
    ): List<StoryStore.Story> = items.filterNot { it.authorId == authorId && it.id == storyId }
}
