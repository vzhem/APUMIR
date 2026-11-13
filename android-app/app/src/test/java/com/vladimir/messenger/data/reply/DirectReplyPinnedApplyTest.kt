package com.vladimir.messenger.data.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReplyPinnedApplyTest {
    private val peer = "pk_" + "a".repeat(32)
    private val text = DirectReplyWire.build("parent", "target", "Alice", "quoted")
    private val id = DirectReplyWire.transportMessageId("parent")
    private val legitimate = byteArrayOf(1, 2, 3)
    private val differentSigner = byteArrayOf(1, 2, 4)

    @Test fun matchingExistingPinIsRequiredBeforeApplyForwardOrAck() = runBlocking {
        var pin: ByteArray? = null
        var writes = 0
        var forwards = 0
        var acks = 0
        val pinned = DirectReplyPinnedApply(
            bindingFromWire = { wire -> when (wire) { "legitimate" -> legitimate; "forged" -> differentSigner; else -> null } },
            withPin = { _, action -> action(pin) },
            apply = { _, _ -> writes++; true },
        )
        val receiver = DirectReplyIngress(
            { _, _ -> false }, { _, _ -> error("network must use pinned apply") }, pinned::apply,
            { _, _, _, _ -> forwards++ }, { _, _ -> acks++ }, { _, _ -> error("unexpected failure") },
        )
        assertEquals(DirectReplyResult.REJECTED, receiver.network(peer, "chat", id, text, true, "legitimate"))
        pin = legitimate
        // A distinct binding may claim the same sender ID; identity verification
        // before this gate is not a substitute for matching the durable pin.
        assertEquals(DirectReplyResult.REJECTED, receiver.network(peer, "chat", id, text, true, "forged"))
        assertEquals(DirectReplyResult.REJECTED, receiver.network(peer, "chat", id, text, true, "missing"))
        assertEquals(0, writes)
        assertEquals(0, forwards)
        assertEquals(0, acks)
        assertEquals(DirectReplyResult.APPLIED, receiver.network(peer, "chat", id, text, true, "legitimate"))
        assertEquals(1, writes)
        assertEquals(1, forwards)
        assertEquals(1, acks)
    }

    @Test fun pinRemovalWaitsForTheRowUpdate() = runBlocking {
        val gate = Mutex()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var pin: ByteArray? = legitimate
        var acks = 0
        val pinned = DirectReplyPinnedApply(
            bindingFromWire = { legitimate },
            withPin = { _, action -> gate.withLock { action(pin) } },
            apply = { _, _ -> entered.complete(Unit); finish.await(); true },
        )
        val receiver = DirectReplyIngress({ _, _ -> false }, { _, _ -> false }, pinned::apply,
            { _, _, _, _ -> }, { _, _ -> acks++ }, { _, _ -> error("unexpected failure") })
        val receiving = async { receiver.network(peer, "chat", id, text, true, "wire") }
        entered.await()
        val removing = async(start = CoroutineStart.UNDISPATCHED) { gate.withLock { pin = null } }
        assertFalse(removing.isCompleted)
        assertEquals(0, acks)
        finish.complete(Unit)
        assertEquals(DirectReplyResult.APPLIED, receiving.await())
        removing.await()
        assertEquals(1, acks)
        assertEquals(DirectReplyResult.REJECTED, receiver.network(peer, "chat", id, text, true, "wire"))
    }

    @Test fun networkEffectsAreOutsideThePinGateAndCancellationWhileWaitingDoesNotAck() = runBlocking {
        val gate = Mutex()
        var writes = 0
        var acks = 0
        var unlockedDuringForward = false
        var unlockedDuringAck = false
        val pinned = DirectReplyPinnedApply(
            bindingFromWire = { legitimate },
            withPin = { _, action -> gate.withLock { action(legitimate) } },
            apply = { _, _ -> writes++; true },
        )
        val receiver = DirectReplyIngress({ _, _ -> false }, { _, _ -> false }, pinned::apply,
            { _, _, _, _ -> unlockedDuringForward = !gate.isLocked },
            { _, _ -> unlockedDuringAck = !gate.isLocked; acks++ }, { _, _ -> error("unexpected failure") })
        gate.lock()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { receiver.network(peer, "chat", id, text, true, "wire") }
        waiting.cancelAndJoin()
        gate.unlock()
        assertTrue(waiting.isCancelled)
        assertEquals(0, writes)
        assertEquals(0, acks)
        assertEquals(DirectReplyResult.APPLIED, receiver.network(peer, "chat", id, text, true, "wire"))
        assertTrue(unlockedDuringForward)
        assertTrue(unlockedDuringAck)
        assertEquals(1, writes)
        assertEquals(1, acks)
    }
}
