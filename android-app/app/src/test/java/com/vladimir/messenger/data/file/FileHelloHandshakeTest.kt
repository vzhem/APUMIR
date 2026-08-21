package com.vladimir.messenger.data.file

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileHelloHandshakeTest {
    private val transferIdHex = "0123456789abcdef0123456789abcdef"
    private val senderId = "pk_" + "ab".repeat(16)
    private val myNodeId = "pk_" + "cd".repeat(16)
    private val binding = ByteArray(96) { 3 }

    private fun crypto() = FakeFileCryptoGateway(
        senderNodeId = senderId,
        recipientNodeId = myNodeId,
        transferIdHex = transferIdHex,
        fileSha256Hex = "00".repeat(32),
        fileSizeBytes = 0,
        chunkSizeBytes = 1024,
    )

    private fun receiver(pinner: RecordingPinner) = FileTransferReceiver(
        transferDao = FakeFileTransferDao(),
        chunkStore = FileTransferChunkStore(TestDirs.newDir("apu-hello-chunks-")),
        receivedStore = ReceivedFileStore(TestDirs.newDir("apu-hello-received-")),
        pinner = pinner,
        crypto = crypto(),
        keyVault = FakeTransferKeyVault(),
        identity = FakeLocalExchangeIdentity(myNodeId),
        transport = PacketTransport { _, _, _, _ -> true },
        ackSink = { _, _ -> },
        notifier = RecordingNotifier(),
    )

    @Test
    fun helloWireRoundTripAndBounds() {
        val text = FileTransferWire.encodeHelloBinding(binding)
        assertTrue(FileTransferWire.isHelloText(text))
        assertFalse(FileTransferWire.isFilePacketText(text))
        assertArrayEquals(binding, FileTransferWire.decodeHelloBinding(text))
        try {
            FileTransferWire.encodeHelloBinding(ByteArray(FileTransferWire.MAX_HELLO_BINDING_BYTES + 1))
            fail("Oversized hello binding accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun helloMessageIdIsDeterministicPerDirectionAndDelimiterSafe() {
        val first = FileTransferWire.helloMessageId(myNodeId, senderId)
        assertEquals(first, FileTransferWire.helloMessageId(myNodeId, senderId))
        assertFalse(first == FileTransferWire.helloMessageId(senderId, myNodeId))
        assertTrue(first.length <= FileTransferWire.MAX_MESSAGE_ID_BYTES)
        assertFalse(first.contains('|'))
    }

    @Test
    fun firstHelloPinsNewAndRepeatIsIdempotent() = runTest {
        val pinner = RecordingPinner()
        val receiver = receiver(pinner)
        val text = FileTransferWire.encodeHelloBinding(binding)
        assertEquals(FileTransferReceiver.HelloResult.PINNED_NEW, receiver.onHelloText(senderId, text))
        assertEquals(FileTransferReceiver.HelloResult.PINNED_ALREADY, receiver.onHelloText(senderId, text))
        assertEquals(1, pinner.pinnedBindings.size)
    }

    @Test
    fun helloFromMismatchedSenderIsRejected() = runTest {
        val pinner = RecordingPinner()
        val receiver = receiver(pinner)
        val other = "pk_" + "99".repeat(16)
        assertEquals(
            FileTransferReceiver.HelloResult.REJECTED,
            receiver.onHelloText(other, FileTransferWire.encodeHelloBinding(binding)),
        )
        assertTrue(pinner.pinnedBindings.isEmpty())
    }

    @Test
    fun helloWithChangedPinnedKeyIsRejected() = runTest {
        val pinner = RecordingPinner()
        val receiver = receiver(pinner)
        assertEquals(
            FileTransferReceiver.HelloResult.PINNED_NEW,
            receiver.onHelloText(senderId, FileTransferWire.encodeHelloBinding(binding)),
        )
        pinner.rejectNext = true
        assertEquals(
            FileTransferReceiver.HelloResult.REJECTED,
            receiver.onHelloText(senderId, FileTransferWire.encodeHelloBinding(ByteArray(96) { 9 })),
        )
    }

    @Test
    fun plainTextIsNotHello() = runTest {
        val receiver = receiver(RecordingPinner())
        assertEquals(FileTransferReceiver.HelloResult.NOT_HELLO, receiver.onHelloText(senderId, "привет"))
    }

    @Test
    fun helloIsConsumedByRouteBeforeChatTextSemantics() = runTest {
        val pinner = RecordingPinner()
        val receiver = receiver(pinner)
        // The router contract: hello texts are consumed (never stored as chat text).
        assertTrue(receiver.onIncomingText(senderId, "chat", "m1", FileTransferWire.encodeHelloBinding(binding)))
        assertEquals(1, pinner.pinnedBindings.size)
    }
}
