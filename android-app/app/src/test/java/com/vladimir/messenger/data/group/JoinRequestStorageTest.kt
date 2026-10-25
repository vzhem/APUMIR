package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinRequestStorageTest {

    @Test
    fun unknownDirectoryGroupUsesHistoryAndOutboxNotForeignKeyTable() {
        assertEquals(
            JoinRequestStorage.HISTORY_AND_OUTBOX,
            joinRequestStorage(groupKnownLocally = false),
        )
    }

    @Test
    fun knownLocalGroupKeepsRoomRequestCopy() {
        assertEquals(
            JoinRequestStorage.LOCAL_GROUP_REQUESTS,
            joinRequestStorage(groupKnownLocally = true),
        )
    }
}
