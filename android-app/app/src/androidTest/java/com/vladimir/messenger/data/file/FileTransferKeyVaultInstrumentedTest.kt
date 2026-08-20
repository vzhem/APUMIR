package com.vladimir.messenger.data.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferKeyVaultInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "apu_file_transfer_test_vault_v1"
    private val transferId = "0123456789abcdef0123456789abcdef"
    private val root by lazy { File(context.noBackupFilesDir, "file-transfer-key-vault-test-v1") }

    @Test
    fun wrappedKeyIsStableBoundAndTamperFailsWithoutOverwrite() {
        cleanup()
        try {
            var borrowed: ByteArray? = null
            val firstHash = FileTransferKeyVault.withOrCreateKeyIn(transferId, alias, root) { key ->
                borrowed = key
                assertEquals(32, key.size)
                assertFalse(key.all { it == 0.toByte() })
                sha256(key)
            }
            assertTrue(borrowed!!.all { it == 0.toByte() })
            val keyFile = File(root, "$transferId/key.v1")
            assertTrue(keyFile.isFile)
            assertEquals(FileTransferKeyEnvelope.ENVELOPE_BYTES.toLong(), keyFile.length())
            assertEquals(FileTransferKeyVault.Mode.READY, FileTransferKeyVault.modeIn(transferId, alias, root))

            val secondHash = FileTransferKeyVault.withExistingKeyIn(transferId, alias, root, ::sha256)
            assertEquals(firstHash, secondHash)
            val sameKey = ByteArray(32)
            FileTransferKeyVault.withExistingKeyIn(transferId, alias, root) { it.copyInto(sameKey) }
            FileTransferKeyVault.importKeyIn(transferId, alias, root, sameKey)
            sameKey[0] = (sameKey[0].toInt() xor 1).toByte()
            expectFailure { FileTransferKeyVault.importKeyIn(transferId, alias, root, sameKey) }
            sameKey.fill(0)
            assertEquals(
                firstHash,
                FileTransferKeyVault.withExistingKeyIn(transferId, alias, root, ::sha256),
            )

            val wrapped = keyFile.readBytes()
            wrapped[wrapped.lastIndex] = (wrapped.last().toInt() xor 1).toByte()
            keyFile.writeBytes(wrapped)
            val tamperedHash = sha256(keyFile.readBytes())
            assertEquals(FileTransferKeyVault.Mode.UNAVAILABLE, FileTransferKeyVault.modeIn(transferId, alias, root))
            expectFailure {
                FileTransferKeyVault.withOrCreateKeyIn(transferId, alias, root) { Unit }
            }
            assertEquals(tamperedHash, sha256(keyFile.readBytes()))
        } finally {
            cleanup()
            assertFalse(root.exists())
            assertFalse(keyStore().containsAlias(alias))
        }
    }

    private fun cleanup() {
        root.deleteRecursively()
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected operation to fail")
        } catch (_: Exception) {
            // expected
        }
    }
}
