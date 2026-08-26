package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Тема внутри группы (аналог форум-топиков Telegram).
 *
 * `messageCount` и `unreadCount` — счётчики, которые показывает список тем:
 * сколько всего сообщений накопилось в теме и сколько из них не прочитано.
 */
@Entity(
    tableName = "group_topics",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class GroupTopicEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val ownerId: String = "",
    val ownerName: String = "",
    val iconEmoji: String = "",
    val createdAtMs: Long = 0L,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAtMs: Long? = null,
    val isClosed: Boolean = false,
    /** Служебная тема «General», создаётся вместе с группой. */
    val isGeneral: Boolean = false,
)
