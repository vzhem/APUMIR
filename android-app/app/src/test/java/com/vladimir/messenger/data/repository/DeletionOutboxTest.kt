package com.vladimir.messenger.data.repository

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeletionOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun root(): File = temporary.root.resolve("deletion/v1")
    private fun entry(
        kind: String = DeletionOutbox.KIND_DIRECT,
        chat: String = "chat",
        peer: String = "pk_friend",
        id: String = "message",
        at: Long = 1_000L,
        deleter: String = "",
    ) = DeletionOutbox.Entry(id, kind, chat, peer, deleter, at, at, 1, emptyList())

    @Test fun retryUpdatesPersistAndRespectTheNextInterval() {
        val outbox = DeletionOutbox(root())
        val first = entry()
        val other = entry(chat = "other-chat", peer = "pk_other", id = "other")
        outbox.add(first)
        outbox.add(other)
        assertEquals(listOf(first, other), outbox.due(1_100L, 100L, 10_000L))

        outbox.markTried(first, 1_100L)
        val reopened = DeletionOutbox(root())
        assertEquals(listOf(first.copy(lastTryMs = 1_100L, attempts = 2), other), reopened.all())
        assertEquals(listOf(other), reopened.due(1_199L, 100L, 10_000L))
        assertEquals(listOf(first.copy(lastTryMs = 1_100L, attempts = 2), other),
            reopened.due(1_200L, 100L, 10_000L))
    }

    @Test fun groupRetryPersistsDistinctTargetsAndDoesNotChangeOtherEntries() {
        val outbox = DeletionOutbox(root())
        val group = entry(kind = DeletionOutbox.KIND_GROUP, peer = "", deleter = "pk_author")
        val other = entry(chat = "personal", id = "other")
        outbox.add(group)
        outbox.add(other)
        outbox.markTried(group, 2_000L, "pk_a")
        outbox.markTried(group, 2_100L, "pk_a")
        outbox.markTried(group, 2_200L, "pk_b")
        assertEquals(listOf(group.copy(lastTryMs = 2_200L, attempts = 4, tried = listOf("pk_a", "pk_b")), other),
            DeletionOutbox(root()).all())
    }

    @Test fun staleAttemptCannotModifyAReplacedRequestWithDifferentIdentity() {
        val outbox = DeletionOutbox(root())
        val original = entry()
        outbox.add(original)
        val stale = outbox.all().single()
        for (replacement in listOf(original.copy(atMs = 1_001L), original.copy(peerId = "pk_new"),
            original.copy(deleterId = "pk_new_deleter"))) {
            outbox.add(replacement)
            outbox.markTried(stale, 2_000L, "pk_a")
            assertEquals(listOf(replacement), DeletionOutbox(root()).all())
        }
    }

    @Test fun directAcknowledgementOnlyRemovesTheMatchingPeerAndLeavesGroups() {
        val outbox = DeletionOutbox(root())
        val first = entry(chat = "chat-a", peer = "pk_a")
        val second = entry(chat = "chat-b", peer = "pk_b")
        val group = entry(kind = DeletionOutbox.KIND_GROUP, chat = "group", peer = "")
        listOf(first, second, group).forEach(outbox::add)

        assertFalse(outbox.acknowledgeDirect("", "message"))
        assertFalse(outbox.acknowledgeDirect("pk_a", ""))
        assertFalse(outbox.acknowledgeDirect("pk_unknown", "message"))
        assertEquals(listOf(first, second, group), DeletionOutbox(root()).all())
        assertTrue(outbox.acknowledgeDirect("pk_a", "message"))
        assertFalse(outbox.acknowledgeDirect("pk_a", "message"))
        assertEquals(listOf(second, group), DeletionOutbox(root()).all())
        assertTrue(outbox.acknowledgeDirect("pk_b", "message"))
        assertEquals(listOf(group), DeletionOutbox(root()).all())
    }

    @Test fun ambiguousDirectAcknowledgementDoesNotGuessBetweenChats() {
        val outbox = DeletionOutbox(root())
        val first = entry(chat = "old-chat")
        val second = entry(chat = "restored-chat")
        outbox.add(first)
        outbox.add(second)
        assertFalse(outbox.acknowledgeDirect("pk_friend", "message"))
        assertEquals(listOf(first, second), DeletionOutbox(root()).all())
    }

    @Test fun concurrentRetriesOnOneInstanceDoNotLoseCountsOrGroupTargets() {
        val outbox = DeletionOutbox(root())
        val group = entry(kind = DeletionOutbox.KIND_GROUP, peer = "", deleter = "pk_author")
        outbox.add(group)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { number ->
                executor.submit {
                    start.await()
                    outbox.markTried(group, 2_000L, "pk_$number")
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val stored = DeletionOutbox(root()).all().single()
        assertEquals(33, stored.attempts)
        assertEquals(2_000L, stored.lastTryMs)
        assertEquals((1..32).map { "pk_$it" }.toSet(), stored.tried.toSet())
        assertEquals(32, stored.tried.size)
    }

    @Test fun failedRewriteDoesNotReportAcknowledgementOrEraseTheOldFile() {
        val outbox = DeletionOutbox(root())
        val pending = entry()
        outbox.add(pending)
        assertTrue(root().resolve("outbox.v1.tmp").mkdir())
        try {
            outbox.acknowledgeDirect("pk_friend", "message")
            fail("an unwritable temporary path must not report success")
        } catch (_: IOException) {
            // The original file must remain readable after the failed rewrite.
        }
        assertEquals(listOf(pending), DeletionOutbox(root()).all())
    }

    @Test fun missingAttemptAndAcknowledgementDoNotCreateAnOutbox() {
        val outbox = DeletionOutbox(root())
        outbox.markTried(entry(), 2_000L)
        assertFalse(outbox.acknowledgeDirect("pk_friend", "message"))
        assertFalse(root().exists())
    }
}
