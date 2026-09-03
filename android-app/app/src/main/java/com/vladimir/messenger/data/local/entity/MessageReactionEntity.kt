package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Реакция на сообщение: один значок от одного узла на одно сообщение.
 *
 * Ключ составной (сообщение + автор реакции): второй раз тот же человек ту же
 * реакцию не поставит, а смена значка просто перезапишет строку. Внешних
 * ключей на messages нет намеренно - реакция может приехать раньше самого
 * сообщения (роевая доставка не гарантирует порядок), и терять её нельзя.
 */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["messageId", "nodeId"],
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
