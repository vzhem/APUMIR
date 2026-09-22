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

    /** Уже у хранителя (одного или нескольких), получатель ещё не подтвердил: можно добавить копию (этап 8). */
    @Query("SELECT * FROM file_transfers WHERE direction = 'OUTGOING' AND state = 'CUSTODIED' AND expiresAtMs > :nowMs")
    suspend fun getCustodied(nowMs: Long): List<FileTransferEntity>

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

    // ── Хранение у третьего телефона (этап 7 роя) ──────────────────────────
    // Чужой файл на хранении - строка direction = 'CUSTODY': peerNodeId =
    // получатель, originNodeId = отправитель (схема 19 → 20).
    // Состояния: HOLDING (собираем куски от отправителя) → FORWARDING (получатель
    // в сети, отдаём) → COMPLETE (получатель подтвердил всё, куски удалены).

    @Query(
        """
        SELECT * FROM file_transfers
        WHERE direction = 'CUSTODY' AND state IN ('HOLDING', 'FORWARDING')
          AND expiresAtMs > :nowMs
        ORDER BY createdAtMs ASC
        """
    )
    suspend fun getActiveCustody(nowMs: Long): List<FileTransferEntity>

    @Query(
        """
        SELECT * FROM file_transfers
        WHERE direction = 'CUSTODY' AND peerNodeId = :recipientId
          AND state IN ('HOLDING', 'FORWARDING') AND expiresAtMs > :nowMs
        ORDER BY createdAtMs ASC
        """
    )
    suspend fun getCustodyForRecipient(recipientId: String, nowMs: Long): List<FileTransferEntity>

    @Query("SELECT * FROM file_transfers WHERE direction = 'CUSTODY' AND (expiresAtMs <= :nowMs OR state = 'COMPLETE')")
    suspend fun getFinishedCustody(nowMs: Long): List<FileTransferEntity>

    @Query("SELECT COUNT(*) FROM file_transfers WHERE direction = 'CUSTODY' AND state IN ('HOLDING', 'FORWARDING')")
    suspend fun countActiveCustody(): Int

    /** Сколько чужих исходящих (одного отправителя) уже держим: защита от затопления одним узлом. */
    @Query(
        """
        SELECT COUNT(*) FROM file_transfers
        WHERE direction = 'CUSTODY' AND originNodeId = :originId AND state IN ('HOLDING', 'FORWARDING')
        """
    )
    suspend fun countActiveCustodyFrom(originId: String): Int

    /**
     * Файл давно у хранителя, а получатель так и не подтвердил: вернуть в
     * обычную очередь, чтобы отправитель попробовал и напрямую (см.
     * FileCustodySender.DIRECT_RETRY_AFTER_MS).
     */
    @Query(
        """
        UPDATE file_transfers
        SET state = 'TRANSFERRING', updatedAtMs = :nowMs
        WHERE direction = 'OUTGOING' AND state = 'CUSTODIED' AND updatedAtMs < :olderThanMs
        """
    )
    suspend fun resumeStaleCustodied(nowMs: Long, olderThanMs: Long): Int

    /** Сколько чужих байт (шифротекста) держим для других: строка в настройках. */
    @Query(
        """
        SELECT COALESCE(SUM(ciphertextBytes), 0) FROM file_transfer_chunks
        WHERE transferId IN (SELECT transferId FROM file_transfers WHERE direction = 'CUSTODY')
        """
    )
    suspend fun custodyHeldBytes(): Long

    /** Кому отдали на хранение (исходящая) / от кого пересланное (входящая); пусто - напрямую. */
    @Query("UPDATE file_transfers SET custodianNodeId = :custodianNodeId, updatedAtMs = :updatedAtMs WHERE transferId = :transferId")
    suspend fun setCustodian(transferId: String, custodianNodeId: String, updatedAtMs: Long): Int

    @Query("SELECT * FROM file_transfers WHERE state = 'COMPLETE'")
    suspend fun getCompleted(): List<FileTransferEntity>

    /**
     * Передачи одного файла в одном чате (рой, этап 9: файл группы). У
     * автора это исходящие каждому просителю, у участника - его входящая;
     * по ним карточка файла в ленте показывает ход раздачи и приёма.
     */
    @Query("SELECT * FROM file_transfers WHERE chatId = :chatId AND fileSha256 = :fileSha256 ORDER BY createdAtMs ASC")
    suspend fun getForFile(chatId: String, fileSha256: String): List<FileTransferEntity>

    /**
     * Раунд 120: уже принятый (COMPLETE) тот же файл от того же собеседника
     * в том же чате, с неистёкшим сроком. По нему повторное предложение
     * того же файла завершается локальной копией - без качания байтов.
     */
    @Query(
        "SELECT * FROM file_transfers " +
            "WHERE direction = 'INCOMING' AND state = 'COMPLETE' " +
            "AND chatId = :chatId AND peerNodeId = :peerNodeId " +
            "AND fileSha256 = :fileSha256 AND expiresAtMs > :nowMs " +
            "ORDER BY createdAtMs DESC LIMIT 1"
    )
    suspend fun getCompletedIncomingSameFile(
        chatId: String,
        peerNodeId: String,
        fileSha256: String,
        nowMs: Long,
    ): FileTransferEntity?

    /**
     * Мои групповые копии (K2, v11.70.25): строка `OUTGOING`/`SEEDING` -
     * файл группы, зашифрованный один раз общим ключом; куски из неё уходят
     * любому участнику по его просьбе (GroupFileSeeder). Обычный передатчик
     * такие строки не трогает (состояние не из его списка).
     */
    @Query("SELECT * FROM file_transfers WHERE direction = 'OUTGOING' AND state = 'SEEDING'")
    suspend fun getSeeding(): List<FileTransferEntity>

    @Transaction
    suspend fun insertNewTransfer(transfer: FileTransferEntity): Boolean =
        insertTransferIgnore(transfer) != -1L
}
