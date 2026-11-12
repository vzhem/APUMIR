package com.vladimir.messenger.data.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReplyIngressTest {
    private val peer = "pk_friend123"
    private val packet = DirectReplyWire.build("parent-1", "quoted-1", "Alice", "first\nsecond")
    private val id = DirectReplyWire.transportMessageId("parent-1")

    private suspend fun DirectReplyIngress.testNetwork(sender: String, chat: String, id: String, text: String, authenticated: Boolean) =
        network(sender, chat, id, text, authenticated, "opened-test-wire")

    private class Fixture {
        var cleared: (String, String) -> Boolean = { _, _ -> false }
        var apply: suspend (String, DirectReplyWire.Packet) -> Boolean = { _, _ -> true }
        var forward: (String, String, String, String) -> Unit = { _, _, _, _ -> }
        var ack: (String, String) -> Unit = { _, _ -> }
        val applied = mutableListOf<Pair<String, DirectReplyWire.Packet>>()
        val forwarded = mutableListOf<List<String>>()
        val acknowledgements = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        val ingress = DirectReplyIngress(
            isCleared = { sender, parent -> cleared(sender, parent) },
            apply = { sender, reply -> applied += sender to reply; apply(sender, reply) },
            applyNetwork = { sender, reply, _ -> applied += sender to reply; apply(sender, reply) },
            forwardVerified = { sender, chat, id, text ->
                forward(sender, chat, id, text)
                forwarded += listOf(sender, chat, id, text)
            },
            acknowledge = { id, sender -> ack(id, sender); acknowledgements += id to sender },
            recordFailure = { stage, _ -> failures += stage },
        )
    }

    @Test fun verifiedNetworkSidecarAppliesBeforeForwardAndAck() = runBlocking {
        val f = Fixture()
        val order = mutableListOf<String>()
        f.apply = { _, _ -> order += "apply"; true }
        f.forward = { _, _, _, _ -> order += "forward" }
        f.ack = { _, _ -> order += "ack" }
        assertEquals(DirectReplyResult.APPLIED, f.ingress.testNetwork(peer, "local-chat", id, packet, true))
        assertEquals(listOf("apply", "forward", "ack"), order)
        assertEquals(peer, f.applied.single().first)
        assertEquals("parent-1", f.applied.single().second.messageId)
        assertEquals("first second", f.applied.single().second.replyText)
        assertEquals(listOf(peer, "local-chat", id, packet), f.forwarded.single())
        assertEquals(id to peer, f.acknowledgements.single())
    }

    @Test fun untrustedMalformedAndMismatchedTransportPacketsNeverTouchStorageOrAck() = runBlocking {
        val f = Fixture()
        assertEquals(DirectReplyResult.NOT_PACKET, f.ingress.testNetwork(peer, "chat", id, "hello", true))
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", id, packet, false))
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork("not-a-node", "chat", id, packet, true))
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", "other-id", packet, true))
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", id, "APUDMR1|bad", true))
        val badParent = "x".repeat(129)
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", DirectReplyWire.transportMessageId(badParent),
            DirectReplyWire.build(badParent, "target", "a", "b"), true))
        val nulParent = "parent\u0000tail"
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", DirectReplyWire.transportMessageId(nulParent),
            DirectReplyWire.build(nulParent, "target", "a", "b"), true))
        assertTrue(f.applied.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun missingOrConflictingParentIsNeitherForwardedNorAcknowledged() = runBlocking {
        val f = Fixture()
        f.apply = { _, _ -> false }
        assertEquals(DirectReplyResult.UNAPPLIED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        assertTrue(f.acknowledgements.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        assertEquals(DirectReplyResult.UNAPPLIED, f.ingress.mirror(peer, id, packet, true))
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun mirrorRequiresTrustedSourceAndNeverEchoesOrAcknowledges() = runBlocking {
        val f = Fixture()
        assertEquals(DirectReplyResult.REJECTED, f.ingress.mirror(peer, id, packet, false))
        assertTrue(f.applied.isEmpty())
        assertEquals(DirectReplyResult.APPLIED, f.ingress.mirror(peer, id, packet, true))
        assertEquals(1, f.applied.size)
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun clearedAndUnavailableClearStateFailClosed() = runBlocking {
        val f = Fixture()
        f.cleared = { sender, parent -> sender == peer && parent == "parent-1" }
        assertEquals(DirectReplyResult.REJECTED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        f.cleared = { _, _ -> throw IllegalStateException("identity unavailable") }
        assertEquals(DirectReplyResult.UNAPPLIED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        assertEquals(listOf("apply"), f.failures)
        assertTrue(f.applied.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun storageFailureDoesNotAckOrForwardAndCanBeRetried() = runBlocking {
        val f = Fixture()
        f.apply = { _, _ -> throw IllegalStateException("sqlite") }
        assertEquals(DirectReplyResult.UNAPPLIED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        assertTrue(f.acknowledgements.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        assertEquals(listOf("apply"), f.failures)
        f.apply = { _, _ -> true }
        assertEquals(DirectReplyResult.APPLIED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        assertEquals(1, f.acknowledgements.size)
    }

    @Test fun noAckBeforeApplyReturnsAndCancellationLeavesNoAcknowledgement() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.apply = { _, _ -> started.complete(Unit); release.await(); true }
        val job = async { f.ingress.testNetwork(peer, "chat", id, packet, true) }
        started.await()
        assertTrue(f.acknowledgements.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.acknowledgements.isEmpty())
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.failures.isEmpty())
    }

    @Test fun cancellationAfterApplyWithoutSuspensionStillWithholdsAck() = runBlocking {
        val f = Fixture()
        f.apply = { _, _ -> currentCoroutineContext().cancel(CancellationException("stopped")); true }
        val job = async { f.ingress.testNetwork(peer, "chat", id, packet, true) }
        var caught = false
        try { job.await() } catch (_: CancellationException) { caught = true }
        assertTrue(caught && job.isCancelled)
        assertTrue(f.forwarded.isEmpty())
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun cancellationDuringForwardWithoutSuspensionStillWithholdsAck() = runBlocking {
        val f = Fixture()
        val job = async {
            val ownJob = currentCoroutineContext()[Job]!!
            f.forward = { _, _, _, _ -> ownJob.cancel(CancellationException("stopped")) }
            f.ingress.testNetwork(peer, "chat", id, packet, true)
        }
        var caught = false
        try { job.await() } catch (_: CancellationException) { caught = true }
        assertTrue(caught && job.isCancelled)
        assertEquals(1, f.forwarded.size)
        assertTrue(f.acknowledgements.isEmpty())
    }

    @Test fun cancellationFromClearForwardOrAckIsNotSwallowed() = runBlocking {
        for (stage in listOf("clear", "forward", "ack")) {
            val f = Fixture()
            val cancelled = CancellationException(stage)
            when (stage) {
                "clear" -> f.cleared = { _, _ -> throw cancelled }
                "forward" -> f.forward = { _, _, _, _ -> throw cancelled }
                else -> f.ack = { _, _ -> throw cancelled }
            }
            var caught = false
            try { f.ingress.testNetwork(peer, "chat", id, packet, true) }
            catch (actual: CancellationException) { assertTrue(actual === cancelled); caught = true }
            assertTrue(caught)
            assertTrue(f.failures.isEmpty())
            assertTrue(f.acknowledgements.isEmpty())
        }
    }

    @Test fun forwardFailureDoesNotPreventAckForTheCommittedLocalRow() = runBlocking {
        val f = Fixture()
        f.forward = { _, _, _, _ -> throw IllegalStateException("mirror unavailable") }
        assertEquals(DirectReplyResult.APPLIED, f.ingress.testNetwork(peer, "chat", id, packet, true))
        assertEquals(listOf("forward"), f.failures)
        assertEquals(id to peer, f.acknowledgements.single())
        assertFalse(f.applied.isEmpty())
    }
}
