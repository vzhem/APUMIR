package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Группа. Ровно одна строка на группу, в которой участвует этот телефон.
 *
 * `isPublic` — выбор владельца при создании: публичная принимает по ссылке
 * без одобрения, частная требует подтверждения администратора.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val title: String,
    val about: String = "",
    val ownerId: String,
    val ownerName: String = "",
    val isPublic: Boolean = false,
    val topicsEnabled: Boolean = true,
    /**
     * Канал, а не группа: посты пишут администраторы, обсуждение - в
     * комментариях под постом. Хранится в той же таблице groups.
     */
    val isChannel: Boolean = false,
    val createdAtMs: Long = 0L,
    val memberCount: Int = 1,
    val inviteSlug: String = "",
    /**
     * Права обычных участников на уровне всей группы (маска GroupPermissions.Member).
     * Отдельная маска каждого администратора хранится в group_members.permissions.
     */
    val memberPermissions: Long = 0L,
    /** Телефон вышел из группы: строка хранится, чтобы не потерять историю. */
    val isLeft: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageAtMs: Long? = null,
    val unreadCount: Int = 0,
)
