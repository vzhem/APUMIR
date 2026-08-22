package com.vladimir.messenger.data.file

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import uniffi.p2p_core.FileTransferManifestFfi
import uniffi.p2p_core.decryptFileTransferChunk
import uniffi.p2p_core.openFileKeyEnvelope
import uniffi.p2p_core.parseFileTransferManifest
import uniffi.p2p_core.verifyFileExchangeBinding
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * Production seams for the file transport owner. All Android/FFI boundaries live here so the
 * sender/receiver state machines stay pure-JVM testable; nothing below leaks key material into
 * logs or ordinary preferences.
 */

/** One mesh message = one encoded packet fragment. Returns the underlying send result. */
fun interface PacketTransport {
    suspend fun send(messageId: String, chatId: String, recipientNodeId: String, text: String): Boolean
}

/** Production transport: Rust owns direct QUIC plus the encrypted durable relay custody. */
class RustPacketTransport @Inject constructor() : PacketTransport {
    override suspend fun send(
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        text: String,
    ): Boolean = RustBridge.sendMessage(messageId, chatId, recipientNodeId, text)
}

/** Local identity/file-exchange access used by the receiver to authenticate an offer. */
interface LocalExchangeIdentity {
    fun myNodeId(): String?
    fun myBinding(): ByteArray?
    fun <T> withSecret(operation: (ByteArray) -> T): T?
}

class AndroidLocalExchangeIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalExchangeIdentity {
    override fun myNodeId(): String? =
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getString("node_id", null)

    override fun myBinding(): ByteArray? = FileExchangeKeyStore.publicBinding(context)

    override fun <T> withSecret(operation: (ByteArray) -> T): T? =
        FileExchangeKeyStore.withExistingSecret(context, operation)
}

/** Per-transfer file key storage (wrapped by Android Keystore in production). */
interface TransferKeyVaultAccess {
    fun mode(transferId: String): FileTransferKeyVault.Mode
    fun importKey(transferId: String, key: ByteArray)
    fun <T> withExistingKey(transferId: String, operation: (ByteArray) -> T): T
}

class AndroidTransferKeyVaultAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransferKeyVaultAccess {
    override fun mode(transferId: String): FileTransferKeyVault.Mode =
        FileTransferKeyVault.mode(context, transferId)

    override fun importKey(transferId: String, key: ByteArray) =
        FileTransferKeyVault.importKey(context, transferId, key)

    override fun <T> withExistingKey(transferId: String, operation: (ByteArray) -> T): T =
        FileTransferKeyVault.withExistingKey(context, transferId, operation)
}

/** Strict crypto operations provided by the Rust core (fail closed on any inconsistency). */
interface FileCryptoGateway {
    fun parseManifest(manifestBytes: ByteArray): FileTransferManifestFfi
    fun verifyBinding(binding: ByteArray): Boolean
    fun bindingNodeId(binding: ByteArray): String
    fun openKeyEnvelope(
        envelope: ByteArray,
        myBinding: ByteArray,
        secret: ByteArray,
        manifest: ByteArray,
    ): ByteArray
    fun decryptChunk(
        manifestBytes: ByteArray,
        fileKey: ByteArray,
        chunkIndex: Int,
        ciphertext: ByteArray,
    ): ByteArray
}

class FfiFileCryptoGateway @Inject constructor() : FileCryptoGateway {
    override fun parseManifest(manifestBytes: ByteArray): FileTransferManifestFfi =
        parseFileTransferManifest(manifestBytes)

    override fun verifyBinding(binding: ByteArray): Boolean = verifyFileExchangeBinding(binding)

    override fun bindingNodeId(binding: ByteArray): String = fileExchangeBindingNodeId(binding)

    override fun openKeyEnvelope(
        envelope: ByteArray,
        myBinding: ByteArray,
        secret: ByteArray,
        manifest: ByteArray,
    ): ByteArray = openFileKeyEnvelope(envelope, myBinding, secret, manifest)

    override fun decryptChunk(
        manifestBytes: ByteArray,
        fileKey: ByteArray,
        chunkIndex: Int,
        ciphertext: ByteArray,
    ): ByteArray =
        decryptFileTransferChunk(manifestBytes, fileKey, chunkIndex.toUInt(), ciphertext)
}
