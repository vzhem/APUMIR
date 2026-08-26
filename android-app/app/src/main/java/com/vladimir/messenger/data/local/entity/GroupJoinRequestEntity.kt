package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Заявка на вступление в частную группу. Одобряет администратор с правом invite_users. */
@Entity(
    tableName = "group_join_requests",
    primaryKeys = ["groupId", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("status")],
)
data class GroupJoinRequestEntity(
    val groupId: String,
    val nodeId: String,
    val displayName: String = "",
    val note: String = "",
    val requestedAtMs: Long = 0L,
    /** PENDING | APPROVED | REJECTED */
    val status: String = "PENDING",
    val decidedAtMs: Long? = null,
    val decidedBy: String = "",
)
