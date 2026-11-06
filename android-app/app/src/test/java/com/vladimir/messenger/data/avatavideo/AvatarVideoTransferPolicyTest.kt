package com.vladimir.messenger.data.avatavideo

import com.vladimir.messenger.data.file.FakeFileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarVideoTransferPolicyTest {
    private val sha = "a".repeat(64)
    private val now = 1_000_000L

    private fun incoming() = FileTransferEntity(
        transferId = "transfer",
        messageId = "message",
        chatId = "chat",
        peerNodeId = "owner",
        direction = "INCOMING",
        displayName = AvatarVideoWire.fileName(sha),
        mediaType = "video/mp4",
        totalBytes = 1_000L,
        chunkSize = 100,
        chunkCount = 10,
        fileSha256 = sha,
        state = "TRANSFERRING",
        transferredBytes = 250L,
        createdAtMs = 1L,
        expiresAtMs = now + 1_000L,
        updatedAtMs = now,
    )

    private fun progress(row: FileTransferEntity): Float? =
        AvatarVideoTransferPolicy.progress("owner", sha, 1_000L, row, now)

    @Test
    fun readyAddressesOwnerAndStillAcceptsLegacyRequester() {
        val request = AvatarVideoWire.parse(AvatarVideoWire.ready("owner", sha)) as AvatarVideoWire.Packet.Ready
        assertEquals("owner", request.ownerId)
        assertTrue(AvatarVideoTransferPolicy.acceptsReady(request.ownerId, "friend", "owner"))
        assertTrue(AvatarVideoTransferPolicy.acceptsReady("friend", "friend", "owner"))
        assertFalse(AvatarVideoTransferPolicy.acceptsReady("someone-else", "friend", "owner"))
        assertFalse(AvatarVideoTransferPolicy.acceptsReady("friend", "friend", null))
        assertFalse(AvatarVideoTransferPolicy.acceptsReady("owner", "", "owner"))
    }

    @Test
    fun ringUsesOnlyMatchingIncomingBytesAndReservesCompletionForVerification() {
        val row = incoming()
        assertEquals(0.25f, progress(row)!!, 0.0001f)
        assertEquals(0f, progress(row.copy(transferredBytes = -5L))!!, 0.0001f)
        assertEquals(0.95f, progress(row.copy(state = "VERIFYING", transferredBytes = 1_000L))!!, 0.0001f)
        assertEquals(0.95f, progress(row.copy(state = "COMPLETE", transferredBytes = 1_000L))!!, 0.0001f)
        assertNull(progress(row.copy(peerNodeId = "other")))
        assertNull(progress(row.copy(fileSha256 = "a".repeat(63) + "b")))
        assertNull(progress(row.copy(displayName = "apu-avatar-video-other.mp4")))
        assertNull(progress(row.copy(totalBytes = 999L)))
        assertNull(progress(row.copy(direction = "OUTGOING")))
        assertNull(progress(row.copy(state = "FAILED")))
        assertNull(progress(row.copy(expiresAtMs = now)))
        assertNull(AvatarVideoTransferPolicy.progress("owner", sha, 0L, row.copy(totalBytes = 0L), now))
    }

    @Test
    fun avatarQueryFiltersSortsAndEmitsProgressChanges() = runBlocking {
        val dao = FakeFileTransferDao()
        dao.insertTransferIgnore(incoming().copy(transferId = "active"))
        dao.insertTransferIgnore(incoming().copy(transferId = "complete", state = "COMPLETE", updatedAtMs = now + 1))
        dao.insertTransferIgnore(incoming().copy(transferId = "outgoing", direction = "OUTGOING"))
        dao.insertTransferIgnore(incoming().copy(transferId = "story", displayName = "apu-story-video-123.mp4"))
        dao.insertTransferIgnore(incoming().copy(transferId = "failed", state = "FAILED"))
        val observed = dao.observeAvatarVideoIncoming()
        assertEquals(listOf("active", "complete"), observed.first().map { it.transferId })
        val next = async(start = CoroutineStart.UNDISPATCHED) { observed.drop(1).first() }
        dao.advanceProgress("active", "TRANSFERRING", 7L, 750L, now + 2L, null)
        assertEquals(750L, next.await().first { it.transferId == "active" }.transferredBytes)
    }

    @Test
    fun onlyLiveAndNonExpiredTransfersSuppressARepeat() {
        for (state in listOf("PREPARED", "TRANSFERRING", "SENT", "WAITING_RECIPIENT", "CUSTODIED")) {
            assertTrue(state, AvatarVideoTransferPolicy.inFlight(state, now + 1, now, now))
            assertFalse(state, AvatarVideoTransferPolicy.inFlight(state, now, now, now))
        }
        for (state in listOf("COMPLETE", "FAILED", "CANCELLED")) {
            assertFalse(state, AvatarVideoTransferPolicy.inFlight(state, now + 1, now, now))
        }
        assertTrue(AvatarVideoTransferPolicy.inFlight("PREPARING", now + 1, now, now))
        assertFalse(AvatarVideoTransferPolicy.inFlight("PREPARING", now + 1, now - 300_000L, now))
    }
}
