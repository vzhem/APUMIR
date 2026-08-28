package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись сетевого каталога: публичная группа или канал, о которых телефон
 * узнал роевой рассылкой (владелец шлёт своим контактам, контакты - своим).
 * Позволяет поиску находить группы и каналы, созданные другими людьми.
 */
@Entity(tableName = "directory")
data class DirectoryEntity(
    @PrimaryKey val groupId: String,
    val title: String,
    val about: String = "",
    val ownerId: String,
    val slug: String,
    val isChannel: Boolean = false,
    val needsApproval: Boolean = false,
    /** Сколько пересылок прошло; ограничивает дальность эпидемии. */
    val hops: Int = 0,
    val updatedAtMs: Long = 0L,
)
