package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Один факт репоста (пересылки) сообщения.
 *
 * Счётчик репостов — это количество строк по сообщению, а не число в колонке:
 * один и тот же репост, пришедший дважды (роевая доставка, повтор после
 * разрыва), узнаётся по [eventId] и второй раз не считается. Служебные
 * строки сборщиков больших каналов имеют nodeId с префиксом `hub:` — они не
 * факты пересылки, а добивка до сводного числа (как у просмотров постов).
 *
 * Внешних ключей на messages нет намеренно: сигнал о репосте может приехать
 * раньше самого сообщения, и терять его нельзя (как с реакциями).
 */
@Entity(
    tableName = "message_reposts",
    primaryKeys = ["messageId", "eventId"],
    indices = [Index(value = ["messageId"]), Index(value = ["chatId"])],
)
data class MessageRepostEntity(
    val messageId: String,
    /** Чат или группа, где живёт исходное сообщение. */
    val chatId: String,
    /** Уникальный номер события пересылки (у каждой пересланной копии свой). */
    val eventId: String,
    /** Кто переслал: pk_-адрес узла или "self". */
    val nodeId: String,
    val atMs: Long,
)
