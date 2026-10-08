package com.vladimir.messenger.domain.model

enum class MessageStatus {
    PENDING,
    QUEUED_OFFLINE,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    /** Chat row is a local placeholder for an outgoing file transfer. */
    LOCAL_FILE,
    /** An outgoing file transfer expired before receiver confirmation. */
    FILE_EXPIRED,
}
