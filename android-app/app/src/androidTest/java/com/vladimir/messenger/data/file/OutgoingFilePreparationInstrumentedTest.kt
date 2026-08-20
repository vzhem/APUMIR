package com.vladimir.messenger.data.file

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.decryptFileTransferChunk
import uniffi.p2p_core.parseFileTransferManifest

@RunWith(AndroidJUnit4::class)
class OutgoingFilePreparationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "apu_file_transfer_test_preparation_v1"
    private val root by lazy { File(context.noBackupFilesDir, "file-preparation-owner-test-v1") }
    private val sourceFile by lazy { File(context.cacheDir, "file-preparation-source-test.bin") }

    @Test
    fun sourceBecomesDurableEncryptedPreparedTransferWithoutProductionState() = runBlocking {
        cleanup()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = database.fileTransferDao()
        val store = FileTransferChunkStore(root, maxStoreBytes = 2L * 1024 * 1024)
        val keyAccess = object : OutgoingFilePreparationService.KeyAccess {
            override fun create(transferId: String) {
                FileTransferKeyVault.withOrCreateKeyIn(transferId, alias, root) { Unit }
            }

            override fun <T> withExisting(
                transferId: String,
                operation: (ByteArray) -> T,
            ): T = FileTransferKeyVault.withExistingKeyIn(transferId, alias, root, operation)
        }
        val sourceBytes = ByteArray(200_000) { index -> (index % 251).toByte() }
        try {
            sourceFile.writeBytes(sourceBytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                sourceFile,
            )
            val service = OutgoingFilePreparationService(
                context,
                dao,
                store,
                keyAccess,
                isolatedTest = true,
            )
            val sender = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getString("node_id", null)!!
            val candidate = "pk_0123456789abcdef0123456789abcdef"
            val recipient = if (sender == candidate) {
                "pk_fedcba9876543210fedcba9876543210"
            } else {
                candidate
            }
            val prepared = service.prepare(
                source = uri,
                messageId = "file-message-test-v1",
                chatId = "file-chat-test-v1",
                recipientNodeId = recipient,
                nowMs = 1_800_000_000_000,
            )
            assertEquals(2, prepared.chunkCount)
            val entity = dao.getTransfer(prepared.transferId)!!
            assertEquals("PREPARED", entity.state)
            assertEquals(entity.chunkCount, entity.completedChunks)
            assertEquals(entity.totalBytes, entity.transferredBytes)
            val chunks = dao.getChunks(prepared.transferId)
            assertEquals(2, chunks.size)
            assertEquals(listOf(0, 1), store.storedChunkIndices(prepared.transferId))

            val manifestBytes = store.readManifest(prepared.transferId)!!
            val manifest = parseFileTransferManifest(manifestBytes)
            assertEquals(prepared.transferId, manifest.transferIdHex)
            val restored = ByteArrayOutputStream(sourceBytes.size)
            chunks.forEach { chunk ->
                val encrypted = store.readEncryptedChunk(prepared.transferId, chunk.chunkIndex)!!
                val plaintext = keyAccess.withExisting(prepared.transferId) { key ->
                    decryptFileTransferChunk(
                        manifestBytes,
                        key,
                        chunk.chunkIndex.toUInt(),
                        encrypted,
                    )
                }
                restored.write(plaintext)
                plaintext.fill(0)
                encrypted.fill(0)
            }
            assertArrayEquals(sourceBytes, restored.toByteArray())
            assertTrue(store.deleteTransfer(prepared.transferId))
            assertEquals(1, dao.deleteTransfer(prepared.transferId))
            assertEquals(0L, store.currentStoredBytes())
            assertTrue(root.delete() || !root.exists())
            assertFalse(root.exists())
        } finally {
            sourceBytes.fill(0)
            database.close()
            cleanup()
            assertFalse(root.exists())
            assertFalse(sourceFile.exists())
            assertFalse(keyStore().containsAlias(alias))
        }
    }

    private fun cleanup() {
        root.deleteRecursively()
        sourceFile.delete()
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
