package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestamp"], name = "index_messages_chatId_timestamp"),
        Index(value = ["isFromMe", "timestamp"], name = "index_messages_isFromMe_timestamp"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: String = "PENDING",
    val isFromMe: Boolean = false,
    /**
     * р250: на какое сообщение это ответ (в личке, в группе и в теме).
     * Пусто - обычное сообщение без цитаты.
     */
    val replyToId: String? = null,
    /**
     * р250: кого цитируем - имя автора исходного сообщения.
     *
     * Имя и текст цитаты хранятся рядом с сообщением, а не достаются по
     * [replyToId] при отрисовке: исходное сообщение могло быть удалено, не
     * дойти или лежать за пределами загруженной части ветки, а пузырь с
     * цитатой должен рисоваться всегда.
     */
    @ColumnInfo(defaultValue = "")
    val replyAuthor: String = "",
    /** р250: короткий текст цитируемого сообщения (до 120 знаков). */
    @ColumnInfo(defaultValue = "")
    val replyText: String = "",
    val channel: String = "UNKNOWN",
    val recipientId: String = "",
    // ── Группы и темы (аддитивно, v7 → v8). В личных чатах все четыре поля пусты. ──
    /** Тема внутри группы; пусто для личных чатов и для группы без тем. */
    val topicId: String? = null,
    /**
     * Закреплено. Закреплять могут только администраторы с правом pin_messages.
     * defaultValue задан явно, чтобы схема сущности и схема после ALTER TABLE
     * совпадали при валидации миграции Room.
     */
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    val pinnedAtMs: Long? = null,
    val pinnedBy: String? = null,
)
