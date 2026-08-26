package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: String = "PENDING",
    val isFromMe: Boolean = false,
    val replyToId: String? = null,
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
