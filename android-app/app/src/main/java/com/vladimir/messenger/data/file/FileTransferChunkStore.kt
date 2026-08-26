package com.vladimir.messenger.data.file

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** App-private store for bounded encrypted chunks only. Plaintext and file keys are forbidden here. */
class FileTransferChunkStore(
    private val root: File,
    private val maxStoreBytes: Long = DEFAULT_STORE_QUOTA_BYTES,
) {
    /** Lazily rebuilt once after process start; avoids an O(total chunks) scan for every write. */
    private var cachedStoredBytes: Long? = null
    data class StoredChunk(
        val chunkIndex: Long,
        val ciphertextBytes: Long,
        val sha256: String,
        val newlyStored: Boolean,
    )

    init {
        require(maxStoreBytes in MIN_STORE_QUOTA_BYTES..MAX_STORE_QUOTA_BYTES)
    }

    @Synchronized
    fun storeManifest(transferId: String, manifest: ByteArray): Boolean {
        validateTransferId(transferId)
        require(manifest.size in MIN_MANIFEST_BYTES..MAX_MANIFEST_BYTES) { "Invalid manifest size" }
        val transfer = transferDirectory(transferId, create = true)
        val target = checkedChild(transfer, MANIFEST_FILE)
        if (target.exists()) {
            val existing = readExactBounded(target, MIN_MANIFEST_BYTES, MAX_MANIFEST_BYTES)
            val same = MessageDigest.isEqual(existing, manifest)
            existing.fill(0)
            check(same) { "Transfer already contains a different manifest" }
            return false
        }
        atomicWrite(target, manifest)
        return true
    }

    @Synchronized
    fun readManifest(transferId: String): ByteArray? {
        validateTransferId(transferId)
        val target = checkedChild(transferDirectory(transferId, create = false), MANIFEST_FILE)
        if (!target.exists()) return null
        return readExactBounded(target, MIN_MANIFEST_BYTES, MAX_MANIFEST_BYTES)
    }

    @Synchronized
    fun storeKeyEnvelope(transferId: String, envelope: ByteArray): Boolean {
        validateTransferId(transferId)
        require(envelope.size in MIN_KEY_ENVELOPE_BYTES..MAX_KEY_ENVELOPE_BYTES)
        val target = checkedChild(transferDirectory(transferId, create = true), KEY_ENVELOPE_FILE)
        if (target.exists()) {
            val existing = readExactBounded(target, MIN_KEY_ENVELOPE_BYTES, MAX_KEY_ENVELOPE_BYTES)
            val same = MessageDigest.isEqual(existing, envelope)
            existing.fill(0)
            check(same) { "Transfer already contains a different key envelope" }
            return false
        }
        atomicWrite(target, envelope)
        return true
    }

    @Synchronized
    fun readKeyEnvelope(transferId: String): ByteArray? {
        validateTransferId(transferId)
        val target = checkedChild(transferDirectory(transferId, create = false), KEY_ENVELOPE_FILE)
        if (!target.exists()) return null
        return readExactBounded(target, MIN_KEY_ENVELOPE_BYTES, MAX_KEY_ENVELOPE_BYTES)
    }

    @Synchronized
    fun storeEncryptedChunk(
        transferId: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ): StoredChunk {
        validateTransferId(transferId)
        require(chunkIndex >= 0L) { "Invalid chunk index" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Invalid encrypted chunk size"
        }
        val chunks = chunksDirectory(transferId, create = true)
        val target = chunkFile(chunks, chunkIndex)
        val incomingHash = sha256(ciphertext)
        if (target.exists()) {
            val existing = readBounded(target)
            val existingHash = sha256(existing)
            existing.fill(0)
            check(existingHash == incomingHash && target.length() == ciphertext.size.toLong()) {
                "Chunk index already contains different ciphertext"
            }
            return StoredChunk(chunkIndex, target.length(), incomingHash, newlyStored = false)
        }

        val projected = Math.addExact(currentStoredBytes(), ciphertext.size.toLong())
        check(projected <= maxStoreBytes) { "File transfer owner quota exceeded" }
        atomicWrite(target, ciphertext)
        cachedStoredBytes = projected
        return StoredChunk(chunkIndex, target.length(), incomingHash, newlyStored = true)
    }

    @Synchronized
    fun hasEncryptedChunk(transferId: String, chunkIndex: Long): Boolean {
        validateTransferId(transferId)
        require(chunkIndex >= 0L) { "Invalid chunk index" }
        return chunkFile(chunksDirectory(transferId, create = false), chunkIndex).isFile
    }

    @Synchronized
    fun readEncryptedChunk(transferId: String, chunkIndex: Long): ByteArray? {
        validateTransferId(transferId)
        require(chunkIndex >= 0L) { "Invalid chunk index" }
        val file = chunkFile(chunksDirectory(transferId, create = false), chunkIndex)
        if (!file.exists()) return null
        return readBounded(file)
    }

    @Synchronized
    fun storedChunkIndices(transferId: String): List<Long> {
        validateTransferId(transferId)
        val chunks = chunksDirectory(transferId, create = false)
        if (!chunks.isDirectory) return emptyList()
        return chunks.listFiles().orEmpty()
            .filter { it.isFile && CHUNK_FILE.matches(it.name) }
            .map { it.name.removeSuffix(".chunk").toLong() }
            .sorted()
    }

    @Synchronized
    fun deleteTransfer(transferId: String): Boolean {
        validateTransferId(transferId)
        cachedStoredBytes = null
        val directory = checkedChild(rootDirectory(create = false), transferId)
        if (!directory.exists()) return true
        check(!Files.isSymbolicLink(directory.toPath())) { "Symbolic transfer directory rejected" }
        directory.walkBottomUp().forEach { entry ->
            check(!Files.isSymbolicLink(entry.toPath())) { "Symbolic transfer entry rejected" }
            if (!entry.delete() && entry.exists()) return false
        }
        return !directory.exists()
    }

    @Synchronized
    fun currentStoredBytes(): Long {
        cachedStoredBytes?.let { return it }
        val base = rootDirectory(create = false)
        if (!base.isDirectory) return 0L.also { cachedStoredBytes = it }
        var total = 0L
        base.walkTopDown().forEach { file ->
            check(!Files.isSymbolicLink(file.toPath())) { "Symbolic entry in file transfer store" }
            if (file.isFile && CHUNK_FILE.matches(file.name)) {
                total = Math.addExact(total, file.length())
                check(total <= MAX_STORE_QUOTA_BYTES) { "Unbounded file transfer store" }
            }
        }
        cachedStoredBytes = total
        return total
    }

    private fun chunksDirectory(transferId: String, create: Boolean): File {
        val chunks = checkedChild(transferDirectory(transferId, create), "chunks")
        if (create) {
            check(chunks.mkdirs() || chunks.isDirectory) { "Cannot create encrypted chunk directory" }
        }
        return chunks
    }

    private fun transferDirectory(transferId: String, create: Boolean): File {
        val transfer = checkedChild(rootDirectory(create), transferId)
        if (create) {
            check(transfer.mkdirs() || transfer.isDirectory) { "Cannot create transfer directory" }
        }
        return transfer
    }

    private fun rootDirectory(create: Boolean): File {
        if (create) check(root.mkdirs() || root.isDirectory) { "Cannot create file transfer root" }
        return root
    }

    private fun checkedChild(parent: File, name: String): File {
        val child = File(parent, name)
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        check(childPath.parent == parentPath) { "File transfer path escaped its parent" }
        check(!Files.isSymbolicLink(child.toPath())) { "Symbolic file transfer path rejected" }
        return child
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("Missing atomic write parent")
        val temporary = checkedChild(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.length() == bytes.size.toLong()) { "Incomplete atomic file write" }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic file move unavailable", unsupported)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readBounded(file: File): ByteArray =
        readExactBounded(file, MIN_CIPHERTEXT_BYTES, MAX_CIPHERTEXT_BYTES)

    private fun readExactBounded(file: File, minimum: Int, maximum: Int): ByteArray {
        val length = file.length()
        check(length in minimum.toLong()..maximum.toLong()) { "Stored file has invalid size" }
        return file.inputStream().use { input ->
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                check(read > 0) { "Stored file truncated while reading" }
                offset += read
            }
            check(input.read() == -1) { "Stored file grew while reading" }
            bytes
        }
    }

    private fun validateTransferId(value: String) {
        require(TRANSFER_ID.matches(value)) { "Invalid transfer ID" }
    }

    private fun chunkFileName(index: Long): String = String.format(Locale.ROOT, "%020d.chunk", index)

    private fun chunkFile(chunks: File, index: Long): File {
        val current = checkedChild(chunks, chunkFileName(index))
        if (current.exists() || index > LEGACY_MAX_CHUNK_INDEX) return current
        val legacy = checkedChild(chunks, String.format(Locale.ROOT, "%08d.chunk", index))
        return if (legacy.exists()) legacy else current
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_STORE_QUOTA_BYTES = Long.MAX_VALUE
        const val MIN_MANIFEST_BYTES = 64
        const val MAX_MANIFEST_BYTES = 2 * 1024
        const val MIN_KEY_ENVELOPE_BYTES = 200
        const val MAX_KEY_ENVELOPE_BYTES = 2 * 1024
        const val MIN_STORE_QUOTA_BYTES = 1024L
        const val MAX_STORE_QUOTA_BYTES = Long.MAX_VALUE
        const val MAX_PLAINTEXT_CHUNK_BYTES = 4 * 1024 * 1024
        const val AEAD_TAG_BYTES = 16
        const val MIN_CIPHERTEXT_BYTES = AEAD_TAG_BYTES
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_CHUNK_BYTES + AEAD_TAG_BYTES
        private const val LEGACY_MAX_CHUNK_INDEX = 99_999_999L
        private const val MANIFEST_FILE = "manifest.v1"
        private const val KEY_ENVELOPE_FILE = "key-envelope.v1"
        private val TRANSFER_ID = Regex("^[0-9a-f]{32}$")
        private val CHUNK_FILE = Regex("^(?:[0-9]{8}|[0-9]{20})\\.chunk$")

        fun forApplication(context: Context): FileTransferChunkStore = FileTransferChunkStore(
            File(context.noBackupFilesDir, "file_transfers/v1"),
        )
    }
}
