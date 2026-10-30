package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.util.InlineImage

/**
 * Что из строк темы видно в ленте и где начинается непрочитанное.
 *
 * Вынесено из `GroupChatViewModel` в отдельный маленький файл: сам файл модели
 * на потолке god-файла (AGENTS.md п. 8), а эта разметка — чистая функция от
 * списка сообщений, её можно проверить и без Android.
 */
internal object TopicFeedMessages {

    /**
     * Строки ленты.
     *
     * Куски фотографий и длинного текста — служебные, в ленте их нет: галерея
     * собирает фото сама, а куски текста подклеиваются к своему сообщению
     * здесь же. В обсуждении видны только общие закрепы: личный закреп
     * публикации канала остаётся в ленте канала.
     */
    fun visible(all: List<MessageEntity>): List<MessageEntity> {
        val parts = all.filter { InlineImage.isPart(it.content) }
        return all.filter { !InlineImage.isPart(it.content) }.map { message ->
            val expanded = if (InlineImage.textTail(message.content) == null) {
                message
            } else {
                val own = parts.filter { it.senderId == message.senderId }.map { it.content }
                message.copy(content = InlineImage.expandContent(message.id, message.content, own))
            }
            expanded.copy(isPinned = expanded.isPinned && expanded.pinnedBy != null)
        }
    }

    /** Строка, на которой начинается непрочитанное; последняя, если счётчика нет. */
    fun unreadIndex(unread: Int, size: Int): Int =
        if (unread in 1..size) size - unread else size - 1
}
