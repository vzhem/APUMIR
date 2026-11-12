package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAwareOutboxSenderTest {
    private val peer = "pk_abcdefgh12345678"
    private fun row(id: String = "parent") = MessageEntity(
        id = id, chatId = "chat", senderId = "self", content = "answer", timestamp = 1,
        status = "PENDING", isFromMe = true, recipientId = peer,
        replyToId = "quoted", replyAuthor = "Alice", replyText = "hello there",
    )

    @Test fun retriesStoredReplyAfterBodyUsingStableSidecarId() = runBlocking {
        val events = mutableListOf<String>()
        var wire: String? = null
        var wireId: String? = null
        val sidecar = OutgoingReplySidecar(
            find = { row() }, preflight = { id, _, _ -> events += "preflight:$id"; true },
            transport = { id, _, _, payload -> wireId = id; wire = payload; events += "reply"; true },
            recordFailure = { throw AssertionError(it) },
        )
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> events += "body"; true },
            sendManualBody = { _, _, _, _ -> error("not manual") },
            find = { row() }, personalScope = { _, _ -> events += "scope"; true },
            sendReply = sidecar::send, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(listOf("body", "scope", "preflight:parent", "reply"), events)
        assertEquals(DirectReplyWire.transportMessageId("parent"), wireId)
        assertEquals(DirectReplyWire.Packet("parent", "quoted", "Alice", "hello there"), DirectReplyWire.parse(wire))
    }

    @Test fun falseBodyResultStillOffersReplyButBlockedBodyDoesNot() = runBlocking {
        var bodyResult: Boolean? = false
        var replies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> bodyResult }, sendManualBody = { _, _, _, _ -> true },
            find = { row() }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> replies++; false }, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(false, sender.send("parent", "chat", peer, "body"))
        assertEquals(1, replies)
        bodyResult = null
        assertNull(sender.send("parent", "chat", peer, "body"))
        assertEquals(1, replies)
    }

    @Test fun manualRetryUsesItsOwnBodySenderAndSameReplyPolicy() = runBlocking {
        val calls = mutableListOf<String>()
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> calls += "automatic"; true },
            sendManualBody = { _, _, _, _ -> calls += "manual"; false },
            find = { row().copy(status = "FAILED") }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> calls += "reply"; true }, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(false, sender.sendManual("parent", "chat", peer, "body"))
        assertEquals(listOf("manual", "reply"), calls)
    }

    @Test fun deletedChangedIncomingGroupOrMissingReplyNeverSendsSidecar() = runBlocking {
        var current: MessageEntity? = row()
        var replies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { current }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> replies++; true }, recordFailure = { throw AssertionError(it) },
        )
        val cases = listOf(
            null, row().copy(id = "other"), row().copy(chatId = "other"),
            row().copy(isFromMe = false), row().copy(senderId = "pk_someone"),
            row().copy(status = "LOCAL_FILE"), row().copy(channel = "GROUP"), row().copy(channel = " group "),
            row().copy(topicId = "topic"), row().copy(topicId = " "), row().copy(replyToId = null), row().copy(replyToId = " "),
        )
        for (candidate in cases) {
            current = candidate
            assertEquals(true, sender.send("parent", "chat", peer, "body"))
        }
        assertEquals(0, replies)
    }

    @Test fun malformedParentOrPeerAndRejectedScopeNeverSend() = runBlocking {
        var replies = 0
        var scopes = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { row(it) }, personalScope = { _, _ -> scopes++; false },
            sendReply = { _, _, _, _ -> replies++; true }, recordFailure = { throw AssertionError(it) },
        )
        for (bad in listOf("", "a|b", "a\u0000b", "x".repeat(129))) {
            assertEquals(true, sender.send(bad, "chat", peer, "body"))
        }
        assertEquals(true, sender.send("parent", "chat", "pk_???", "body"))
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(1, scopes)
        assertEquals(0, replies)
    }

    @Test fun sidecarRereadsChangedQuoteOrRecipientBeforeTransport() = runBlocking {
        var current = row()
        var sends = 0
        val sidecar = OutgoingReplySidecar(
            find = { current }, preflight = { _, _, _ -> true },
            transport = { _, _, _, _ -> sends++; true }, recordFailure = { throw AssertionError(it) },
        )
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { current }, personalScope = { _, _ -> current = current.copy(replyText = "changed"); true },
            sendReply = sidecar::send, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(0, sends)
        current = row()
        val changedRecipient = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { current }, personalScope = { _, _ -> current = current.copy(recipientId = "pk_other1234567"); true },
            sendReply = sidecar::send, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(true, changedRecipient.send("parent", "chat", peer, "body"))
        assertEquals(0, sends)
        for (changed in listOf(row().copy(channel = " group "), row().copy(topicId = " "),
                               row().copy(status = "LOCAL_FILE"))) {
            current = row()
            val reclassified = ReplyAwareOutboxSender(
                sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
                find = { current }, personalScope = { _, _ -> current = changed; true },
                sendReply = sidecar::send, recordFailure = { throw AssertionError(it) },
            )
            assertEquals(true, reclassified.send("parent", "chat", peer, "body"))
        }
        assertEquals(0, sends)
    }

    @Test fun parentClearPreflightAfterBodySuppressesReply() = runBlocking {
        var cleared = false
        var sends = 0
        val sidecar = OutgoingReplySidecar(
            find = { row() }, preflight = { id, _, _ -> id == "parent" && !cleared },
            transport = { _, _, _, _ -> sends++; true }, recordFailure = { throw AssertionError(it) },
        )
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> cleared = true; true }, sendManualBody = { _, _, _, _ -> true },
            find = { row() }, personalScope = { _, _ -> true }, sendReply = sidecar::send,
            recordFailure = { throw AssertionError(it) },
        )
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(0, sends)
    }

    @Test fun racingBodyAckDoesNotSuppressTheCurrentReplyAttempt() = runBlocking {
        var current = row()
        var replies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> current = current.copy(status = "DELIVERED"); true },
            sendManualBody = { _, _, _, _ -> true }, find = { current }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> replies++; true }, recordFailure = { throw AssertionError(it) },
        )
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(1, replies)
    }

    @Test fun replyFailureIsRecordedButDoesNotChangeBodyResult() = runBlocking {
        val failures = mutableListOf<Exception>()
        var result = true
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> result }, sendManualBody = { _, _, _, _ -> true },
            find = { row() }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> error("private content") }, recordFailure = { failures += it },
        )
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        result = false
        assertEquals(false, sender.send("parent", "chat", peer, "body"))
        assertEquals(2, failures.size)
    }

    @Test fun parentTransportExceptionNeverAttemptsReply() = runBlocking {
        var finds = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> error("body failed") }, sendManualBody = { _, _, _, _ -> true },
            find = { finds++; row() }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> error("not reached") }, recordFailure = { error("not reached") },
        )
        assertTrue(runCatching { sender.send("parent", "chat", peer, "body") }.isFailure)
        assertEquals(0, finds)
    }

    @Test fun cancellationPropagatesAndReleasesReplyGate() = runBlocking {
        var cancelled = true
        var sends = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { if (cancelled) throw CancellationException("cancel") else row() },
            personalScope = { _, _ -> true }, sendReply = { _, _, _, _ -> sends++; true },
            recordFailure = { error("cancellation is not an ordinary failure") },
        )
        assertTrue(runCatching { sender.send("parent", "chat", peer, "body") }.exceptionOrNull() is CancellationException)
        cancelled = false
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(1, sends)
    }

    @Test fun concurrentAutomaticAndManualRetriesShareOneReplyGate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var replies = 0
        var bodies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> bodies++; true }, sendManualBody = { _, _, _, _ -> bodies++; false },
            find = { row() }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ -> replies++; entered.complete(Unit); release.await(); true },
            recordFailure = { throw AssertionError(it) },
        )
        withTimeout(5_000) {
            val first = async { sender.send("parent", "chat", peer, "body") }
            entered.await()
            val second = async { sender.sendManual("parent", "chat", peer, "body") }
            assertEquals(false, second.await())
            assertEquals(1, replies)
            release.complete(Unit)
            assertEquals(true, first.await())
        }
        assertEquals(2, bodies)
        assertEquals(true, sender.send("parent", "chat", peer, "body"))
        assertEquals(2, replies)
    }

    @Test fun cancellationDuringReplyReleasesTheGateForTheNextRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var block = true
        var replies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { row() }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ ->
                replies++
                if (block) { entered.complete(Unit); awaitCancellation() }
                true
            }, recordFailure = { error("cancellation must not be reported as an ordinary failure") },
        )
        withTimeout(5_000) {
            val first = launch { sender.send("parent", "chat", peer, "body") }
            entered.await()
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            block = false
            assertEquals(true, sender.send("parent", "chat", peer, "body"))
        }
        assertEquals(2, replies)
    }

    @Test fun boundedGateSkipsOverflowWithoutBlockingBodiesAndRecovers() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var replies = 0
        var bodies = 0
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> bodies++; true }, sendManualBody = { _, _, _, _ -> true },
            find = { row(it) }, personalScope = { _, _ -> true },
            sendReply = { _, _, _, _ ->
                replies++
                if (replies == 64) entered.complete(Unit)
                release.await()
                true
            }, recordFailure = { throw AssertionError(it) },
        )
        withTimeout(10_000) {
            val jobs = (0 until 64).map { n -> async { sender.send("msg$n", "chat", peer, "body") } }
            entered.await()
            assertEquals(true, sender.send("overflow", "chat", peer, "body"))
            assertEquals(65, bodies)
            assertEquals(64, replies)
            release.complete(Unit)
            jobs.forEach { assertEquals(true, it.await()) }
            assertEquals(true, sender.send("overflow", "chat", peer, "body"))
        }
        assertEquals(66, bodies)
        assertEquals(65, replies)
    }

    @Test fun blockedReplyDoesNotBlockAnotherMessage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replies = mutableListOf<String>()
        val sender = ReplyAwareOutboxSender(
            sendBody = { _, _, _, _ -> true }, sendManualBody = { _, _, _, _ -> true },
            find = { row(it) }, personalScope = { _, _ -> true },
            sendReply = { id, _, _, _ -> replies += id; if (id == "first") { entered.complete(Unit); release.await() }; true },
            recordFailure = { throw AssertionError(it) },
        )
        withTimeout(5_000) {
            val first = async { sender.send("first", "chat", peer, "body") }
            entered.await()
            assertEquals(true, sender.send("second", "chat", peer, "body"))
            assertEquals(listOf("first", "second"), replies)
            release.complete(Unit)
            assertEquals(true, first.await())
        }
    }
}
