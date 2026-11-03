package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Опрос в ЛИЧНОМ чате: один ряд на опрос, привязанный к сообщению с вопросом.
 *
 * Почему не `group_polls`: там ключ — группа, и все проверки требуют участия
 * в группе. Личный опрос живёт на двух телефонах, поэтому здесь `chatId`
 * (личная переписка) и больше ничего.
 *
 * Строка появляется у автора при создании и у собеседника при первом голосе
 * или закрытии (вопрос приходит обычным текстом, поэтому и опознаётся по
 * `pollId`, выведенному из id сообщения).
 */
@Entity(
    tableName = "direct_polls",
    indices = [
        Index(value = ["chatId"], name = "index_direct_polls_chatId"),
        Index(value = ["messageId"], name = "index_direct_polls_messageId"),
    ],
)
data class DirectPollEntity(
    @PrimaryKey val pollId: String,
    val chatId: String,
    val messageId: String,
    /** Вопрос: он же текст сообщения, хранится для карточки без сети. */
    val question: String,
    /** Варианты через `,`, каждый в base64url: в тексте бывает запятая. */
    val optionsCsv: String,
    val anonymous: Boolean = false,
    /** Автор опроса (узел). В личном чате это отправитель вопроса. */
    val creatorId: String = "",
    val createdAtMs: Long = 0L,
    val closed: Boolean = false,
)
