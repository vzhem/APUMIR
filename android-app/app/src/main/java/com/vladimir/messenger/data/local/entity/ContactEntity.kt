package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fingerprint: String = "",
    val isOnline: Boolean = false,
    /** Старое текстовое поле, сохранено для совместимости с ранними базами. */
    val lastSeen: String? = null,
    /** Когда этот телефон в последний раз наблюдал контакт в сети. */
    val lastSeenAtMs: Long? = null,
    /** Дополнительное оригинальное имя через собаку, например @nickname. */
    val username: String = "",
    /**
     * Ранг собеседника, который он сам сообщил конвертом APURANK1 (число
     * подтверждённых приглашений). -1 — ещё не сообщал; тогда знак VIP у имени
     * не показывается вовсе (неизвестность не выдаём за «обычный ранг»).
     */
    val peerRankQualified: Int = -1,
    /** Когда этот телефон получил последнее сообщение ранга собеседника. */
    val peerRankUpdatedAtMs: Long = 0,
)
