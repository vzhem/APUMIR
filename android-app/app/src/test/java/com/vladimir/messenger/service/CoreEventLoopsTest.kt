package com.vladimir.messenger.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEventLoopsTest {
    @Test
    fun fairnessGivesWaitingServiceWorkATurnAfterBoundedInteractiveBurst() {
        val policy = MainEventFairness()
        repeat(MainEventFairness.MAX_INTERACTIVE_BURST) {
            assertTrue(policy.preferInteractive())
            policy.observe(true)
        }
        assertFalse(policy.preferInteractive())
        // If only messages remain, keep asking for service work until one arrives.
        repeat(10) { policy.observe(true) }
        assertFalse(policy.preferInteractive())
        policy.observe(false)
        assertTrue(policy.preferInteractive())
    }

    @Test
    fun blockedFileHandlerDoesNotDelayMainMessagesOrDrainFileBacklog() = runBlocking {
        val queues = mapOf(
            CoreEventLoops.Lane.Main to ArrayDeque<String>(),
            CoreEventLoops.Lane.File to ArrayDeque<String>(),
        )
        val signals = queues.keys.associateWith { Channel<Unit>(Channel.CONFLATED) }
        fun enqueue(lane: CoreEventLoops.Lane, value: String) {
            queues.getValue(lane).addLast(value)
            signals.getValue(lane).trySend(Unit)
        }
        val fileStarted = CompletableDeferred<Unit>()
        val releaseFile = CompletableDeferred<Unit>()
        val messageDone = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        enqueue(CoreEventLoops.Lane.File, "file-1")
        enqueue(CoreEventLoops.Lane.File, "file-2")
        val loops = CoreEventLoops(
            isRunning = { true },
            await = { lane, _ ->
                if (queues.getValue(lane).isEmpty()) signals.getValue(lane).receive()
                true
            },
            poll = { lane, _ -> queues.getValue(lane).removeFirstOrNull() },
            isInteractive = { value: String -> value.startsWith("message") },
            process = { value ->
                if (value == "file-1") {
                    fileStarted.complete(Unit)
                    releaseFile.await()
                }
                processed += value
                if (value == "message-1") messageDone.complete(Unit)
            },
            refreshStatus = {},
            recordFailure = { _, error -> errors += error },
            waitMs = { 5_000L },
            statusIntervalMs = 5_000L,
        )
        val job = loops.start(this)
        try {
            withTimeout(1_000L) { fileStarted.await() }
            enqueue(CoreEventLoops.Lane.Main, "message-1")
            withTimeout(1_000L) { messageDone.await() }
            assertEquals(listOf("message-1"), processed)
            assertEquals(listOf("file-2"), queues.getValue(CoreEventLoops.Lane.File).toList())
            assertTrue(errors.isEmpty())
        } finally {
            releaseFile.complete(Unit)
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancellationDuringNativeWaitDoesNotPopAnotherEvent() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var polls = 0
        val loops = CoreEventLoops(
            isRunning = { true },
            await = { lane, _ ->
                if (lane == CoreEventLoops.Lane.Main) {
                    started.complete(Unit)
                    // Native Condvar waits are blocking and can finish after cancellation.
                    withContext(NonCancellable) { release.await() }
                    true
                } else awaitCancellation()
            },
            poll = { _, _ -> polls++; "event" },
            isInteractive = { _: String -> true },
            process = {},
            refreshStatus = {},
            recordFailure = { _, error -> throw error },
            waitMs = { 5_000L },
            statusIntervalMs = 5_000L,
        )
        val job = loops.start(this)
        withTimeout(1_000L) { started.await() }
        job.cancel()
        release.complete(Unit)
        withTimeout(1_000L) { job.join() }
        assertEquals(0, polls)
    }
}
