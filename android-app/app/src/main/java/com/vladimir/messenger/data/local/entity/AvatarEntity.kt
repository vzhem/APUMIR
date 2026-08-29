package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Присланный аватар участника сети: маленький JPEG в base64. Хранится по
 * владельцу; новый пакет заменяет старый только если свежее по времени.
 */
@Entity(tableName = "avatars")
data class AvatarEntity(
    @PrimaryKey val ownerId: String,
    /** JPEG 96x96, base64 без переносов. */
    val dataB64: String,
    val updatedAtMs: Long = 0L,
)
