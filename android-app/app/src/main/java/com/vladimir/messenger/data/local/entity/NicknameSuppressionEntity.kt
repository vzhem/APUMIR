package com.vladimir.messenger.data.local.entity

import androidx.room.Entity

/**
 * Local revocation ledger for a nickname announcement.
 *
 * Deleting a row from [NicknameEntity] is not enough: the same gossip packet
 * may arrive again a moment later.  This tombstone deliberately belongs to the
 * exact owner/name pair, so another legitimate owner of the same @name is not
 * hidden by a cleanup action.
 */
@Entity(
    tableName = "nickname_suppressions",
    primaryKeys = ["ownerId", "name"],
)
data class NicknameSuppressionEntity(
    val ownerId: String,
    /** Normalized nickname, without @ and in lower case. */
    val name: String,
    val revokedAtMs: Long,
)
