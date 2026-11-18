package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision order of the single authenticated exception to the push-control
 * quarantine. No emulator, Keystore or Rust library: every store is a recording
 * stand-in, so what is asserted is *which* check decided the outcome and in what
 * order — the property the real receive path depends on.
 */
class PushEpochImportTest {
    private val peerNode = "pk_" + "1".repeat(64)
    private val localNode = "pk_" + "2".repeat(64)
    private val peerBinding = byteArrayOf(1, 3, 5, 7)
    private val localBinding = byteArrayOf(2, 4, 6, 8)

    private fun bindingHash(bytes: ByteArray): String = PushEpochProtocol.bindingHash(bytes)

    /** A structurally valid sealed wire that carries [sender] in its header. */
    private fun wire(sender: ByteArray): String {
        val envelope = ByteArray(sender.size + 203) { 6 }
        envelope[0] = 1
        envelope[1] = (sender.size ushr 8).toByte()
        envelope[2] = sender.size.toByte()
        sender.copyInto(envelope, 3)
        return SealedWire.encode(envelope, ByteArray(12) { 3 }, ByteArray(16) { 4 })
    }

    private fun offer(generation: Long = 1) = PushEpochProtocol.encodeOffer(
        generation, bindingHash(peerBinding), bindingHash(localBinding), "ab".repeat(16),
    )!!

    private class Recorder {
        val calls = mutableListOf<String>()
    }

    private fun importer(
        recorder: Recorder = Recorder(),
        plaintext: String? = offer(),
        storedPin: ByteArray? = peerBinding.copyOf(),
        storedLocal: ByteArray? = localBinding.copyOf(),
        nodeId: String = localNode,
        accept: () -> PushEpochStore.WriteResult = { PushEpochStore.WriteResult.Applied },
        open: (String) -> String? = { plaintext },
    ) = PushEpochImport(
        localNodeId = { recorder.calls += "local"; nodeId },
        pinnedPeer = { peer -> recorder.calls += "pin"; storedPin?.copyOf() },
        localBinding = { recorder.calls += "binding"; storedLocal?.copyOf() },
        open = { wireText -> recorder.calls += "open"; open(wireText) },
        accept = { localId, peerId, packet, pinned, local ->
            recorder.calls += "accept:$localId:$peerId:${packet.generation}:${pinned.contentEquals(peerBinding)}"
            accept()
        },
        nodeIdOfBinding = { binding ->
            recorder.calls += "node"
            when {
                binding.contentEquals(peerBinding) -> peerNode
                binding.contentEquals(localBinding) -> localNode
                else -> "pk_" + "f".repeat(64)
            }
        },
    )

    @Test fun aPlaintextPacketNeverBecomesAnEpoch() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.NOT_APPLICABLE,
            importer(recorder).import(peerNode, offer()),
        )
        // Nothing was read at all: an unsealed text is quarantine, not a decision.
        assertEquals(emptyList<String>(), recorder.calls)
    }

    @Test fun anEnvelopeFromSomeoneElseThanTheClaimedSenderIsNeverOpened() = runBlocking {
        val recorder = Recorder()
        // The envelope names a different key than the sender we were told about.
        assertEquals(
            PushEpochImport.Outcome.NOT_APPLICABLE,
            importer(recorder).import(peerNode, wire(localBinding)),
        )
        assertEquals(listOf("node"), recorder.calls)
    }

    @Test fun aPeerWhoseBindingIsNotPinnedNeverReachesTheJournal() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.NOT_APPLICABLE,
            importer(recorder, storedPin = null).import(peerNode, wire(peerBinding)),
        )
        assertTrue("accept" !in recorder.calls.toString())
        assertEquals(listOf("node", "local", "pin"), recorder.calls)
    }

    @Test fun aMissingLocalProfileNeverReachesTheJournal() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.NOT_APPLICABLE,
            importer(recorder, storedLocal = null).import(peerNode, wire(peerBinding)),
        )
        assertTrue("accept" !in recorder.calls.toString())
        assertEquals(listOf("node", "local", "pin", "binding"), recorder.calls)
    }

    @Test fun aKeptWireWhoseOpenedTextIsNotAnEpochPacketStaysQuarantined() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.NOT_APPLICABLE,
            importer(recorder, plaintext = "привет").import(peerNode, wire(peerBinding)),
        )
        assertTrue("accept" !in recorder.calls.toString())
        assertEquals(listOf("node", "local", "pin", "binding", "open"), recorder.calls)
    }

    @Test fun aChangedPinBetweenEnvelopeAndJournalIsRejectedNotApplied() = runBlocking {
        val recorder = Recorder()
        // Header still names the old key, the live pin is a different one.
        val rotated = byteArrayOf(9, 9, 9)
        assertEquals(
            PushEpochImport.Outcome.REJECTED,
            importer(recorder, storedPin = rotated, storedLocal = localBinding).import(peerNode, wire(peerBinding)),
        )
        assertTrue("accept" !in recorder.calls.toString())
        assertArrayEquals(byteArrayOf(9, 9, 9), rotated)
    }

    @Test fun theJournalWriteSeesTheLivePinAndTheExactPair() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.APPLIED,
            importer(recorder).import(peerNode, wire(peerBinding)),
        )
        assertEquals("accept:$localNode:$peerNode:1:true", recorder.calls.last())
    }

    @Test fun aDuplicateGenerationIsIdempotentAndNotAFailure() = runBlocking {
        val recorder = Recorder()
        assertEquals(
            PushEpochImport.Outcome.DUPLICATE,
            importer(recorder, accept = { PushEpochStore.WriteResult.Duplicate }).import(peerNode, wire(peerBinding)),
        )
        assertEquals("accept:$localNode:$peerNode:1:true", recorder.calls.last())
    }

    @Test fun aFullOrUnreadableJournalIsRetryableNotApplied() = runBlocking {
        for (result in listOf(PushEpochStore.WriteResult.Full, PushEpochStore.WriteResult.Unavailable)) {
            assertEquals(
                PushEpochImport.Outcome.UNAVAILABLE,
                importer(accept = { result }).import(peerNode, wire(peerBinding)),
            )
        }
    }

    @Test fun aJournalRejectionIsNotReportedAsAppliedOrUnavailable() = runBlocking {
        assertEquals(
            PushEpochImport.Outcome.REJECTED,
            importer(accept = { PushEpochStore.WriteResult.Rejected }).import(peerNode, wire(peerBinding)),
        )
    }

    @Test fun anUnopenableEnvelopeIsNotAnOutage() = runBlocking {
        var failures = 0
        val importer = PushEpochImport(
            localNodeId = { localNode },
            pinnedPeer = { peerBinding.copyOf() },
            localBinding = { localBinding.copyOf() },
            open = { null },
            accept = { _, _, _, _, _ ->
                failures++
                PushEpochStore.WriteResult.Applied
            },
            nodeIdOfBinding = { peerNode },
            recordFailure = { failures++ },
        )
        assertEquals(PushEpochImport.Outcome.NOT_APPLICABLE, importer.import(peerNode, wire(peerBinding)))
        assertEquals(0, failures)
    }

    @Test fun aBrokenReaderFailsClosedWithoutReachingTheJournal() = runBlocking {
        val recorder = Recorder()
        val importer = PushEpochImport(
            localNodeId = { localNode },
            pinnedPeer = { throw IllegalStateException("pin store") },
            localBinding = { localBinding },
            open = { offer() },
            accept = { _, _, _, _, _ ->
                recorder.calls += "accept"
                PushEpochStore.WriteResult.Applied
            },
            nodeIdOfBinding = { peerNode },
            recordFailure = { recorder.calls += "reported" },
        )
        assertEquals(PushEpochImport.Outcome.NOT_APPLICABLE, importer.import(peerNode, wire(peerBinding)))
        assertEquals(listOf("reported"), recorder.calls)
    }

    @Test fun anEnvelopeWhoseHeaderIsUnreadableIsNeverTreatedAsThePinnedSender() = runBlocking {
        // The key envelope must be structurally valid; a truncated header is not a
        // claim about identity and must not fall back to any stored pin.
        val broken = SealedWire.encode(ByteArray(7) { 6 }, ByteArray(12) { 3 }, ByteArray(16) { 4 })
        val recorder = Recorder()
        assertEquals(PushEpochImport.Outcome.NOT_APPLICABLE, importer(recorder).import(peerNode, broken))
        assertEquals(emptyList<String>(), recorder.calls)
    }

    @Test fun theImportedPacketIsBoundToThePairNotToTheJournalState() = runBlocking {
        // A packet addressed to another local binding must not be accepted as-is;
        // the journal decides, and the importer passes the live pin, not the packet.
        val foreign = PushEpochProtocol.encodeOffer(
            1, bindingHash(peerBinding), bindingHash(byteArrayOf(9, 9)), "cd".repeat(16),
        )!!
        val seen = mutableListOf<ByteArray>()
        val importer = PushEpochImport(
            localNodeId = { localNode },
            pinnedPeer = { peerBinding.copyOf() },
            localBinding = { localBinding.copyOf() },
            open = { foreign },
            accept = { _, _, _, pinned, _ ->
                seen += pinned.copyOf()
                PushEpochStore.WriteResult.Rejected
            },
            nodeIdOfBinding = { peerNode },
        )
        assertEquals(PushEpochImport.Outcome.REJECTED, importer.import(peerNode, wire(peerBinding)))
        assertEquals(1, seen.size)
        assertArrayEquals(peerBinding, seen.single())
        assertTrue(MessageDigest.isEqual(peerBinding, seen.single()))
    }
}
