package com.vladimir.messenger.data.group

/**
 * Where the caller's copy of a join request may be stored.
 *
 * [GroupJoinRequestEntity] has a foreign key to the local groups table. A row
 * received from the network directory is not in that table yet, so writing it
 * to the Room request table would crash with a foreign-key constraint error.
 * Such requests are represented by the durable history/outbox instead and are
 * materialized in Room only after the group becomes known locally.
 */
internal enum class JoinRequestStorage {
    LOCAL_GROUP_REQUESTS,
    HISTORY_AND_OUTBOX,
}

internal fun joinRequestStorage(groupKnownLocally: Boolean): JoinRequestStorage =
    if (groupKnownLocally) {
        JoinRequestStorage.LOCAL_GROUP_REQUESTS
    } else {
        JoinRequestStorage.HISTORY_AND_OUTBOX
    }
