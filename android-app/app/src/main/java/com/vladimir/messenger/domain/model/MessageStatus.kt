package com.vladimir.messenger.domain.model

enum class MessageStatus {
    PENDING,
    QUEUED_OFFLINE,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
