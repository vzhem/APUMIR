package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Реакция на сообщение: значки одного узла на одно сообщение (до пяти разных).
 *
 * Ключ составной (сообщение + автор реакции + значок): тот же значок второй
 * раз не встанет, а разные значки одного человека живут отдельными строками.
 * Лимит в пять держит репозиторий. Внешних
 * ключей на messages нет намеренно - реакция может приехать раньше самого
 * сообщения (роевая доставка не гарантирует порядок), и терять её нельзя.
 */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["messageId", "nodeId", "emoji"],
    indices = [Index(value = ["messageId"]), Index(value = ["chatId"])],
)
data class MessageReactionEntity(
    val messageId: String,
    /** Кто поставил: pk_-адрес узла или "self" для своей реакции. */
    val nodeId: String,
    /** Чат или группа, к которой относится сообщение - для выборок пачкой. */
    val chatId: String,
    /** Сам значок, одна эмодзи. */
    val emoji: String,
    val atMs: Long,
)
