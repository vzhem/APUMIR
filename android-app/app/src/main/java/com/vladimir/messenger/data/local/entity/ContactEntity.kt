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
)
