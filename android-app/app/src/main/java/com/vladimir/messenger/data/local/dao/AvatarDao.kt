package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.AvatarEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AvatarDao {
    @Query("SELECT * FROM avatars")
    fun observeAll(): Flow<List<AvatarEntity>>

    @Query("SELECT * FROM avatars")
    suspend fun all(): List<AvatarEntity>

    @Query("SELECT * FROM avatars WHERE ownerId = :ownerId")
    suspend fun byOwner(ownerId: String): AvatarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AvatarEntity)

    @Query("DELETE FROM avatars WHERE ownerId = :ownerId")
    suspend fun deleteByOwner(ownerId: String)
}
