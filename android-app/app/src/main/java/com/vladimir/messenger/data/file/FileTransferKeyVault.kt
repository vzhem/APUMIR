package com.vladimir.messenger.data.file

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound wrapping vault. Plain transfer keys are exposed only to one bounded callback. */
object FileTransferKeyVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val PRODUCTION_ALIAS = "apu_file_transfer_wrap_v1"
    private const val KEY_FILE = "key.v1"
    private const val GCM_TAG_BITS = 128
    private val TRANSFER_ID = Regex("^[0-9a-f]{32}$")
    private val AAD_DOMAIN = "apu-file-transfer-key-wrap-v1\u0000".toByteArray(Charsets.US_ASCII)

    enum class Mode { ABSENT, READY, UNAVAILABLE }

    class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Synchronized
    fun <T> withOrCreateKey(context: Context, transferId: String, operation: (ByteArray) -> T): T =
        withOrCreateKeyIn(
            transferId,
            PRODUCTION_ALIAS,
            productionRoot(context.applicationContext),
            operation,
        )

    @Synchronized
    fun <T> withExistingKey(context: Context, transferId: String, operation: (ByteArray) -> T): T =
        withExistingKeyIn(
            transferId,
            PRODUCTION_ALIAS,
            productionRoot(context.applicationContext),
            operation,
        )

    @Synchronized
    fun importKey(context: Context, transferId: String, key: ByteArray) {
        importKeyIn(transferId, PRODUCTION_ALIAS, productionRoot(context.applicationContext), key)
    }

    @Synchronized
    fun mode(context: Context, transferId: String): Mode =
        modeIn(transferId, PRODUCTION_ALIAS, productionRoot(context.applicationContext))

    @Synchronized
    internal fun <T> withOrCreateKeyIn(
        transferId: String,
        alias: String,
        root: File,
        operation: (ByteArray) -> T,
    ): T {
        validateNamespace(transferId, alias, root)
        val file = keyFile(root, transferId)
        val key = if (file.exists()) {
            unwrapExisting(file, transferId, existingWrapKey(alias))
        } else {
            ByteArray(FileTransferKeyEnvelope.KEY_BYTES).also(SecureRandom()::nextBytes).also {
                try {
                    persistWrapped(file, transferId, it, ensureWrapKey(alias))
                } catch (error: Exception) {
                    it.fill(0)
                    throw KeyUnavailableException("Cannot create wrapped transfer key", error)
                }
            }
        }
        return try {
            operation(key)
        } finally {
            key.fill(0)
        }
    }

    @Synchronized
    internal fun <T> withExistingKeyIn(
        transferId: String,
        alias: String,
        root: File,
        operation: (ByteArray) -> T,
    ): T {
        validateNamespace(transferId, alias, root)
        val file = keyFile(root, transferId)
        if (!file.isFile) throw KeyUnavailableException("Wrapped transfer key is absent")
        val key = unwrapExisting(file, transferId, existingWrapKey(alias))
        return try {
            operation(key)
        } finally {
            key.fill(0)
        }
    }

    @Synchronized
    internal fun importKeyIn(transferId: String, alias: String, root: File, supplied: ByteArray) {
        validateNamespace(transferId, alias, root)
        require(supplied.size == FileTransferKeyEnvelope.KEY_BYTES) { "Invalid transfer key length" }
        val copy = supplied.copyOf()
        try {
            val file = keyFile(root, transferId)
            if (file.exists()) {
                val existing = unwrapExisting(file, transferId, existingWrapKey(alias))
                try {
                    check(MessageDigest.isEqual(existing, copy)) {
                        "Existing wrapped transfer key differs"
                    }
                } finally {
                    existing.fill(0)
                }
                return
            }
            persistWrapped(file, transferId, copy, ensureWrapKey(alias))
        } catch (error: KeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw KeyUnavailableException("Cannot import wrapped transfer key", error)
        } finally {
            copy.fill(0)
        }
    }

    @Synchronized
    internal fun modeIn(transferId: String, alias: String, root: File): Mode {
        return try {
            validateNamespace(transferId, alias, root)
            val file = keyFile(root, transferId)
            if (!file.exists()) return Mode.ABSENT
            val key = unwrapExisting(file, transferId, existingWrapKey(alias))
            key.fill(0)
            Mode.READY
        } catch (_: Exception) {
            Mode.UNAVAILABLE
        }
    }

    private fun persistWrapped(file: File, transferId: String, key: ByteArray, wrapKey: SecretKey) {
        check(!file.exists()) { "Wrapped transfer key already exists" }
        val parent = file.parentFile ?: throw KeyUnavailableException("Missing transfer key directory")
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create transfer key directory" }
        check(!Files.isSymbolicLink(parent.toPath())) { "Symbolic transfer key directory rejected" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        cipher.updateAAD(aad(transferId))
        val envelope = FileTransferKeyEnvelope.encode(cipher.iv, cipher.doFinal(key))
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(envelope)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        } finally {
            envelope.fill(0)
        }
    }

    private fun unwrapExisting(file: File, transferId: String, wrapKey: SecretKey?): ByteArray {
        val key = wrapKey ?: throw KeyUnavailableException("Wrapped key exists without Keystore alias")
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Invalid wrapped key file" }
        check(file.length() == FileTransferKeyEnvelope.ENVELOPE_BYTES.toLong()) {
            "Invalid wrapped key file length"
        }
        val envelope = file.readBytes()
        try {
            val decoded = FileTransferKeyEnvelope.decode(envelope)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decoded.iv))
            cipher.updateAAD(aad(transferId))
            val plaintext = cipher.doFinal(decoded.ciphertext)
            if (plaintext.size != FileTransferKeyEnvelope.KEY_BYTES) {
                plaintext.fill(0)
                throw KeyUnavailableException("Unexpected unwrapped transfer key length")
            }
            decoded.iv.fill(0)
            decoded.ciphertext.fill(0)
            return plaintext
        } catch (error: KeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw KeyUnavailableException("Cannot unwrap existing transfer key", error)
        } finally {
            envelope.fill(0)
        }
    }

    private fun keyFile(root: File, transferId: String): File {
        check(!Files.isSymbolicLink(root.toPath())) { "Symbolic transfer key root rejected" }
        val transfer = File(root, transferId)
        val rootPath = root.canonicalFile.toPath()
        val transferPath = transfer.canonicalFile.toPath()
        check(transferPath.parent == rootPath) { "Transfer key path escaped root" }
        check(!Files.isSymbolicLink(transfer.toPath())) { "Symbolic transfer key path rejected" }
        val file = File(transfer, KEY_FILE)
        check(file.canonicalFile.toPath().parent == transferPath) { "Transfer key file escaped root" }
        return file
    }

    private fun aad(transferId: String): ByteArray = AAD_DOMAIN + transferId.toByteArray(Charsets.US_ASCII)

    private fun productionRoot(context: Context): File =
        File(context.noBackupFilesDir, "file_transfers/v1")

    private fun validateNamespace(transferId: String, alias: String, root: File) {
        require(TRANSFER_ID.matches(transferId)) { "Invalid transfer ID" }
        require(alias == PRODUCTION_ALIAS || alias.startsWith("apu_file_transfer_test_")) {
            "Unexpected transfer Keystore alias"
        }
        require(root.path.isNotBlank())
    }

    private fun existingWrapKey(alias: String): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun ensureWrapKey(alias: String): SecretKey {
        existingWrapKey(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
