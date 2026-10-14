package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.update.ApkUpdate
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The APK seed must never turn a missing direct path into queued megabytes. */
class GroupFileSeederTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val requester = "pk_" + "ab".repeat(16)
    private lateinit var root: java.io.File
    private lateinit var store: FileTransferChunkStore
    private lateinit var dao: FakeFileTransferDao
    private var directCalls = 0
    private var fallbackCalls = 0
    private var directOk = false

    private fun row(chatId: String) = FileTransferEntity(
        transferId = id,
        messageId = "m-$id",
        chatId = chatId,
        peerNodeId = requester,
        direction = "OUTGOING",
        displayName = "update.apk",
        mediaType = "application/vnd.android.package-archive",
        totalBytes = 1024L,
        chunkSize = 1024,
        chunkCount = 1L,
        fileSha256 = "ab".repeat(32),
        state = "SEEDING",
        createdAtMs = 1_000L,
        expiresAtMs = 20_000_000L,
        updatedAtMs = 1_000L,
    )

    private fun seeder() = GroupFileSeeder(
        transferDao = dao,
        chunkStore = store,
        directTransport = { _, _ -> fallbackCalls++; true },
        apkDirectTransport = { _, _ -> directCalls++; directOk },
        ownBindingProvider = { ByteArray(96) },
        wrapKey = { _, _ -> ByteArray(220) },
        nowMs = { 10_000_000L },
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("apu-seed-transport-").toFile()
        store = FileTransferChunkStore(root)
        dao = FakeFileTransferDao()
        directCalls = 0
        fallbackCalls = 0
        directOk = false
        // Size-valid group manifest; only its routing tag matters to this test.
        val manifest = ByteArray(96)
        val domain = "apu-file-manifest-v1\u0000".toByteArray(Charsets.US_ASCII)
        domain.copyInto(manifest)
        var offset = domain.size + 1 + 16
        manifest[offset + 1] = 4 // sender length
        offset += 2
        "pk_a".toByteArray(Charsets.US_ASCII).copyInto(manifest, offset)
        offset += 4
        val recipient = "grp_apkseed".toByteArray(Charsets.US_ASCII)
        manifest[offset + 1] = recipient.size.toByte()
        recipient.copyInto(manifest, offset + 2)
        store.storeManifest(id, manifest)
        store.storeEncryptedChunk(id, 0L, ByteArray(80) { 1 })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun apkOfferWithoutDirectChannelDoesNotEnqueueBrokerPackets() = runTest {
        val seed = seeder()
        assertEquals(GroupFileSeeder.OfferResult.UNREACHABLE, seed.offer(row(ApkUpdate.CHAT_ID), requester))
        assertEquals(1, directCalls)
        assertEquals(0, fallbackCalls)
        assertEquals(0, seed.activeLegs())
    }

    @Test
    fun apkChunkAfterAckStillNeverEnqueuesBrokerPackets() = runTest {
        val seedRow = row(ApkUpdate.CHAT_ID)
        dao.insertTransferIgnore(seedRow)
        val seed = seeder()
        directOk = true
        assertEquals(GroupFileSeeder.OfferResult.SENT, seed.offer(seedRow, requester))
        directOk = false
        seed.onAck(id, requester, 0L)
        assertTrue(directCalls >= 2)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun ordinaryGroupFileKeepsItsPreviousBrokerFallback() = runTest {
        val seed = seeder()
        assertEquals(GroupFileSeeder.OfferResult.SENT, seed.offer(row("group-chat"), requester))
        assertEquals(0, directCalls)
        assertEquals(1, fallbackCalls)
    }
}
