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
     * Ранг собеседника из схемы v27. Больше НЕ пишется и не читается: ранги
     * переехали в таблицу `peer_ranks` (решение владельца 2026-10-07 — «VIP
     * должно быть видно везде, и в группах, и в каналах», то есть и у тех, кого
     * нет в адресной книге). Колонки оставлены ради совместимости схемы; в
     * `MIGRATION_27_28` их значения переносятся в новую таблицу.
     */
    val peerRankQualified: Int = -1,
    /** Когда этот телефон получил последнее сообщение ранга собеседника (v27). */
    val peerRankUpdatedAtMs: Long = 0,
)
