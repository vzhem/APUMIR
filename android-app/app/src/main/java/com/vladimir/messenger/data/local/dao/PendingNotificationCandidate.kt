package com.vladimir.messenger.data.local.dao

/** Rowid is a local traversal hint, never an identity or a network message ID. */
data class PendingNotificationCandidate(val sequence: Long, val id: String)
