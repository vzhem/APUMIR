package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClearFileMetaGuardTest {
    @Test fun lateMirrorMetadataIsSuppressedByEitherFileId() = runTest {
        val cleared = mutableSetOf("file-transfer")
        val applied = mutableListOf<ChatClearWire.Batch>()
        val guard = ChatClearFileMetaGuard(
            isCleared = { peer, id -> peer == "friend" && id in cleared },
            apply = { peer, batch -> assertEquals("friend", peer); applied.add(batch); true },
        )
        assertTrue(guard.suppress("friend", "", "file.jpg", "sender-id", "transfer"))
        assertEquals(listOf("sender-id", "file-transfer"), applied.single().ids)
        assertFalse(guard.suppress("other", "", "file.jpg", "sender-id", "transfer"))
        cleared.clear()
        cleared.add("sender-id")
        assertTrue(guard.suppress("friend", "", "file.jpg", "sender-id", "transfer"))
    }

    @Test fun groupsAndReservedSystemTransfersRemainUntouched() = runTest {
        var checked = false
        val guard = ChatClearFileMetaGuard({ _, _ -> checked = true; true }, { _, _ -> error("must not clean") })
        assertFalse(guard.suppress("peer", "group", "file.jpg", "id", "transfer"))
        assertFalse(guard.suppress("peer", "", "APU-STORY-VIDEO-test", "id", "transfer"))
        assertFalse(guard.suppress("peer", "", "apu-avatar-video-test", "id", "transfer"))
        assertFalse(checked)
    }

    @Test fun postInsertionCheckFindsTombstoneCreatedDuringInsert() = runTest {
        var cleared = false
        var cleaned = 0
        val guard = ChatClearFileMetaGuard({ _, _ -> cleared }, { _, _ -> cleaned++; true })
        assertFalse(guard.suppress("peer", "", "file.jpg", "id", "transfer"))
        cleared = true // clear raced with the mirror insert
        assertTrue(guard.suppress("peer", "", "file.jpg", "id", "transfer"))
        assertEquals(1, cleaned)
    }

    @Test fun cleanupFailureIsNotReportedAsSuccess() = runTest {
        val guard = ChatClearFileMetaGuard({ _, _ -> true }, { _, _ -> false })
        var failed = false
        try { guard.suppress("peer", "", "file.jpg", "id", "transfer") }
        catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
    }
}
