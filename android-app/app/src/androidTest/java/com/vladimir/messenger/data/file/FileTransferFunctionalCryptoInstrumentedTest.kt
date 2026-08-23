package com.vladimir.messenger.data.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.createFileTransferManifest
import uniffi.p2p_core.decryptFileTransferChunk
import uniffi.p2p_core.encryptFileTransferChunk
import uniffi.p2p_core.parseFileTransferManifest

@RunWith(AndroidJUnit4::class)
class FileTransferFunctionalCryptoInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestCryptoAndAppPrivateCiphertextStoreRoundTrip() {
        val root = File(context.noBackupFilesDir, "file-transfer-functional-test-v1")
        root.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintext = "hello".toByteArray()
        try {
            val sender = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getString("node_id", null)!!
            val candidate = "pk_0123456789abcdef0123456789abcdef"
            val recipient = if (sender == candidate) {
                "pk_fedcba9876543210fedcba9876543210"
            } else {
                candidate
            }
            val now = System.currentTimeMillis()
            val hash = MessageDigest.getInstance("SHA-256").digest(plaintext)
            val manifest = createFileTransferManifest(
                sender,
                recipient,
                "hello.txt",
                "text/plain",
                plaintext.size.toULong(),
                hash,
                now,
                now + 24 * 60 * 60 * 1_000L,
            )
            assertTrue(manifest.transferIdHex.matches(Regex("^[0-9a-f]{32}$")))
            assertEquals(1uL, manifest.chunkCount)
            val parsed = parseFileTransferManifest(manifest.manifestBytes)
            assertEquals(manifest.transferIdHex, parsed.transferIdHex)
            assertEquals(sender, parsed.senderNodeId)
            assertEquals(recipient, parsed.recipientNodeId)

            val ciphertext = encryptFileTransferChunk(
                manifest.manifestBytes,
                key,
                0uL,
                plaintext,
            )
            assertFalse(ciphertext.contentEquals(plaintext))
            val store = FileTransferChunkStore(root, maxStoreBytes = 1024)
            assertTrue(store.storeEncryptedChunk(manifest.transferIdHex, 0, ciphertext).newlyStored)
            val stored = store.readEncryptedChunk(manifest.transferIdHex, 0)!!
            assertArrayEquals(
                plaintext,
                decryptFileTransferChunk(manifest.manifestBytes, key, 0uL, stored),
            )
            assertFalse(store.storeEncryptedChunk(manifest.transferIdHex, 0, ciphertext).newlyStored)

            val tampered = stored.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            expectFailure {
                decryptFileTransferChunk(manifest.manifestBytes, key, 0uL, tampered)
            }
            expectFailure {
                parseFileTransferManifest(manifest.manifestBytes.copyOf(manifest.manifestBytes.size - 1))
            }
            assertTrue(store.deleteTransfer(manifest.transferIdHex))
            assertEquals(0L, store.currentStoredBytes())
        } finally {
            key.fill(0)
            plaintext.fill(0)
            root.deleteRecursively()
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected operation to fail")
        } catch (_: Exception) {
            // expected UniFFI/Core exception
        }
    }
}
