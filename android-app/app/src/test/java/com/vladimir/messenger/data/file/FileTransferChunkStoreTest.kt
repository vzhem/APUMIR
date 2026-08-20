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
        assertEquals(listOf(0), store.storedChunkIndices(transferId))
        assertArrayEquals(read, store.readEncryptedChunk(transferId, 0))
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
        expectFailure {
            store.storeEncryptedChunk(
                transferId,
                FileTransferChunkStore.MAX_CHUNKS_PER_TRANSFER,
                ByteArray(16),
            )
        }
        assertEquals(0, store.currentStoredBytes())
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
