package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Реестр @имён, собранный роевой рассылкой: кто и когда зарегистрировал имя.
 * При споре двух владельцев имени прав тот, у кого registeredAtMs раньше.
 * Имена хранятся без собаки.
 */
@Entity(tableName = "nicknames")
data class NicknameEntity(
    @PrimaryKey val ownerId: String,
    val name: String,
    val registeredAtMs: Long,
)
