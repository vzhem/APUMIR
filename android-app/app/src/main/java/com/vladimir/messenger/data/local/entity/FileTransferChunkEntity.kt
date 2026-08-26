package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Durable chunk progress. Encrypted bytes live in a transfer-ID-derived app-private file. */
@Entity(
    tableName = "file_transfer_chunks",
    primaryKeys = ["transferId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = FileTransferEntity::class,
            parentColumns = ["transferId"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["transferId"])],
)
data class FileTransferChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val state: String,
    val ciphertextBytes: Long,
    val chunkSha256: String? = null,
    val updatedAtMs: Long,
)
