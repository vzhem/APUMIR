package com.vladimir.messenger.data.file

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileTransferReceiverTest {
    private val transferIdHex = "0123456789abcdef0123456789abcdef"
    private val transferIdBytes = ByteArray(16) { index ->
        // Must be the exact byte form of transferIdHex: the receiver derives the transfer ID
        // from packet bytes and matches it against the manifest hex in the database.
        transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    private val senderId = "pk_" + "ab".repeat(16)
    private val myNodeId = "pk_" + "cd".repeat(16)
    private val chatId = "chat-42"

    private lateinit var chunkRoot: File
    private lateinit var receivedRoot: File
    private lateinit var dao: FakeFileTransferDao
    private lateinit var chunkStore: FileTransferChunkStore
    private lateinit var receivedStore: ReceivedFileStore
    private lateinit var pinner: RecordingPinner
    private lateinit var vault: FakeTransferKeyVault
    private lateinit var notifier: RecordingNotifier
    private val acksReceived = mutableListOf<Pair<String, Int>>()
    private val transportSends = mutableListOf<String>()

    private val plaintext = ByteArray(2500) { (it % 253).toByte() }
    private val chunkSize = 1024

    private val transport = PacketTransport { _, _, _, text ->
        transportSends += text
        true
    }

    private fun crypto(gatewaySender: String = senderId, gatewayRecipient: String = myNodeId) =
        FakeFileCryptoGateway(
            senderNodeId = gatewaySender,
            recipientNodeId = gatewayRecipient,
            transferIdHex = transferIdHex,
            fileSha256Hex = sha256(plaintext),
            fileSizeBytes = plaintext.size.toLong(),
            chunkSizeBytes = chunkSize,
        )

    private fun receiver(gateway: FakeFileCryptoGateway = crypto()) = FileTransferReceiver(
        transferDao = dao,
        chunkStore = chunkStore,
        receivedStore = receivedStore,
        pinner = pinner,
        crypto = gateway,
        keyVault = vault,
        identity = FakeLocalExchangeIdentity(myNodeId),
        transport = transport,
        ackSink = { transferId, contiguous -> acksReceived += transferId to contiguous },
        notifier = notifier,
        nowMs = { 500_000L },
    )

    private fun offerTexts(): List<String> {
        val pdu = FileOfferPdu.encode(ByteArray(96) { 1 }, ByteArray(220) { 2 }, ByteArray(96) { 3 })
        return FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.OFFER,
            transferIdBytes,
            0,
            pdu,
        ).map(FileTransferWire::encodeEncodedPacket)
    }

    private fun chunkTexts(index: Int, bytes: ByteArray): List<String> =
        FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CHUNK,
            transferIdBytes,
            index,
            FakeFileCryptoGateway.fakeEncrypt(bytes),
        ).map(FileTransferWire::encodeEncodedPacket)

    private fun ackText(contiguous: Int): String =
        FileTransferWire.encodeEncodedPacket(
            FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.ACK,
                    transferIdBytes,
                    contiguous,
                    0,
                    1,
                    byteArrayOf(1),
                ),
            ),
        )

    private fun sentAckContiguous(): List<Int> = transportSends.mapNotNull { text ->
        runCatching {
            FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(text))
        }.getOrNull()
    }.filter { it.type == FileTransferPacketCodec.Type.ACK }.map { it.itemIndex }

    private suspend fun deliver(receiver: FileTransferReceiver, texts: List<String>, from: String = senderId) {
        texts.forEachIndexed { index, text ->
            assertTrue(receiver.onIncomingText(from, chatId, "wire-$index", text))
        }
    }

    @Before
    fun setUp() {
        chunkRoot = TestDirs.newDir("apu-rx-chunks-")
        receivedRoot = TestDirs.newDir("apu-rx-received-")
        dao = FakeFileTransferDao()
        chunkStore = FileTransferChunkStore(chunkRoot)
        receivedStore = ReceivedFileStore(receivedRoot)
        pinner = RecordingPinner()
        vault = FakeTransferKeyVault()
        notifier = RecordingNotifier()
    }

    @After
    fun tearDown() {
        chunkRoot.deleteRecursively()
        receivedRoot.deleteRecursively()
    }

    @Test
    fun fullReceiveFlowVerifiesAndWritesPlaintext() = runTest {
        val receiver = receiver()
        deliver(receiver, offerTexts())
        assertTrue(dao.getTransfer(transferIdHex)!!.state in setOf("OFFERED", "TRANSFERRING"))
        assertTrue(vault.keys.containsKey(transferIdHex))
        assertEquals(1, pinner.pinnedBindings.size)
        assertEquals(listOf(0), sentAckContiguous())

        val lengths = listOf(1024, 1024, 452)
        lengths.forEachIndexed { index, length ->
            val chunk = plaintext.copyOfRange(index * chunkSize, index * chunkSize + length)
            deliver(receiver, chunkTexts(index, chunk))
        }

        val transfer = dao.getTransfer(transferIdHex)!!
        assertEquals("COMPLETE", transfer.state)
        assertEquals(3, transfer.completedChunks)
        assertEquals(plaintext.size.toLong(), transfer.transferredBytes)

        val receivedFile = receivedStore.receivedFile(transferIdHex, "photo.png")
        assertNotNull(receivedFile)
        receivedFile!!.inputStream().use { input ->
            assertTrue(input.readBytes().contentEquals(plaintext))
        }
        assertEquals(1, notifier.events.size)
        assertTrue(notifier.events.single().contains("|photo.png|image/png|2500"))
        assertTrue(sentAckContiguous().contains(3))
    }

    @Test
    fun incomingAckPacketReachesTheSenderSink() = runTest {
        val receiver = receiver()
        assertTrue(receiver.onIncomingText(senderId, chatId, "ack-1", ackText(2)))
        assertEquals(listOf(transferIdHex to 2), acksReceived)
    }

    @Test
    fun duplicateAndReorderedChunksStayIdempotent() = runTest {
        val receiver = receiver()
        deliver(receiver, offerTexts())
        val chunkOne = plaintext.copyOfRange(chunkSize, 2 * chunkSize)
        deliver(receiver, chunkTexts(1, chunkOne))
        val acksAfterFirst = sentAckContiguous().size
        deliver(receiver, chunkTexts(1, chunkOne))

        val transfer = dao.getTransfer(transferIdHex)!!
        assertEquals(1, transfer.completedChunks)
        assertEquals(acksAfterFirst, sentAckContiguous().size)
    }

    @Test
    fun chunkBeforeOfferIsBufferedThenIngested() = runTest {
        val receiver = receiver()
        val firstChunk = plaintext.copyOfRange(0, chunkSize)
        deliver(receiver, chunkTexts(0, firstChunk))
        assertNull(dao.getTransfer(transferIdHex))

        deliver(receiver, offerTexts())
        val transfer = dao.getTransfer(transferIdHex)!!
        assertEquals(1, transfer.completedChunks)
        assertTrue(chunkStore.storedChunkIndices(transferIdHex).contains(0))
    }

    @Test
    fun offerForAnotherRecipientIsDropped() = runTest {
        val receiver = receiver(crypto(gatewayRecipient = "pk_" + "ee".repeat(16)))
        deliver(receiver, offerTexts())
        assertNull(dao.getTransfer(transferIdHex))
        assertTrue(pinner.pinnedBindings.isEmpty())
    }

    @Test
    fun offerSenderMismatchIsDropped() = runTest {
        val receiver = receiver()
        deliver(receiver, offerTexts(), from = "pk_" + "99".repeat(16))
        assertNull(dao.getTransfer(transferIdHex))
    }

    @Test
    fun changedPinnedKeyRejectsOffer() = runTest {
        val receiver = receiver()
        pinner.rejectNext = true
        deliver(receiver, offerTexts())
        assertNull(dao.getTransfer(transferIdHex))
    }

    @Test
    fun geometryMismatchedChunkIsDropped() = runTest {
        val receiver = receiver()
        deliver(receiver, offerTexts())
        deliver(receiver, chunkTexts(0, ByteArray(500) { 1 }))
        val transfer = dao.getTransfer(transferIdHex)!!
        assertEquals(0, transfer.completedChunks)
        assertTrue(chunkStore.storedChunkIndices(transferIdHex).isEmpty())
    }

    @Test
    fun corruptedWholeFileFailsClosedWithoutPlaintext() = runTest {
        val receiver = receiver()
        deliver(receiver, offerTexts())
        val lengths = listOf(1024, 1024, 452)
        lengths.forEachIndexed { index, length ->
            val bytes = if (index == 2) {
                ByteArray(length) { 7 }
            } else {
                plaintext.copyOfRange(index * chunkSize, index * chunkSize + length)
            }
            deliver(receiver, chunkTexts(index, bytes))
        }
        assertEquals("FAILED", dao.getTransfer(transferIdHex)!!.state)
        assertNull(receivedStore.receivedFile(transferIdHex, "photo.png"))
        assertTrue(notifier.events.isEmpty())
    }

    @Test
    fun plainChatTextPassesThroughAndMalformedPacketsAreConsumedButDropped() = runTest {
        val receiver = receiver()
        assertFalse(receiver.onIncomingText(senderId, chatId, "m1", "привет"))
        assertNull(dao.getTransfer(transferIdHex))
        // Wire-prefixed garbage is still a "file packet message": consumed, never chat text.
        assertTrue(receiver.onIncomingText(senderId, chatId, "m2", "apu-file1|not-base64!!"))
        assertNull(dao.getTransfer(transferIdHex))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
