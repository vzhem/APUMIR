package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Public signed file-exchange binding pinned per legacy contact. No private material. */
@Entity(tableName = "file_exchange_peers")
data class FileExchangePeerEntity(
    @PrimaryKey val nodeId: String,
    val bindingBase64: String,
    val bindingSha256: String,
    val x25519PublicHex: String,
    val trustState: String,
    val firstSeenAtMs: Long,
    val updatedAtMs: Long,
)
