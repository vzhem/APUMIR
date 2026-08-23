package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers WHERE chatId = :chatId ORDER BY createdAtMs ASC")
    fun observeForChat(chatId: String): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId")
    suspend fun getTransfer(transferId: String): FileTransferEntity?

    @Query(
        """
        SELECT * FROM file_transfers
        WHERE direction = 'OUTGOING'
          AND state IN ('PREPARED', 'TRANSFERRING', 'SENT')
          AND expiresAtMs > :nowMs
        ORDER BY createdAtMs ASC
        """
    )
    suspend fun getActiveOutgoing(nowMs: Long): List<FileTransferEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransferIgnore(transfer: FileTransferEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: FileTransferChunkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunkIgnore(chunk: FileTransferChunkEntity): Long

    @Query("SELECT COUNT(*) FROM file_transfer_chunks WHERE transferId = :transferId")
    suspend fun countChunks(transferId: String): Long

    @Query(
        """
        SELECT COALESCE(SUM(ciphertextBytes - 16), 0)
        FROM file_transfer_chunks
        WHERE transferId = :transferId
        """
    )
    suspend fun receivedPlaintextBytes(transferId: String): Long

    @Query(
        """
        UPDATE file_transfers
        SET state = :state,
            completedChunks = :completedChunks,
            transferredBytes = :transferredBytes,
            updatedAtMs = :updatedAtMs,
            errorCode = :errorCode
        WHERE transferId = :transferId
          AND :completedChunks >= completedChunks
          AND :completedChunks <= chunkCount
          AND :transferredBytes >= transferredBytes
          AND :transferredBytes <= totalBytes
        """
    )
    suspend fun advanceProgress(
        transferId: String,
        state: String,
        completedChunks: Long,
        transferredBytes: Long,
        updatedAtMs: Long,
        errorCode: String?,
    ): Int

    @Query(
        """
        SELECT * FROM file_transfer_chunks
        WHERE transferId = :transferId
        ORDER BY chunkIndex ASC
        """
    )
    suspend fun getChunks(transferId: String): List<FileTransferChunkEntity>

    @Query("DELETE FROM file_transfers WHERE expiresAtMs < :nowMs AND state != 'COMPLETE'")
    suspend fun deleteExpiredIncomplete(nowMs: Long): Int

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: String): Int

    /** Пользовательская «очистка зависших»: отменяет все незавершённые ИСХОДЯЩИЕ передачи. */
    @Query(
        """
        UPDATE file_transfers
        SET state = 'CANCELLED', updatedAtMs = :nowMs
        WHERE direction = 'OUTGOING'
          AND state IN ('PREPARED', 'TRANSFERRING', 'SENT')
        """
    )
    suspend fun cancelAllOutgoing(nowMs: Long): Int

    @Query("SELECT * FROM file_transfers WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'")
    suspend fun getWaitingRecipient(): List<FileTransferEntity>

    @Query(
        """
        UPDATE file_transfers
        SET state = 'TRANSFERRING', updatedAtMs = :nowMs
        WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'
        """
    )
    suspend fun resumeAllWaitingRecipient(nowMs: Long): Int

    @Query("SELECT * FROM file_transfers WHERE state = 'CANCELLED'")
    suspend fun getCancelled(): List<FileTransferEntity>

    @Query("SELECT * FROM file_transfers WHERE state = 'COMPLETE'")
    suspend fun getCompleted(): List<FileTransferEntity>

    @Transaction
    suspend fun insertNewTransfer(transfer: FileTransferEntity): Boolean =
        insertTransferIgnore(transfer) != -1L
}
