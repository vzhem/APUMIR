package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileTransferSenderTest {
    private lateinit var chunkRoot: java.io.File
    private lateinit var chunkStore: FileTransferChunkStore
    private lateinit var dao: FakeFileTransferDao
    private val transportSends = mutableListOf<Pair<String, String>>() // messageId to recipient
    private val transferIdHex = "0123456789abcdef0123456789abcdef"
    private var now = 1_000_000L

    private val transport = PacketTransport { messageId, _, recipientNodeId, _ ->
        transportSends += messageId to recipientNodeId
        true
    }

    private val binding = ByteArray(96) { 7 }

    // K3: бинарный канал в тестах — запись вызовов, доставка «по требованию».
    data class BinaryCall(
        val peer: String,
        val transferId: String,
        val chunkIndex: Long,
        val offset: Int,
        val chunkLen: Int,
        val range: ByteArray,
    )
    private val binarySends = mutableListOf<BinaryCall>()
    private var binaryDeliver = true
    private val binaryTransport: (String, String, Long, Int, Int, ByteArray) -> Boolean =
        { peer, transferId, chunkIndex, offset, chunkLen, range ->
            binarySends += BinaryCall(peer, transferId, chunkIndex, offset, chunkLen, range.copyOf())
            binaryDeliver
        }

    private fun sender(withBinary: Boolean = false) = FileTransferSender(
        transferDao = dao,
        chunkStore = chunkStore,
        transport = transport,
        ownBindingProvider = { binding.copyOf() },
        nowMs = { now },
        binaryTransport = if (withBinary) binaryTransport else null,
    )

    @Before
    fun setUp() {
        chunkRoot = Files.createTempDirectory("apu-sender-chunks-").toFile()
        chunkStore = FileTransferChunkStore(chunkRoot)
        dao = FakeFileTransferDao()
    }

    @After
    fun tearDown() {
        chunkRoot.deleteRecursively()
    }

    private fun entity(chunkCount: Int, chunkSize: Int, totalBytes: Long) = FileTransferEntity(
        transferId = transferIdHex,
        messageId = "m-$transferIdHex",
        chatId = "chat-1",
        peerNodeId = "pk_" + "22".repeat(16),
        direction = "OUTGOING",
        displayName = "photo.png",
        mediaType = "image/png",
        totalBytes = totalBytes,
        chunkSize = chunkSize,
        chunkCount = chunkCount.toLong(),
        fileSha256 = "ab".repeat(32),
        state = "PREPARED",
        completedChunks = chunkCount.toLong(),
        transferredBytes = totalBytes,
        createdAtMs = now - 1000,
        // Long enough that virtual-time jumps in the throttle test never expire the transfer.
        expiresAtMs = now + 3_600_000,
        updatedAtMs = now - 1000,
    )

    private suspend fun stage(chunkCount: Int, chunkSize: Int, lastChunkBytes: Int) {
        chunkStore.storeManifest(transferIdHex, ByteArray(96))
        chunkStore.storeKeyEnvelope(transferIdHex, ByteArray(220))
        for (index in 0 until chunkCount) {
            val length = if (index == chunkCount - 1) lastChunkBytes else chunkSize
            chunkStore.storeEncryptedChunk(
                transferIdHex,
                index.toLong(),
                ByteArray(length + 16) { index.toByte() },
            )
        }
    }

    @Test
    fun firstPumpSendsOfferAndAllWindowChunksThenSent() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 3, chunkSize = 1024, totalBytes = 2500))
        stage(chunkCount = 3, chunkSize = 1024, lastChunkBytes = 452)

        val summary = sender().pumpOnce()

        assertEquals(1, summary.transfersPumped)
        // offer (1 fragment) + 3 single-fragment chunks
        assertEquals(4, summary.packetsSent)
        assertTrue(transportSends.any { it.first == FileTransferWire.offerMessageId(transferIdHex, 0) })
        assertTrue(transportSends.any { it.first == FileTransferWire.chunkMessageId(transferIdHex, 2, 0) })
        assertEquals("SENT", dao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun windowIsBoundedForLargeFragmentsUntilAckAdvances() = runTest {
        // Window derives from the live fragment size: ceil((chunk+tag)/frag) fragments per chunk,
        // then MAX_INFLIGHT_MESSAGES/fragmentsPerChunk chunks per window.
        val fragmentsPerChunk = (
            (256 * 1024 + FileTransferChunkStore.AEAD_TAG_BYTES +
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES
            )
        val windowChunks = maxOf(1, FileTransferSender.MAX_INFLIGHT_MESSAGES / fragmentsPerChunk)
        dao.insertNewTransfer(entity(chunkCount = 12, chunkSize = 256 * 1024, totalBytes = 12L * 256 * 1024))
        stage(chunkCount = 12, chunkSize = 256 * 1024, lastChunkBytes = 256 * 1024)

        val sender = sender()
        sender.pumpOnce()

        val chunkPackets = transportSends.count { Regex("c\\d+f\\d+$").containsMatchIn(it.first) }
        assertEquals(windowChunks * fragmentsPerChunk, chunkPackets)
        assertEquals("TRANSFERRING", dao.getTransfer(transferIdHex)!!.state)

        sender.onReceiverAck(transferIdHex, windowChunks.toLong())
        now += 1000
        transportSends.clear()
        sender.pumpOnce()
        // Окно сдвинулось ровно на свой размер: снова windowChunks чанков в полёте.
        assertEquals(windowChunks * fragmentsPerChunk, transportSends.count { Regex("c\\d+f\\d+$").containsMatchIn(it.first) })
        assertEquals("TRANSFERRING", dao.getTransfer(transferIdHex)!!.state)

        // Подтверждаем весь файл: передача закрывается как COMPLETE.
        sender.onReceiverAck(transferIdHex, 12)
        transportSends.clear()
        sender.pumpOnce()
        assertEquals(0, transportSends.count { Regex("c\\d+f\\d+$").containsMatchIn(it.first) })
        assertEquals("COMPLETE", dao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun receiverFinalAckCompletesTransfer() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 1, chunkSize = 10, totalBytes = 10))
        stage(chunkCount = 1, chunkSize = 10, lastChunkBytes = 10)
        val sender = sender()
        sender.pumpOnce()
        sender.onReceiverAck(transferIdHex, 1)
        assertEquals("COMPLETE", dao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun outOfRangeAckCannotCompleteOrSkipChunks() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 64, totalBytes = 128))
        stage(chunkCount = 2, chunkSize = 64, lastChunkBytes = 64)
        val sender = sender()
        sender.onReceiverAck(transferIdHex, 3)
        assertEquals("PREPARED", dao.getTransfer(transferIdHex)!!.state)
        val summary = sender.pumpOnce()
        assertTrue(summary.packetsSent > 1)
    }

    @Test
    fun cancelAllOutgoingStopsEveryActiveSendAndKeepsOthers() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 64, totalBytes = 128))
        dao.insertNewTransfer(
            entity(chunkCount = 1, chunkSize = 10, totalBytes = 10).copy(
                transferId = "ffffffffffffffffffffffffffffffff",
                messageId = "m-incoming",
                state = "COMPLETE",
                direction = "INCOMING",
            )
        )
        val cancelled = dao.cancelAllOutgoing(now)
        assertEquals(1, cancelled)
        assertEquals("CANCELLED", dao.getTransfer(transferIdHex)!!.state)
        assertEquals(0, dao.getActiveOutgoing(now).size)
        assertEquals(1, dao.getCompleted().size)
    }

    @Test
    fun emptyFileSendsOfferOnlyAndCompletesOnZeroAck() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 0, chunkSize = 1024, totalBytes = 0))
        chunkStore.storeManifest(transferIdHex, ByteArray(96))
        chunkStore.storeKeyEnvelope(transferIdHex, ByteArray(220))
        val sender = sender()
        val summary = sender.pumpOnce()
        assertEquals(1, summary.packetsSent)
        assertEquals("SENT", dao.getTransfer(transferIdHex)!!.state)
        sender.onReceiverAck(transferIdHex, 0)
        assertEquals("COMPLETE", dao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun immediateRepumpWithoutAckProgressIsThrottled() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 64, totalBytes = 128))
        stage(chunkCount = 2, chunkSize = 64, lastChunkBytes = 64)
        val sender = sender()
        sender.pumpOnce()
        transportSends.clear()

        val summary = sender.pumpOnce()
        assertEquals(0, summary.transfersPumped)
        assertEquals(0, transportSends.size)

        now += FileTransferSender.REPUMP_INTERVAL_MS + 1
        val resumed = sender.pumpOnce()
        assertEquals(1, resumed.transfersPumped)
    }

    @Test
    fun missingChunkFailsThisTransferOnlyAndStaysResumable() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 64, totalBytes = 128))
        chunkStore.storeManifest(transferIdHex, ByteArray(96))
        chunkStore.storeKeyEnvelope(transferIdHex, ByteArray(220))
        chunkStore.storeEncryptedChunk(transferIdHex, 0, ByteArray(64 + 16))

        val summary = sender().pumpOnce()

        assertEquals(0, summary.transfersPumped)
        assertEquals(1, summary.failures)
        assertEquals("PREPARED", dao.getTransfer(transferIdHex)!!.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    // K3: бинарные APUF-кадры (FCAP + прямой QUIC)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun markedTransferSendsBinaryFramesNotText() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 1024, totalBytes = 2100))
        stage(chunkCount = 2, chunkSize = 1024, lastChunkBytes = 1076 - 16)

        val s = sender(withBinary = true)
        s.markBinaryCapable(transferIdHex, FileTransferWire.BINARY_MAX_FRAME_PAYLOAD)
        s.pumpOnce()

        // Оффер — по надёжному текстовому пути (как всегда), куски — бинарные.
        assertTrue(transportSends.any { it.first == FileTransferWire.offerMessageId(transferIdHex, 0) })
        assertTrue(transportSends.none { Regex("c\\d+f\\d+$").containsMatchIn(it.first) })
        assertEquals(2, binarySends.size)

        val full0 = chunkStore.readEncryptedChunk(transferIdHex, 0)!!
        val full1 = chunkStore.readEncryptedChunk(transferIdHex, 1)!!
        assertEquals(listOf(0L, 1L), binarySends.map { it.chunkIndex })
        assertEquals(0, binarySends[0].offset)
        assertEquals(full0.size, binarySends[0].chunkLen)
        assertArrayEquals(full0, binarySends[0].range)
        assertArrayEquals(full1, binarySends[1].range)
        assertEquals("SENT", dao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun bigChunkSplitsIntoBoundedApuFrames() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 1, chunkSize = 600_000, totalBytes = 600_000))
        stage(chunkCount = 1, chunkSize = 600_000, lastChunkBytes = 600_000)

        val s = sender(withBinary = true)
        s.markBinaryCapable(transferIdHex, FileTransferWire.BINARY_MAX_FRAME_PAYLOAD)
        s.pumpOnce()

        val maxRange = FileTransferWire.BINARY_MAX_FRAME_PAYLOAD - FileTransferWire.BINARY_CHUNK_PREFIX_BYTES
        val full = chunkStore.readEncryptedChunk(transferIdHex, 0)!!
        assertEquals((full.size + maxRange - 1) / maxRange, binarySends.size)
        var offset = 0
        val rebuilt = ArrayList<Byte>(full.size)
        for (call in binarySends) {
            assertEquals(offset, call.offset)
            assertEquals(full.size, call.chunkLen)
            assertEquals(minOf(maxRange, full.size - offset), call.range.size)
            rebuilt.addAll(call.range.toList())
            offset += call.range.size
        }
        assertEquals(full.size, offset)
        assertArrayEquals(full, rebuilt.toByteArray())
    }

    @Test
    fun binaryTransferWithoutFcapStaysOnTextPath() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 1, chunkSize = 1024, totalBytes = 1024))
        stage(chunkCount = 1, chunkSize = 1024, lastChunkBytes = 1024)

        // Бинарный канал есть, но FCAP не было — куски текстовыми фрагментами.
        sender(withBinary = true).pumpOnce()

        assertEquals(0, binarySends.size)
        assertTrue(transportSends.any { it.first == FileTransferWire.chunkMessageId(transferIdHex, 0, 0) })
    }

    @Test
    fun binaryFailurePausesTransferLikeOfflineRecipient() = runTest {
        dao.insertNewTransfer(entity(chunkCount = 2, chunkSize = 1024, totalBytes = 2048))
        stage(chunkCount = 2, chunkSize = 1024, lastChunkBytes = 1024)
        binaryDeliver = false

        sender(withBinary = true).run {
            markBinaryCapable(transferIdHex, FileTransferWire.BINARY_MAX_FRAME_PAYLOAD)
            pumpOnce()
        }

        assertEquals("WAITING_RECIPIENT", dao.getTransfer(transferIdHex)!!.state)
        // Оффер ушёл (передачу принять есть что), текстовых кусков не было.
        assertTrue(transportSends.any { it.first == FileTransferWire.offerMessageId(transferIdHex, 0) })
        assertTrue(transportSends.none { Regex("c\\d+f\\d+$").containsMatchIn(it.first) })
        assertTrue(binarySends.isNotEmpty())
    }
}
