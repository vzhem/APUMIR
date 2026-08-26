package com.vladimir.messenger.data.file

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileTransferChunkStoreTest {
    private lateinit var root: java.io.File
    private val transferId = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("apu-file-chunks-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun encryptedChunkRoundTripIsIdempotentAndDefensiveCopy() {
        val store = FileTransferChunkStore(root)
        val ciphertext = ByteArray(128 + FileTransferChunkStore.AEAD_TAG_BYTES) { it.toByte() }
        val first = store.storeEncryptedChunk(transferId, 0, ciphertext)
        assertTrue(first.newlyStored)
        assertEquals(ciphertext.size.toLong(), first.ciphertextBytes)

        ciphertext.fill(0)
        val read = store.readEncryptedChunk(transferId, 0)!!
        assertFalse(read.all { it == 0.toByte() })
        val retry = store.storeEncryptedChunk(transferId, 0, read)
        assertFalse(retry.newlyStored)
        assertEquals(listOf(0L), store.storedChunkIndices(transferId))
        assertArrayEquals(read, store.readEncryptedChunk(transferId, 0))
    }

    @Test
    fun manifestIsAtomicIdempotentAndConflictProtected() {
        val store = FileTransferChunkStore(root)
        val manifest = ByteArray(128) { it.toByte() }
        assertTrue(store.storeManifest(transferId, manifest))
        manifest.fill(0)
        val stored = store.readManifest(transferId)!!
        assertFalse(stored.all { it == 0.toByte() })
        assertFalse(store.storeManifest(transferId, stored))
        val different = stored.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectFailure { store.storeManifest(transferId, different) }
        assertArrayEquals(stored, store.readManifest(transferId))
        expectFailure { store.storeManifest("f".repeat(32), ByteArray(63)) }
        expectFailure { store.storeManifest("f".repeat(32), ByteArray(2049)) }
    }

    @Test
    fun keyEnvelopeIsAtomicIdempotentAndConflictProtected() {
        val store = FileTransferChunkStore(root)
        val envelope = ByteArray(256) { (it % 251).toByte() }
        assertTrue(store.storeKeyEnvelope(transferId, envelope))
        assertFalse(store.storeKeyEnvelope(transferId, envelope.copyOf()))
        assertArrayEquals(envelope, store.readKeyEnvelope(transferId))
        val changed = envelope.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectFailure { store.storeKeyEnvelope(transferId, changed) }
    }

    @Test
    fun sameIndexWithDifferentCiphertextIsRejectedWithoutOverwrite() {
        val store = FileTransferChunkStore(root)
        val first = ByteArray(32) { 1 }
        store.storeEncryptedChunk(transferId, 7, first)
        expectFailure {
            store.storeEncryptedChunk(transferId, 7, ByteArray(32) { 2 })
        }
        assertArrayEquals(first, store.readEncryptedChunk(transferId, 7))
    }

    @Test
    fun quotaAndSizeBoundsFailBeforePartialFileAppears() {
        val store = FileTransferChunkStore(root, maxStoreBytes = 1024)
        store.storeEncryptedChunk(transferId, 0, ByteArray(600) { 1 })
        expectFailure { store.storeEncryptedChunk(transferId, 1, ByteArray(600) { 2 }) }
        assertNull(store.readEncryptedChunk(transferId, 1))
        assertEquals(600L, store.currentStoredBytes())

        expectFailure { store.storeEncryptedChunk(transferId, 2, ByteArray(15)) }
        expectFailure {
            store.storeEncryptedChunk(
                transferId,
                2,
                ByteArray(FileTransferChunkStore.MAX_CIPHERTEXT_BYTES + 1),
            )
        }
    }

    @Test
    fun traversalAndInvalidIndicesAreRejected() {
        val store = FileTransferChunkStore(root)
        for (invalid in listOf("", "../escape", "A".repeat(32), "0".repeat(31))) {
            expectFailure { store.storeEncryptedChunk(invalid, 0, ByteArray(16)) }
        }
        expectFailure { store.storeEncryptedChunk(transferId, -1, ByteArray(16)) }
        assertEquals(0L, store.currentStoredBytes())
    }

    @Test
    fun indicesBeyondLegacyUnsignedRangeAreStoredWithoutApplicationCeiling() {
        val store = FileTransferChunkStore(root)
        val highIndex = 5_000_000_000L
        val ciphertext = ByteArray(16) { 7 }
        store.storeEncryptedChunk(transferId, highIndex, ciphertext)
        assertTrue(store.hasEncryptedChunk(transferId, highIndex))
        assertArrayEquals(ciphertext, store.readEncryptedChunk(transferId, highIndex))
        assertEquals(listOf(highIndex), store.storedChunkIndices(transferId))
    }

    @Test
    fun legacyEightDigitChunkNamesRemainReadableAfterUpgrade() {
        val legacyChunks = java.io.File(root, "$transferId/chunks")
        assertTrue(legacyChunks.mkdirs())
        val ciphertext = ByteArray(16) { 8 }
        java.io.File(legacyChunks, "00000007.chunk").writeBytes(ciphertext)
        assertTrue(FileTransferChunkStore(root).hasEncryptedChunk(transferId, 7L))
        assertArrayEquals(ciphertext, FileTransferChunkStore(root).readEncryptedChunk(transferId, 7L))
    }

    @Test
    fun transferDeletionIsBoundedAndIdempotent() {
        val store = FileTransferChunkStore(root)
        store.storeEncryptedChunk(transferId, 0, ByteArray(16) { 3 })
        store.storeEncryptedChunk(transferId, 1, ByteArray(16) { 4 })
        assertTrue(store.deleteTransfer(transferId))
        assertTrue(store.storedChunkIndices(transferId).isEmpty())
        assertTrue(store.deleteTransfer(transferId))
        assertEquals(0, store.currentStoredBytes())
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected operation to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
