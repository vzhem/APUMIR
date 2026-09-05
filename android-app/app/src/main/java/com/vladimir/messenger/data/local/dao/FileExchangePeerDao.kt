package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity

@Dao
interface FileExchangePeerDao {
    @Query("SELECT * FROM file_exchange_peers WHERE nodeId = :nodeId")
    suspend fun get(nodeId: String): FileExchangePeerEntity?

    /** Разогрев кэша шифрования на старте: ключи уже закреплённых контактов. */
    @Query("SELECT * FROM file_exchange_peers")
    suspend fun getAll(): List<FileExchangePeerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstSeen(entity: FileExchangePeerEntity): Long

    @Query("DELETE FROM file_exchange_peers WHERE nodeId = :nodeId")
    suspend fun deleteForContact(nodeId: String): Int
}
