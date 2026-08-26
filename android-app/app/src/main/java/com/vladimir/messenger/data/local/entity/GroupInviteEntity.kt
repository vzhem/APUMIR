package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ссылка-приглашение. Рядом со ссылкой экран показывает QR-код того же текста.
 * `expiresAtMs == null` — без срока; `maxUses == 0` — без ограничения по числу вступлений.
 */
@Entity(
    tableName = "group_invites",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("slug")],
)
data class GroupInviteEntity(
    @PrimaryKey val slug: String,
    val groupId: String,
    val createdBy: String = "",
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long? = null,
    val maxUses: Int = 0,
    val useCount: Int = 0,
    val revoked: Boolean = false,
    /** true — вступивший по этой ссылке попадает в заявки, а не сразу в участники. */
    val requestApproval: Boolean = false,
)
