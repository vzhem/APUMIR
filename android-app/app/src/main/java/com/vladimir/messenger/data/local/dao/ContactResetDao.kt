package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.ContactResetCandidateEntity
import com.vladimir.messenger.data.local.entity.ContactResetStateEntity

@Dao
interface ContactResetDao {
    @Query("SELECT * FROM contact_reset_state WHERE id = 1")
    suspend fun state(): ContactResetStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertState(state: ContactResetStateEntity)

    @Query("UPDATE contact_reset_state SET cacheClearedAtMs = :nowMs WHERE id = 1 AND cacheClearedAtMs = 0")
    suspend fun markCacheCleared(nowMs: Long)

    @Query("SELECT * FROM contact_reset_candidates WHERE restoredAtMs IS NULL ORDER BY lastAttemptAtMs ASC, nodeId ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<ContactResetCandidateEntity>

    @Query("SELECT * FROM contact_reset_candidates WHERE nodeId = :nodeId")
    suspend fun candidate(nodeId: String): ContactResetCandidateEntity?

    @Query("SELECT nodeId FROM contact_reset_candidates WHERE restoredAtMs IS NULL")
    suspend fun pendingIds(): List<String>

    @Query("SELECT nodeId FROM contact_reset_candidates")
    suspend fun allIds(): List<String>

    @Query("UPDATE contact_reset_candidates SET lastAttemptAtMs = :nowMs WHERE nodeId = :nodeId")
    suspend fun markAttempt(nodeId: String, nowMs: Long)

    @Query("UPDATE contact_reset_candidates SET restoredAtMs = :nowMs WHERE nodeId = :nodeId AND restoredAtMs IS NULL")
    suspend fun markRestored(nodeId: String, nowMs: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM contact_reset_candidates WHERE nodeId = :nodeId AND restoredAtMs IS NOT NULL)")
    suspend fun isRestored(nodeId: String): Boolean
}
