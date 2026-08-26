package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Участник группы. `permissions` — битовая маска прав администратора
 * (см. data.group.GroupPermissions). У обычных участников маска хранит
 * разрешения на уровне группы (отправка сообщений, медиа, приглашения).
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("nodeId")],
)
data class GroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val displayName: String = "",
    /** OWNER | ADMIN | MEMBER */
    val role: String = "MEMBER",
    val joinedAtMs: Long = 0L,
    val permissions: Long = 0L,
    val customTitle: String = "",
    val isBanned: Boolean = false,
)
