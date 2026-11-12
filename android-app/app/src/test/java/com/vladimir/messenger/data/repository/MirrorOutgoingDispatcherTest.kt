package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.mirror.MirrorRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorOutgoingDispatcherTest {
    private fun row() = MirrorRow(
        id = "stable-id", chatId = "shadow-chat", contactName = "Friend", senderId = "self",
        content = "private text", timestamp = 42, mine = true, recipientId = "pk_friend123", status = "PENDING",
    )

    @Test fun policyAcceptsOnlyRetryableWellFormedPairedRequests() {
        val base = row()
        assertTrue(MirrorOutgoingPolicy.accepts(base))
        assertTrue(MirrorOutgoingPolicy.accepts(base.copy(status = "QUEUED_OFFLINE")))
        assertTrue(MirrorOutgoingPolicy.accepts(base.copy(status = "SENT"))) // legacy default when status is absent
        assertTrue(MirrorOutgoingPolicy.accepts(base.copy(manualRetry = true, status = "SENT")))
        assertTrue(MirrorOutgoingPolicy.accepts(base.copy(manualRetry = true, status = "FAILED")))
        assertTrue(MirrorOutgoingPolicy.accepts(base.copy(recipientId = "https://invite/?node=pk_friend123&x=1")))
        for (bad in listOf(
            base.copy(mine = false), base.copy(id = ""), base.copy(id = "abc\u0000def"),
            base.copy(id = "abc|def"), base.copy(id = "я".repeat(65)), base.copy(recipientId = ""),
            base.copy(recipientId = "pk_"), base.copy(recipientId = "pk_friend\u0000other"),
            base.copy(recipientId = "pk_friend|other"), base.copy(status = "READ"),
            base.copy(manualRetry = true, status = "READ"), base.copy(manualRetry = true, status = "LOCAL_FILE"),
        )) assertFalse(MirrorOutgoingPolicy.accepts(bad))
    }

    @Test fun scopedTombstoneAndIdentityFailurePreventChatCreationOrSend() = runBlocking {
        var created = 0
        var sent = 0
        val errors = mutableListOf<Exception>()
        val dispatcher = MirrorOutgoingDispatcher(
            isCleared = { peer, id -> peer == "pk_friend123" && id == "stable-id" },
            localChat = { created++; "chat" },
            initial = { _, _ -> sent++ }, manual = { _, _ -> sent++ }, recordFailure = { errors += it },
        )
        dispatcher.dispatch(row())
        dispatcher.dispatch(row().copy(manualRetry = true))
        dispatcher.dispatch(row().copy(mine = false))
        assertEquals(0, created)
        assertEquals(0, sent)
        assertTrue(errors.isEmpty())

        val failed = MirrorOutgoingDispatcher(
            isCleared = { _, _ -> error("identity unavailable") }, localChat = { created++; "chat" },
            initial = { _, _ -> sent++ }, manual = { _, _ -> sent++ }, recordFailure = { errors += it },
        )
        failed.dispatch(row())
        assertEquals(1, errors.size)
        assertEquals(0, created)
        assertEquals(0, sent)
    }

    @Test fun aliasChecksBothScopesAndRoutesOnlyTheMatchingMode() = runBlocking {
        val checks = mutableListOf<Pair<String, String>>()
        val routed = mutableListOf<String>()
        val raw = "https://invite/?node=pk_friend123&x=1"
        val dispatcher = MirrorOutgoingDispatcher(
            isCleared = { peer, id -> checks += peer to id; false },
            localChat = { peer -> assertEquals(raw, peer); "local-chat" },
            initial = { frame, chat -> routed += "initial:${frame.id}:$chat" },
            manual = { frame, chat -> routed += "manual:${frame.id}:$chat" },
            recordFailure = { throw AssertionError("unexpected", it) },
        )
        dispatcher.dispatch(row().copy(recipientId = raw))
        dispatcher.dispatch(row().copy(recipientId = raw, manualRetry = true, status = "SENT"))
        assertEquals(listOf(raw to "stable-id", "pk_friend123" to "stable-id", raw to "stable-id", "pk_friend123" to "stable-id"), checks)
        assertEquals(listOf("initial:stable-id:local-chat", "manual:stable-id:local-chat"), routed)
    }

    @Test fun aClearedCanonicalAliasNeverCreatesAChat() = runBlocking {
        var created = 0
        val dispatcher = MirrorOutgoingDispatcher(
            isCleared = { peer, _ -> peer == "pk_friend123" }, localChat = { created++; "chat" },
            initial = { _, _ -> error("unexpected send") }, manual = { _, _ -> error("unexpected retry") },
            recordFailure = { throw AssertionError("unexpected", it) },
        )
        dispatcher.dispatch(row().copy(recipientId = "https://invite/?node=pk_friend123"))
        assertEquals(0, created)
    }

    @Test fun cancellationPropagatesAndOrdinaryFailuresAreReported() = runBlocking {
        val errors = mutableListOf<Exception>()
        var sent = false
        val cancelled = MirrorOutgoingDispatcher(
            isCleared = { _, _ -> false }, localChat = { throw CancellationException("cancelled") },
            initial = { _, _ -> sent = true }, manual = { _, _ -> sent = true }, recordFailure = { errors += it },
        )
        assertTrue(runCatching { cancelled.dispatch(row()) }.exceptionOrNull() is CancellationException)
        assertFalse(sent)
        assertTrue(errors.isEmpty())
        val failed = MirrorOutgoingDispatcher(
            isCleared = { _, _ -> false }, localChat = { "chat" },
            initial = { _, _ -> error("storage") }, manual = { _, _ -> sent = true }, recordFailure = { errors += it },
        )
        failed.dispatch(row())
        assertEquals(1, errors.size)
        assertFalse(sent)
    }
}
