package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory fake mirroring the Room guards (IGNORE inserts, monotonic progress) for JVM tests. */
class FakeFileTransferDao : FileTransferDao {
    private val transfers = LinkedHashMap<String, FileTransferEntity>()
    private val chunks = HashMap<Pair<String, Int>, FileTransferChunkEntity>()
    private val observe = MutableStateFlow(0)

    override fun observeForChat(chatId: String): Flow<List<FileTransferEntity>> =
        observe.map { transfers.values.filter { entity -> entity.chatId == chatId } }

    override suspend fun getTransfer(transferId: String): FileTransferEntity? = transfers[transferId]

    override suspend fun getActiveOutgoing(nowMs: Long): List<FileTransferEntity> =
        transfers.values
            .filter {
                it.direction == "OUTGOING" &&
                    it.state in setOf("PREPARED", "TRANSFERRING", "SENT") &&
                    it.expiresAtMs > nowMs
            }
            .sortedBy { it.createdAtMs }

    override suspend fun insertTransferIgnore(transfer: FileTransferEntity): Long {
        if (transfers.containsKey(transfer.transferId)) return -1L
        transfers[transfer.transferId] = transfer
        observe.value += 1
        return 1L
    }

    override suspend fun upsertChunk(chunk: FileTransferChunkEntity) {
        chunks[chunk.transferId to chunk.chunkIndex] = chunk
    }

    override suspend fun advanceProgress(
        transferId: String,
        state: String,
        completedChunks: Int,
        transferredBytes: Long,
        updatedAtMs: Long,
        errorCode: String?,
    ): Int {
        val current = transfers[transferId] ?: return 0
        if (completedChunks < current.completedChunks ||
            completedChunks > current.chunkCount ||
            transferredBytes < current.transferredBytes ||
            transferredBytes > current.totalBytes
        ) {
            return 0
        }
        transfers[transferId] = current.copy(
            state = state,
            completedChunks = completedChunks,
            transferredBytes = transferredBytes,
            updatedAtMs = updatedAtMs,
            errorCode = errorCode,
        )
        observe.value += 1
        return 1
    }

    override suspend fun getChunks(transferId: String): List<FileTransferChunkEntity> =
        chunks.filterKeys { it.first == transferId }.values.sortedBy { it.chunkIndex }

    override suspend fun deleteExpiredIncomplete(nowMs: Long): Int {
        val doomed = transfers.values.filter { it.expiresAtMs < nowMs && it.state != "COMPLETE" }
        doomed.forEach { transfers.remove(it.transferId) }
        return doomed.size
    }

    override suspend fun deleteTransfer(transferId: String): Int {
        chunks.keys.filter { it.first == transferId }.forEach(chunks::remove)
        return if (transfers.remove(transferId) != null) 1 else 0
    }

    override suspend fun cancelAllOutgoing(nowMs: Long): Int {
        var n = 0
        transfers.replaceAll { _, e ->
            if (e.direction == "OUTGOING" && e.state in setOf("PREPARED", "TRANSFERRING", "SENT")) {
                n++; e.copy(state = "CANCELLED", updatedAtMs = nowMs)
            } else e
        }
        observe.value += 1
        return n
    }

    override suspend fun getCancelled(): List<FileTransferEntity> =
        transfers.values.filter { it.state == "CANCELLED" }

    override suspend fun getCompleted(): List<FileTransferEntity> =
        transfers.values.filter { it.state == "COMPLETE" }

    override suspend fun insertNewTransfer(transfer: FileTransferEntity): Boolean =
        insertTransferIgnore(transfer) != -1L
}
