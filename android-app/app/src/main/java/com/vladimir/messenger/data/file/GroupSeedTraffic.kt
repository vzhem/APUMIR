package com.vladimir.messenger.data.file

/** Current/this-process upload facts for one shared transfer. */
data class GroupSeedTraffic(
    val activeRequesterIds: List<String>,
    val completedRequesterCount: Int,
    val uploadedBytes: Long,
)
