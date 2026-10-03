package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.vladimir.messenger.data.local.InboxPinPolicy
import com.vladimir.messenger.data.local.entity.GroupEntity

/** Stable types used by the main-inbox pin store and its mirror event. */
enum class InboxPinKind(val wireValue: String) {
    PERSONAL("personal"),
    GROUP("group");

    companion object {
        fun fromWireValue(value: String): InboxPinKind? =
            values().firstOrNull { it.wireValue == value }
    }
}

/** Result of an atomic pin/unpin in the mixed main inbox. */
enum class InboxPinMutation {
    UPDATED,
    UNCHANGED,
    LIMIT_REACHED,
    NOT_FOUND,
}

/**
 * A single transaction boundary for pins shared by personal chats, groups, and
 * channels. The actual timestamps live on the existing conversation rows, so
 * deleting a conversation also releases its slot automatically.
 */
@Dao
interface InboxPinDao {
    @Query(
        "SELECT (SELECT COUNT(DISTINCT contactId) FROM chats WHERE pinnedAtMs IS NOT NULL) + " +
            "(SELECT COUNT(*) FROM groups WHERE pinnedAtMs IS NOT NULL)"
    )
    suspend fun countPinnedConversations(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE contactId = :contactId)")
    suspend fun personalConversationExists(contactId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE contactId = :contactId AND pinnedAtMs IS NOT NULL)")
    suspend fun isPersonalPinned(contactId: String): Boolean

    @Query("UPDATE chats SET pinnedAtMs = :atMs WHERE contactId = :contactId")
    suspend fun updatePersonalPinnedAt(contactId: String, atMs: Long?)

    @Query("SELECT * FROM groups WHERE id = :groupId AND isLeft = 0")
    suspend fun getActiveGroup(groupId: String): GroupEntity?

    @Query("UPDATE groups SET pinnedAtMs = :atMs WHERE id = :groupId AND isLeft = 0")
    suspend fun updateGroupPinnedAt(groupId: String, atMs: Long?)

    /**
     * Set the pin for every local row representing this contact. The mirror
     * protocol uses contactId rather than the per-device chat row id.
     */
    @Transaction
    suspend fun setPersonalPinned(
        contactId: String,
        pinned: Boolean,
        atMs: Long,
    ): InboxPinMutation {
        if (contactId.isBlank() || !personalConversationExists(contactId)) {
            return InboxPinMutation.NOT_FOUND
        }
        val currentlyPinned = isPersonalPinned(contactId)
        if (currentlyPinned == pinned) return InboxPinMutation.UNCHANGED
        if (pinned && !InboxPinPolicy.canAddPin(countPinnedConversations())) {
            return InboxPinMutation.LIMIT_REACHED
        }
        updatePersonalPinnedAt(
            contactId = contactId,
            atMs = if (pinned) atMs.takeIf { it > 0L } ?: System.currentTimeMillis() else null,
        )
        return InboxPinMutation.UPDATED
    }

    /**
     * Set a pin for a joined group or channel. Group ids are stable across the
     * owner's devices, unlike local personal-chat row ids.
     */
    @Transaction
    suspend fun setGroupPinned(
        groupId: String,
        pinned: Boolean,
        atMs: Long,
    ): InboxPinMutation {
        if (groupId.isBlank()) return InboxPinMutation.NOT_FOUND
        val group = getActiveGroup(groupId) ?: return InboxPinMutation.NOT_FOUND
        if ((group.pinnedAtMs != null) == pinned) return InboxPinMutation.UNCHANGED
        if (pinned && !InboxPinPolicy.canAddPin(countPinnedConversations())) {
            return InboxPinMutation.LIMIT_REACHED
        }
        updateGroupPinnedAt(
            groupId = groupId,
            atMs = if (pinned) atMs.takeIf { it > 0L } ?: System.currentTimeMillis() else null,
        )
        return InboxPinMutation.UPDATED
    }
}
