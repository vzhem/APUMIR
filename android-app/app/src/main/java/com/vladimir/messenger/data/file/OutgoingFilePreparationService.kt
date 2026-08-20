package com.vladimir.messenger.data.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.p2p_core.FileTransferManifestFfi
import uniffi.p2p_core.createFileTransferManifest
import uniffi.p2p_core.encryptFileTransferChunk

/** Prepares encrypted durable chunks locally. It does not publish or claim delivery. */
class OutgoingFilePreparationService private constructor(
    @ApplicationContext private val context: Context,
    private val transferDao: FileTransferDao,
    private val store: FileTransferChunkStore,
    private val keyAccess: KeyAccess,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        transferDao: FileTransferDao,
    ) : this(
        context.applicationContext,
        transferDao,
        FileTransferChunkStore.forApplication(context.applicationContext),
        ProductionKeyAccess(context.applicationContext),
    )

    internal constructor(
        context: Context,
        transferDao: FileTransferDao,
        store: FileTransferChunkStore,
        keyAccess: KeyAccess,
        isolatedTest: Boolean,
    ) : this(context.applicationContext, transferDao, store, keyAccess) {
        require(isolatedTest) { "Custom file preparation dependencies are test-only" }
    }

    internal interface KeyAccess {
        fun create(transferId: String)
        fun <T> withExisting(transferId: String, operation: (ByteArray) -> T): T
    }

    private class ProductionKeyAccess(private val context: Context) : KeyAccess {
        override fun create(transferId: String) {
            FileTransferKeyVault.withOrCreateKey(context, transferId) { key ->
                check(key.size == FileTransferKeyEnvelope.KEY_BYTES)
            }
        }

        override fun <T> withExisting(transferId: String, operation: (ByteArray) -> T): T =
            FileTransferKeyVault.withExistingKey(context, transferId, operation)
    }

    data class PreparedTransfer(
        val transferId: String,
        val messageId: String,
        val displayName: String,
        val mediaType: String,
        val totalBytes: Long,
        val chunkCount: Int,
        val fileSha256: String,
    )

    suspend fun prepare(
        source: Uri,
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        qualifiedDirectReferrals: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): PreparedTransfer = withContext(Dispatchers.IO) {
        require(messageId.isNotBlank() && chatId.isNotBlank()) { "Missing file message binding" }
        val senderNodeId = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("node_id", null)
            ?: throw IllegalStateException("Local identity is unavailable")
        val inspected = AndroidFileSelection.inspect(context.contentResolver, source)
        FileTransferRankPolicy.requireCanSend(
            qualifiedDirectReferrals = qualifiedDirectReferrals,
            mediaType = inspected.mediaType,
            sizeBytes = inspected.sizeBytes,
            technicalLimitBytes = FileTransferSourceInspector.MAX_FILE_BYTES,
        )
        val expiresAtMs = Math.addExact(nowMs, TRANSFER_TTL_MS)
        val manifest = createFileTransferManifest(
            senderNodeId,
            recipientNodeId,
            inspected.displayName,
            inspected.mediaType,
            inspected.sizeBytes.toULong(),
            inspected.sha256.hexToBytes(),
            nowMs,
            expiresAtMs,
        )
        val entity = manifest.toEntity(messageId, chatId, recipientNodeId, nowMs)
        check(transferDao.insertNewTransfer(entity)) { "Transfer ID collision" }
        try {
            check(store.storeManifest(manifest.transferIdHex, manifest.manifestBytes)) {
                "New transfer unexpectedly reused a manifest"
            }
            keyAccess.create(manifest.transferIdHex)
            stageChunks(source, manifest, inspected.sha256, store)
            PreparedTransfer(
                transferId = manifest.transferIdHex,
                messageId = messageId,
                displayName = manifest.displayName,
                mediaType = manifest.mediaType,
                totalBytes = manifest.fileSize.toLong(),
                chunkCount = manifest.chunkCount.toInt(),
                fileSha256 = manifest.fileSha256Hex,
            )
        } catch (error: Exception) {
            val filesRemoved = runCatching { store.deleteTransfer(manifest.transferIdHex) }
                .getOrDefault(false)
            if (filesRemoved) transferDao.deleteTransfer(manifest.transferIdHex)
            throw error
        }
    }

    private suspend fun stageChunks(
        source: Uri,
        manifest: FileTransferManifestFfi,
        expectedSha256: String,
        store: FileTransferChunkStore,
    ) {
        val resolver: ContentResolver = context.contentResolver
        val input = resolver.openInputStream(source)
            ?: throw IllegalArgumentException("Cannot reopen selected file")
        val digest = MessageDigest.getInstance("SHA-256")
        var transferred = 0L
        input.use { stream ->
            for (index in 0 until manifest.chunkCount.toInt()) {
                val expected = minOf(
                    manifest.chunkSize.toLong(),
                    manifest.fileSize.toLong() - transferred,
                ).toInt()
                val plaintext = ByteArray(expected)
                try {
                    readExactly(stream, plaintext)
                    digest.update(plaintext)
                    val ciphertext = keyAccess.withExisting(manifest.transferIdHex) { key ->
                        encryptFileTransferChunk(
                            manifest.manifestBytes,
                            key,
                            index.toUInt(),
                            plaintext,
                        )
                    }
                    try {
                        val stored = store.storeEncryptedChunk(
                            manifest.transferIdHex,
                            index,
                            ciphertext,
                        )
                        transferDao.upsertChunk(
                            FileTransferChunkEntity(
                                transferId = manifest.transferIdHex,
                                chunkIndex = index,
                                state = "STAGED",
                                ciphertextBytes = stored.ciphertextBytes,
                                chunkSha256 = stored.sha256,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        )
                    } finally {
                        ciphertext.fill(0)
                    }
                } finally {
                    plaintext.fill(0)
                }
                transferred += expected
                check(
                    transferDao.advanceProgress(
                        transferId = manifest.transferIdHex,
                        state = if (index + 1 == manifest.chunkCount.toInt()) "PREPARED" else "PREPARING",
                        completedChunks = index + 1,
                        transferredBytes = transferred,
                        updatedAtMs = System.currentTimeMillis(),
                        errorCode = null,
                    ) == 1
                ) { "Cannot persist monotonic file preparation progress" }
            }
            check(stream.read() == -1) { "Selected file grew during preparation" }
        }
        check(transferred == manifest.fileSize.toLong()) { "Selected file was truncated" }
        check(digest.digest().toHex() == expectedSha256) { "Selected file changed after inspection" }
        if (manifest.chunkCount == 0u) {
            check(
                transferDao.advanceProgress(
                    transferId = manifest.transferIdHex,
                    state = "PREPARED",
                    completedChunks = 0,
                    transferredBytes = 0,
                    updatedAtMs = System.currentTimeMillis(),
                    errorCode = null,
                ) == 1
            ) { "Cannot mark empty file prepared" }
        }
    }

    private fun readExactly(input: java.io.InputStream, output: ByteArray) {
        var offset = 0
        while (offset < output.size) {
            val read = input.read(output, offset, output.size - offset)
            check(read > 0) { "Selected file was truncated" }
            offset += read
        }
    }

    private fun FileTransferManifestFfi.toEntity(
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        nowMs: Long,
    ): FileTransferEntity = FileTransferEntity(
        transferId = transferIdHex,
        messageId = messageId,
        chatId = chatId,
        peerNodeId = recipientNodeId,
        direction = "OUTGOING",
        displayName = displayName,
        mediaType = mediaType,
        totalBytes = fileSize.toLong(),
        chunkSize = chunkSize.toInt(),
        chunkCount = chunkCount.toInt(),
        fileSha256 = fileSha256Hex,
        state = "PREPARING",
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        updatedAtMs = nowMs,
    )

    private fun String.hexToBytes(): ByteArray {
        require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TRANSFER_TTL_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
