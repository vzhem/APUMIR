package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.ProfileHeartEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileHeartDao {

    /** Повторный голос того же человека молча заменяет прежний, а не удваивает. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(heart: ProfileHeartEntity)

    @Query("DELETE FROM profile_hearts WHERE ownerId = :ownerId AND voterId = :voterId")
    suspend fun remove(ownerId: String, voterId: String)

    @Query("SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId")
    fun observeCount(ownerId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId")
    suspend fun countOf(ownerId: String): Int

    @Query(
        "SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId AND voterId = :voterId"
    )
    suspend fun hasVote(ownerId: String, voterId: String): Int
}
