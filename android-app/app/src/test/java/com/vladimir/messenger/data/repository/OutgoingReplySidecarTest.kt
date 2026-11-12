package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingReplySidecarTest {
    private val reply = DirectReplyWire.Target("quoted", "Alice", "hello\n  there")
    private fun row() = outgoingTextRow("parent", "chat", "pk_friend", "answer", 42L, reply, "red", "blue")

    @Test fun sendsOnlyTheParentScopedReplyWithItsStableTransportId() = runBlocking {
        val checks = mutableListOf<Triple<String, String, String>>()
        val sent = mutableListOf<List<String>>()
        val sidecar = OutgoingReplySidecar(
            find = { row() },
            preflight = { id, chat, peer -> checks += Triple(id, chat, peer); true },
            transport = { id, chat, peer, payload -> sent += listOf(id, chat, peer, payload); true },
            recordFailure = { throw AssertionError("unexpected", it) },
        )
        assertTrue(sidecar.send("parent", "chat", "pk_friend", reply))
        assertEquals(listOf(Triple("parent", "chat", "pk_friend")), checks)
        assertEquals(DirectReplyWire.transportMessageId("parent"), sent.single()[0])
        assertEquals("chat", sent.single()[1])
        assertEquals("pk_friend", sent.single()[2])
        val decoded = DirectReplyWire.parse(sent.single()[3])!!
        assertEquals("parent", decoded.messageId)
        assertEquals("quoted", decoded.replyToId)
        assertEquals("Alice", decoded.replyAuthor)
        assertEquals("hello there", decoded.replyText)
    }

    @Test fun deletedOrMismatchedRowsNeverReachPreflightOrTransport() = runBlocking {
        var current: MessageEntity? = row()
        var checks = 0
        var sends = 0
        val sidecar = OutgoingReplySidecar(
            find = { current }, preflight = { _, _, _ -> checks++; true },
            transport = { _, _, _, _ -> sends++; true }, recordFailure = { throw AssertionError("unexpected", it) },
        )
        for (candidate in listOf(
            null, row().copy(id = "another"), row().copy(isFromMe = false), row().copy(senderId = "pk_other"),
            row().copy(chatId = "other"), row().copy(recipientId = "pk_other"), row().copy(replyToId = "other"),
            row().copy(replyAuthor = "Bob"), row().copy(replyText = "different"),
            row().copy(channel = "GROUP"), row().copy(channel = " group "),
            row().copy(topicId = "topic"), row().copy(topicId = " "),
            row().copy(status = "LOCAL_FILE"), row().copy(status = "FILE_EXPIRED"),
        )) {
            current = candidate
            assertFalse(sidecar.send("parent", "chat", "pk_friend", reply))
        }
        current = row()
        assertFalse(sidecar.send("parent", "chat", "pk_friend\u0000other", reply))
        assertEquals(0, checks)
        assertEquals(0, sends)
    }

    @Test fun savedLegacyRecipientIsNormalizedWithoutSubstitutingAnotherPeer() = runBlocking {
        val row = row().copy(recipientId = "https://invite/?node=pk_friend&x=1")
        val sent = mutableListOf<String>()
        val sidecar = OutgoingReplySidecar(
            find = { row }, preflight = { _, _, _ -> true },
            transport = { _, _, peer, _ -> sent += peer; true }, recordFailure = { throw AssertionError("unexpected", it) },
        )
        assertTrue(sidecar.send("parent", "chat", "pk_friend", reply))
        assertFalse(sidecar.send("parent", "chat", "pk_other", reply))
        assertEquals(listOf("pk_friend"), sent)
    }

    @Test fun failedParentPreflightNeverSendsAndTransportFailureIsNotSuccess() = runBlocking {
        var allowed = false
        var sends = 0
        val sidecar = OutgoingReplySidecar(
            find = { row() }, preflight = { id, _, _ -> assertEquals("parent", id); allowed },
            transport = { _, _, _, _ -> sends++; false }, recordFailure = { throw AssertionError("unexpected", it) },
        )
        assertFalse(sidecar.send("parent", "chat", "pk_friend", reply))
        assertEquals(0, sends)
        allowed = true
        assertFalse(sidecar.send("parent", "chat", "pk_friend", reply))
        assertEquals(1, sends)
    }

    @Test fun cancellationPropagatesAndOrdinaryErrorsAreReported() = runBlocking {
        val failures = mutableListOf<Exception>()
        val cancelled = OutgoingReplySidecar(
            find = { row() }, preflight = { _, _, _ -> throw CancellationException("cancelled") },
            transport = { _, _, _, _ -> error("must not send") }, recordFailure = { failures += it },
        )
        assertTrue(runCatching { cancelled.send("parent", "chat", "pk_friend", reply) }.exceptionOrNull() is CancellationException)
        assertTrue(failures.isEmpty())
        val failing = OutgoingReplySidecar(
            find = { row() }, preflight = { _, _, _ -> true },
            transport = { _, _, _, _ -> throw IllegalStateException("storage") }, recordFailure = { failures += it },
        )
        assertFalse(failing.send("parent", "chat", "pk_friend", reply))
        assertEquals(1, failures.size)
        val cancelledTransport = OutgoingReplySidecar(
            find = { row() }, preflight = { _, _, _ -> true },
            transport = { _, _, _, _ -> throw CancellationException("cancelled") }, recordFailure = { failures += it },
        )
        assertTrue(runCatching { cancelledTransport.send("parent", "chat", "pk_friend", reply) }.exceptionOrNull() is CancellationException)
        assertEquals(1, failures.size)
    }
}
