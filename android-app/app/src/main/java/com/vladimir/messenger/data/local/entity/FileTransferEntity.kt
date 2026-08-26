package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bounded transfer metadata only. File keys, plaintext and filesystem paths never belong in Room. */
@Entity(
    tableName = "file_transfers",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["state"]),
        Index(value = ["expiresAtMs"]),
    ],
)
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val messageId: String,
    val chatId: String,
    val peerNodeId: String,
    val direction: String,
    val displayName: String,
    val mediaType: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val chunkCount: Long,
    val fileSha256: String,
    val state: String,
    val completedChunks: Long = 0,
    val transferredBytes: Long = 0,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val updatedAtMs: Long,
    val errorCode: String? = null,
)
