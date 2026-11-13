package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorOutgoingEchoTest {
    private fun row() = MessageEntity(
        id = "id", chatId = "chat", senderId = "self", content = "body", timestamp = 42,
        isFromMe = true, status = "DELIVERED", recipientId = "pk_friend",
    )

    @Test fun echoesCurrentPersistedStatusWithoutStartingAnotherSend() = runBlocking {
        var checks = 0
        val emitted = mutableListOf<Pair<MessageEntity, String>>()
        val echo = MirrorOutgoingEcho(
            find = { row() }, preflight = { id, chat, peer ->
                checks++
                assertEquals("id", id); assertEquals("chat", chat); assertEquals("pk_friend", peer)
                true
            }, emit = { current, peer -> emitted += current to peer },
        )
        echo.publish("id", "chat", "pk_friend")
        assertEquals(1, checks)
        assertEquals(listOf(row() to "pk_friend"), emitted)
    }

    @Test fun missingForeignOrWrongScopeNeverEchoes() = runBlocking {
        var checks = 0
        var emitted = false
        suspend fun verify(current: MessageEntity?, chat: String = "chat", peer: String = "pk_friend") {
            MirrorOutgoingEcho(
                find = { current }, preflight = { _, _, _ -> checks++; true }, emit = { _, _ -> emitted = true },
            ).publish("id", chat, peer)
        }
        verify(null)
        verify(row().copy(isFromMe = false))
        verify(row().copy(id = "other"))
        verify(row().copy(chatId = "other"))
        verify(row().copy(recipientId = "pk_other"))
        verify(row(), peer = "")
        assertEquals(0, checks)
        assertFalse(emitted)
    }

    @Test fun preflightFailureAndCancellationDoNotEmit() = runBlocking {
        var emitted = false
        val denied = MirrorOutgoingEcho(find = { row() }, preflight = { _, _, _ -> false }, emit = { _, _ -> emitted = true })
        denied.publish("id", "chat", "pk_friend")
        assertFalse(emitted)
        val cancelled = MirrorOutgoingEcho(
            find = { row() }, preflight = { _, _, _ -> throw CancellationException("cancelled") },
            emit = { _, _ -> emitted = true },
        )
        assertTrue(runCatching { cancelled.publish("id", "chat", "pk_friend") }.exceptionOrNull() is CancellationException)
        assertFalse(emitted)
    }

    @Test fun legacyInviteRecipientIsComparedAsTheOriginalCanonicalPeer() = runBlocking {
        var echoedPeer = ""
        val echo = MirrorOutgoingEcho(
            find = { row().copy(recipientId = "https://invite/?node=pk_friend&x=1") },
            preflight = { _, _, _ -> true }, emit = { _, peer -> echoedPeer = peer },
        )
        echo.publish("id", "chat", "pk_friend")
        assertEquals("pk_friend", echoedPeer)
    }
}
