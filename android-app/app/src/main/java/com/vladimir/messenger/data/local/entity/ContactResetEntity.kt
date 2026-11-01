package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One-time contact-directory reset. Chats, messages and cryptographic keys are untouched. */
@Entity(tableName = "contact_reset_state")
data class ContactResetStateEntity(
    @PrimaryKey val id: Int = 1,
    val epoch: Int = 1,
    val startedAtMs: Long,
    val cacheClearedAtMs: Long = 0,
)

/**
 * Pre-reset address book snapshot used only to recognise a fresh, signed hello.
 * A candidate is NOT a contact and is never shown in the UI.
 */
@Entity(tableName = "contact_reset_candidates")
data class ContactResetCandidateEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val username: String = "",
    val fingerprint: String = "",
    val restoredAtMs: Long? = null,
    val lastAttemptAtMs: Long = 0,
)
