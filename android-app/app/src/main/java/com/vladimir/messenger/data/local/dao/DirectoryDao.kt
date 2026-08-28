package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectoryDao {
    @Query("SELECT * FROM directory ORDER BY updatedAtMs DESC LIMIT 200")
    fun observeAll(): Flow<List<DirectoryEntity>>

    @Query("SELECT * FROM directory WHERE groupId = :groupId")
    suspend fun getById(groupId: String): DirectoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DirectoryEntity)

    @Query("DELETE FROM directory WHERE groupId = :groupId")
    suspend fun delete(groupId: String)
}
