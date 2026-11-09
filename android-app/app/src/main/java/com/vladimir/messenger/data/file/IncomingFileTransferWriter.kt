package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.diagnostics.Counters
import com.vladimir.messenger.data.diagnostics.DiagnosticsReport
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import uniffi.p2p_core.FileTransferManifestFfi

/** Chat-scoped tombstone checks for incoming personal file transfers. */
internal class IncomingFileTransferWriter(
    private val transferDao: FileTransferDao,
    private val suppressCleared: suspend (String, String, String) -> Boolean,
) {
    suspend fun suppressOffer(peer: String, chat: String, transferId: String, group: Boolean, name: String): Boolean {
        if (group || reserved(name)) return false
        return suppressCleared(peer, chat, FileTransferWire.chatPlaceholderMessageId(transferId))
    }

    suspend fun suppress(transfer: FileTransferEntity): Boolean {
        if (transfer.direction != "INCOMING" || reserved(transfer.displayName)) return false
        return suppressCleared(transfer.peerNodeId, transfer.chatId, transfer.messageId) ||
            suppressCleared(transfer.peerNodeId, transfer.chatId, FileTransferWire.chatPlaceholderMessageId(transfer.transferId))
    }

    private fun reserved(name: String): Boolean =
        name.startsWith("apu-story-video-", ignoreCase = true) ||
            name.startsWith("apu-avatar-video-", ignoreCase = true)

    suspend fun insert(
        manifest: FileTransferManifestFfi,
        senderId: String,
        chatId: String,
        now: Long,
    ): FileTransferEntity? {
        val messageId = FileTransferWire.chatPlaceholderMessageId(manifest.transferIdHex)
        if (suppressCleared(senderId, chatId, messageId)) return null
        val entity = FileTransferEntity(
            transferId = manifest.transferIdHex,
            messageId = messageId,
            chatId = chatId,
            peerNodeId = senderId,
            direction = "INCOMING",
            displayName = manifest.displayName,
            mediaType = manifest.mediaType,
            totalBytes = manifest.fileSize.toLong(),
            chunkSize = manifest.chunkSize.toInt(),
            chunkCount = manifest.chunkCount.toLong(),
            fileSha256 = manifest.fileSha256Hex,
            state = "OFFERED",
            createdAtMs = now,
            expiresAtMs = manifest.expiresAtMs,
            updatedAtMs = now,
        )
        val inserted = if (transferDao.insertNewTransfer(entity)) entity else return null
        if (suppressCleared(senderId, chatId, messageId)) { transferDao.deleteTransfer(entity.transferId); return null }
        TransferDiagnostics.count(Counters.FILE_STARTED_IN)
        TransferDiagnostics.record(
            "file",
            "входящий файл: ${DiagnosticsReport.formatBytes(entity.totalBytes)} " +
                "(${entity.mediaType}), кусков ${entity.chunkCount}",
        )
        return inserted
    }

}
