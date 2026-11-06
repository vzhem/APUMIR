package com.vladimir.messenger.data.avatavideo

import com.vladimir.messenger.data.local.entity.FileTransferEntity

/** Pure matching/retry rules for an announced avatar and its file transfer. */
internal object AvatarVideoTransferPolicy {
    // The last gap remains until the whole file has been verified and installed.
    const val MAX_UNVERIFIED_PROGRESS = 0.95f
    private const val PREPARING_STALE_MS = 5 * 60 * 1000L

    /** Old clients put the requester's ID here; current clients put the owner's. */
    fun acceptsReady(packetOwner: String, senderId: String, myId: String?): Boolean =
        !myId.isNullOrBlank() && senderId.isNotBlank() &&
            (packetOwner == myId || packetOwner == senderId)

    /** Only a live transfer suppresses a new request. A completed or orphaned one cannot. */
    fun inFlight(state: String, expiresAtMs: Long, updatedAtMs: Long, nowMs: Long): Boolean {
        if (expiresAtMs <= nowMs) return false
        if (state == "PREPARING") return nowMs - updatedAtMs < PREPARING_STALE_MS
        return state in setOf("PREPARED", "TRANSFERRING", "SENT", "WAITING_RECIPIENT", "CUSTODIED")
    }

    /** No other sender, hash or size may supply progress for this avatar. */
    fun progress(
        ownerId: String,
        sha256: String,
        expectedBytes: Long,
        row: FileTransferEntity,
        nowMs: Long,
    ): Float? {
        if (row.direction != "INCOMING" || row.peerNodeId != ownerId ||
            row.fileSha256 != sha256 || row.displayName != AvatarVideoWire.fileName(sha256) ||
            row.totalBytes != expectedBytes || expectedBytes <= 0 || row.expiresAtMs <= nowMs ||
            row.state !in setOf("OFFERED", "TRANSFERRING", "VERIFYING", "COMPLETE")
        ) return null
        return (row.transferredBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, MAX_UNVERIFIED_PROGRESS)
    }
}
