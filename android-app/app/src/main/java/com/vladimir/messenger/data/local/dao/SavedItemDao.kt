package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedItemDao {
    /** Новые сверху: последнее сохранённое человек ищет первым. */
    @Query("SELECT * FROM saved_items ORDER BY savedAtMs DESC")
    fun observeAll(): Flow<List<SavedItemEntity>>

    @Query("SELECT COUNT(*) FROM saved_items")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SavedItemEntity)

    @Query("DELETE FROM saved_items WHERE id = :id")
    suspend fun delete(id: String)

    /** Уже сохранён этот файл? Чтобы не плодить дубли при повторной пересылке. */
    @Query("SELECT * FROM saved_items WHERE transferId = :transferId LIMIT 1")
    suspend fun byTransfer(transferId: String): SavedItemEntity?
}
