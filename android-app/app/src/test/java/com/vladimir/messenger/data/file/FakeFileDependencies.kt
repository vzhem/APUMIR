package com.vladimir.messenger.data.file

import java.nio.file.Files
import uniffi.p2p_core.FileTransferManifestFfi

/** Shared JVM fakes for the receiver transport tests: no Android, no native library needed. */
class FakeFileCryptoGateway(
    private val senderNodeId: String,
    private val recipientNodeId: String,
    private val transferIdHex: String,
    private val fileSha256Hex: String,
    private val fileSizeBytes: Long,
    private val chunkSizeBytes: Int,
    private val expiresAt: Long = Long.MAX_VALUE,
) : FileCryptoGateway {
    val chunkCount: Long =
        (fileSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes

    override fun parseManifest(manifestBytes: ByteArray): FileTransferManifestFfi =
        FileTransferManifestFfi(
            manifestBytes = manifestBytes.copyOf(),
            transferIdHex = transferIdHex,
            senderNodeId = senderNodeId,
            recipientNodeId = recipientNodeId,
            displayName = "photo.png",
            mediaType = "image/png",
            fileSize = fileSizeBytes.toULong(),
            chunkSize = chunkSizeBytes.toUInt(),
            chunkCount = chunkCount.toULong(),
            fileSha256Hex = fileSha256Hex,
            createdAtMs = 1L,
            expiresAtMs = expiresAt,
        )

    override fun verifyBinding(binding: ByteArray): Boolean = binding.size == 96

    override fun bindingNodeId(binding: ByteArray): String = senderNodeId

    override fun openKeyEnvelope(
        envelope: ByteArray,
        myBinding: ByteArray,
        secret: ByteArray,
        manifest: ByteArray,
    ): ByteArray = FILE_KEY.copyOf()

    /** Inverse of [fakeEncrypt]: strips the fake 16-byte tag. */
    override fun decryptChunk(
        manifestBytes: ByteArray,
        fileKey: ByteArray,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ): ByteArray = ciphertext.copyOfRange(16, ciphertext.size)

    companion object {
        val FILE_KEY = ByteArray(32) { 9 }

        fun fakeEncrypt(plaintext: ByteArray): ByteArray = ByteArray(16) { 5 } + plaintext
    }
}

class FakeLocalExchangeIdentity(private val myNodeId: String) : LocalExchangeIdentity {
    override fun myNodeId(): String? = myNodeId

    override fun myBinding(): ByteArray? = ByteArray(256) { 3 }

    override fun <T> withSecret(operation: (ByteArray) -> T): T? = operation(ByteArray(32) { 4 })
}

class FakeTransferKeyVault : TransferKeyVaultAccess {
    val keys = HashMap<String, ByteArray>()

    override fun mode(transferId: String): FileTransferKeyVault.Mode =
        if (keys.containsKey(transferId)) FileTransferKeyVault.Mode.READY
        else FileTransferKeyVault.Mode.ABSENT

    override fun importKey(transferId: String, key: ByteArray) {
        keys[transferId] = key.copyOf()
    }

    override fun <T> withExistingKey(transferId: String, operation: (ByteArray) -> T): T {
        val key = keys[transferId] ?: error("No key imported for $transferId")
        return operation(key)
    }
}

class RecordingPinner : FileExchangePinner {
    val pinnedBindings = mutableListOf<ByteArray>()
    var rejectNext = false

    override suspend fun pinFirstSeen(binding: ByteArray, nowMs: Long): Boolean {
        if (rejectNext) throw IllegalStateException("key changed")
        val existing = pinnedBindings.firstOrNull { it.contentEquals(binding) }
        if (existing != null) return false
        pinnedBindings.add(binding.copyOf())
        return true
    }
}

class RecordingNotifier : FileTransferReceiver.FileChatNotifier {
    val events = mutableListOf<String>()

    override suspend fun onFileReceived(
        chatId: String,
        senderId: String,
        messageId: String,
        displayName: String,
        mediaType: String,
        totalBytes: Long,
        fileSha256: String,
    ) {
        events += "$chatId|$senderId|$messageId|$displayName|$mediaType|$totalBytes"
    }
}

object TestDirs {
    fun newDir(prefix: String): java.io.File = Files.createTempDirectory(prefix).toFile()
}
