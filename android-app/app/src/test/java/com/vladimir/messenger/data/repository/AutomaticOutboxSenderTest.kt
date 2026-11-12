package com.vladimir.messenger.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticOutboxSenderTest {
    private fun unexpected(error: Exception): Unit = throw AssertionError("Unexpected preflight failure", error)

    @Test fun clearedSavedPeerIsSkippedBeforeNetwork() = runBlocking {
        val checks = mutableListOf<Pair<String, String>>()
        var sent = 0
        val guard = AutomaticOutboxSender(
            chatContact = { error("must not need chat after a matching tombstone") },
            isCleared = { peer, id -> checks += peer to id; peer == "pk_original" && id == "id" },
            transport = { _, _, _, _ -> sent++; true }, recordFailure = ::unexpected,
        )
        assertNull(guard.send("id", "chat", "pk_original", "private"))
        assertEquals(listOf("pk_original" to "id"), checks)
        assertEquals(0, sent)
    }

    @Test fun clearedCurrentChatContactAlsoSuppressesAnOlderSavedRecipient() = runBlocking {
        val checks = mutableListOf<String>()
        var sent = 0
        val guard = AutomaticOutboxSender(
            chatContact = { "pk_current" },
            isCleared = { peer, _ -> checks += peer; peer == "pk_current" },
            transport = { _, _, _, _ -> sent++; true }, recordFailure = ::unexpected,
        )
        assertNull(guard.send("id", "chat", "pk_original", "private"))
        assertEquals(listOf("pk_original", "pk_current"), checks)
        assertEquals(0, sent)
    }

    @Test fun unrelatedTombstonesDoNotAffectAnotherPeerOrId() = runBlocking {
        val cleared = setOf("pk_other" to "id", "pk_peer" to "other-id")
        val sent = mutableListOf<String>()
        val guard = AutomaticOutboxSender(
            chatContact = { "pk_peer" }, isCleared = { peer, id -> (peer to id) in cleared },
            transport = { id, _, _, _ -> sent += id; true }, recordFailure = ::unexpected,
        )
        assertEquals(true, guard.send("id", "chat", "pk_peer", "private"))
        assertEquals(listOf("id"), sent)
    }

    @Test fun noTombstonePreservesTransportResultAndDoesNotDoubleCheckSamePeer() = runBlocking {
        var checks = 0
        val guard = AutomaticOutboxSender(
            chatContact = { "pk_peer" }, isCleared = { _, _ -> checks++; false },
            transport = { _, _, _, _ -> false }, recordFailure = ::unexpected,
        )
        assertEquals(false, guard.send("id", "chat", "pk_peer", "private"))
        assertEquals(1, checks)
    }

    @Test fun missingChatCannotSendAnOrphanedRow() = runBlocking {
        var sent = false
        val guard = AutomaticOutboxSender(
            chatContact = { null }, isCleared = { _, _ -> false },
            transport = { _, _, _, _ -> sent = true; true }, recordFailure = ::unexpected,
        )
        assertNull(guard.send("id", "missing", "pk_peer", "private"))
        assertFalse(sent)
    }

    @Test fun storageFailureFailsClosedIsReportedAndIsNotANetworkAttempt() = runBlocking {
        var sent = false
        val errors = mutableListOf<Exception>()
        val guard = AutomaticOutboxSender(
            chatContact = { "pk_peer" }, isCleared = { _, _ -> error("database unavailable") },
            transport = { _, _, _, _ -> sent = true; true }, recordFailure = { errors += it },
        )
        assertNull(guard.send("id", "chat", "pk_peer", "private"))
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IllegalStateException)
        assertFalse(sent)
    }

    @Test fun cancellationPropagatesWithoutReportingOrSending() = runBlocking {
        var sent = false
        val guard = AutomaticOutboxSender(
            chatContact = { throw CancellationException("cancelled") }, isCleared = { _, _ -> false },
            transport = { _, _, _, _ -> sent = true; true }, recordFailure = ::unexpected,
        )
        assertTrue(runCatching { guard.send("id", "chat", "pk_peer", "private") }.exceptionOrNull() is CancellationException)
        assertFalse(sent)
    }

    @Test fun transportFailureStillBelongsToTheCaller() = runBlocking {
        val guard = AutomaticOutboxSender(
            chatContact = { "pk_peer" }, isCleared = { _, _ -> false },
            transport = { _, _, _, _ -> error("transport failure") }, recordFailure = ::unexpected,
        )
        assertTrue(runCatching { guard.send("id", "chat", "pk_peer", "private") }.exceptionOrNull() is IllegalStateException)
    }
}
