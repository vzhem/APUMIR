package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.p2p_core.FileTransferManifestFfi

/** TOFU pin boundary so the receiver stays JVM-testable without the native library. */
fun interface FileExchangePinner {
    /** @return true when this binding was pinned for the first time; throws on key change. */
    suspend fun pinFirstSeen(binding: ByteArray, nowMs: Long): Boolean
}

/**
 * Receiver-side ingest for direct file transfers (F3). All packet items arrive as ordinary
 * durable text messages; this class reassembles the bounded fragments, verifies the offer
 * (manifest authenticity via the signed key envelope, recipient identity, expiry), stores
 * encrypted chunks durably, replies with deterministic file-ACKs the sender windows against,
 * and only exposes plaintext after the final whole-file SHA-256 verification.
 *
 * Nothing here trusts packet text for authorization: a wrong sender, a foreign recipient, an
 * expired offer, a geometry mismatch or a tampered fragment fails closed and is dropped.
 */
class FileTransferReceiver(
    private val transferDao: FileTransferDao,
    private val chunkStore: FileTransferChunkStore,
    private val receivedStore: ReceivedFileStore,
    private val pinner: FileExchangePinner,
    private val crypto: FileCryptoGateway,
    private val keyVault: TransferKeyVaultAccess,
    private val identity: LocalExchangeIdentity,
    private val transport: PacketTransport,
    private val ackSink: suspend (transferIdHex: String, contiguousChunks: Int) -> Unit,
    private val notifier: FileChatNotifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class PendingItem {
        val fragments: MutableMap<Int, ByteArray> = HashMap()
        var fragmentCount: Int = -1
    }

    private val mutex = Mutex()
    private val pendingItems = LinkedHashMap<String, PendingItem>()
    private val bufferedChunks = HashMap<String, MutableMap<Int, ByteArray>>()
    private val receivedIndices = HashMap<String, MutableSet<Int>>()
    private var pendingBytes = 0L

    /** Returns true when the text was a file packet (caller must not store it as chat text). */
    suspend fun onIncomingText(senderId: String, chatId: String, messageId: String, text: String): Boolean {
        if (FileTransferWire.isHelloText(text)) {
            onHelloText(senderId, text)
            return true
        }
        if (!FileTransferWire.isFilePacketText(text)) return false
        mutex.withLock {
            runCatching {
                val packet = FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(text))
                val transferIdHex = FileTransferWire.transferIdHexFromPacket(packet)
                collectFragment(senderId, chatId, transferIdHex, packet)
            }.onFailure { error ->
                Log.w(TAG, "Dropped malformed file packet from $senderId: ${error.message}")
            }
        }
        return true
    }

    enum class HelloResult { NOT_HELLO, PINNED_NEW, PINNED_ALREADY, REJECTED }

    /**
     * File-HELLO handshake: a tiny durable message carrying only the sender's signed exchange
     * binding. Verified against the message sender, TOFU-pinned, and the caller auto-replies
     * with its own HELLO on a first-time pin so both sides end up pinned. Breaks the
     * first-file deadlock without weakening the pin (a changed key still throws and rejects).
     */
    suspend fun onHelloText(senderId: String, text: String): HelloResult {
        if (!FileTransferWire.isHelloText(text)) return HelloResult.NOT_HELLO
        mutex.withLock {
            try {
                val binding = FileTransferWire.decodeHelloBinding(text)
                if (!crypto.verifyBinding(binding) || crypto.bindingNodeId(binding) != senderId) {
                    Log.w(TAG, "File HELLO from $senderId failed verification; dropped")
                    return HelloResult.REJECTED
                }
                val newlyPinned = pinner.pinFirstSeen(binding, nowMs())
                Log.i(
                    TAG,
                    "File HELLO from $senderId: ${if (newlyPinned) "pinned (new)" else "already pinned"}",
                )
                return if (newlyPinned) HelloResult.PINNED_NEW else HelloResult.PINNED_ALREADY
            } catch (error: Exception) {
                Log.w(TAG, "File HELLO from $senderId rejected: ${error.message}")
                return HelloResult.REJECTED
            }
        }
    }

    private suspend fun collectFragment(
        senderId: String,
        chatId: String,
        transferIdHex: String,
        packet: FileTransferPacketCodec.Packet,
    ) {
        val key = "${transferIdHex}|${packet.type.wire}|${packet.itemIndex}"
        val pending = pendingItems[key] ?: PendingItem().also {
            pendingItems[key] = it
            evictOldestIfOverBudget()
        }
        check(pending.fragmentCount == -1 || pending.fragmentCount == packet.fragmentCount) {
            "Fragment count conflict for file item"
        }
        val existing = pending.fragments.put(packet.fragmentIndex, packet.payload.copyOf())
        if (existing != null) {
            existing.fill(0)
            return
        }
        pending.fragmentCount = packet.fragmentCount
        pendingBytes += packet.payload.size
        if (pending.fragments.size < packet.fragmentCount) return

        // Complete item: re-encode fragments and strictly reassemble.
        val encodedFragments = IntRange(0, packet.fragmentCount - 1).map { fragment ->
            FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    packet.type,
                    hexToBytes(transferIdHex),
                    packet.itemIndex,
                    fragment,
                    packet.fragmentCount,
                    pending.fragments.getValue(fragment),
                )
            )
        }
        releasePending(key, pending)
        val payload = FileTransferPacketCodec.reassemble(encodedFragments)
        try {
            when (packet.type) {
                FileTransferPacketCodec.Type.OFFER -> handleOffer(senderId, chatId, payload)
                FileTransferPacketCodec.Type.CHUNK -> handleChunk(transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.ACK -> ackSink(transferIdHex, packet.itemIndex)
                FileTransferPacketCodec.Type.CANCEL ->
                    Log.i(TAG, "File transfer CANCEL notice for $transferIdHex")
            }
        } finally {
            payload.fill(0)
        }
    }

    private fun releasePending(key: String, item: PendingItem) {
        pendingItems.remove(key)
        pendingBytes -= item.fragments.values.sumOf { it.size }
        item.fragments.values.forEach { it.fill(0) }
        item.fragments.clear()
    }

    private fun evictOldestIfOverBudget() {
        while (pendingItems.size > MAX_PENDING_ITEMS || pendingBytes > MAX_PENDING_BYTES) {
            val oldestKey = pendingItems.keys.firstOrNull() ?: break
            releasePending(oldestKey, pendingItems.getValue(oldestKey))
            Log.w(TAG, "Evicted stale file item buffer $oldestKey")
        }
    }

    private suspend fun handleOffer(senderId: String, chatId: String, payload: ByteArray) {
        val offer = FileOfferPdu.decode(payload)
        val manifest = crypto.parseManifest(offer.manifest)
        val now = nowMs()
        if (manifest.senderNodeId != senderId) {
            Log.w(TAG, "File offer sender mismatch: ${manifest.senderNodeId} != $senderId")
            return
        }
        val myNodeId = identity.myNodeId()
        if (myNodeId == null || manifest.recipientNodeId != myNodeId) {
            Log.w(TAG, "File offer addressed to another node; dropped")
            return
        }
        if (manifest.expiresAtMs <= now) {
            Log.w(TAG, "File offer expired at ${manifest.expiresAtMs}; dropped")
            return
        }
        if (!crypto.verifyBinding(offer.senderBinding) || crypto.bindingNodeId(offer.senderBinding) != senderId) {
            Log.w(TAG, "File offer sender binding failed verification; dropped")
            return
        }
        try {
            pinner.pinFirstSeen(offer.senderBinding, now)
        } catch (pinError: Exception) {
            Log.w(TAG, "File offer rejected: pinned exchange key changed for $senderId (${pinError.message})")
            return
        }

        val transferIdHex = manifest.transferIdHex
        val existing = transferDao.getTransfer(transferIdHex)
        if (existing != null && (existing.direction != "INCOMING" || existing.peerNodeId != senderId)) {
            Log.w(TAG, "File offer conflicts with local transfer row; dropped")
            return
        }
        if (existing?.state == "FAILED") {
            Log.w(TAG, "File offer for already failed transfer; dropped")
            return
        }
        val transfer = existing ?: insertIncomingTransfer(manifest, senderId, chatId, now) ?: return

        Log.i(
            TAG,
            "File offer accepted: $transferIdHex from $senderId " +
                "(${manifest.displayName}, ${manifest.fileSize} B, ${manifest.chunkCount} chunks)",
        )
        chunkStore.storeManifest(transferIdHex, offer.manifest)
        chunkStore.storeKeyEnvelope(transferIdHex, offer.keyEnvelope)
        if (keyVault.mode(transferIdHex) != FileTransferKeyVault.Mode.READY) {
            openAndImportKey(transferIdHex, offer)
        }
        rememberExistingReceivedIndices(transferIdHex)

        // Chunks may have arrived before the offer: ingest them now.
        bufferedChunks.remove(transferIdHex)?.let { buffered ->
            for ((chunkIndex, ciphertext) in buffered.toSortedMap()) {
                ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
                ciphertext.fill(0)
            }
        }

        val contiguous = contiguousReceived(transferIdHex)
        sendFileAck(transferIdHex, contiguous)
        val fresh = transferDao.getTransfer(transferIdHex) ?: return
        if (contiguous >= manifest.chunkCount.toInt()) {
            finalizeTransfer(fresh, manifest)
        } else if (fresh.state == "OFFERED") {
            advance(fresh, newState = "TRANSFERRING")
        }
    }

    private suspend fun handleChunk(transferIdHex: String, chunkIndex: Int, ciphertext: ByteArray) {
        if (transferDao.getTransfer(transferIdHex) == null ||
            chunkStore.readManifest(transferIdHex) == null
        ) {
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
    }

    private fun bufferOrDropChunk(transferIdHex: String, chunkIndex: Int, ciphertext: ByteArray) {
        if (chunkIndex !in 0 until FileTransferChunkStore.MAX_CHUNKS_PER_TRANSFER) return
        val buffered = bufferedChunks.getOrPut(transferIdHex) { HashMap() }
        val existing = buffered.put(chunkIndex, ciphertext)
        if (existing != null) existing.fill(0)
        while (bufferedChunkBytes() > MAX_BUFFERED_CHUNK_BYTES) {
            val oldestTransfer = bufferedChunks.keys.firstOrNull() ?: break
            bufferedChunks.remove(oldestTransfer)?.values?.forEach { it.fill(0) }
            Log.w(TAG, "Dropped pre-offer chunk buffer for $oldestTransfer")
        }
    }

    private fun bufferedChunkBytes(): Long {
        var total = 0L
        for (chunks in bufferedChunks.values) {
            for (ciphertext in chunks.values) {
                total = Math.addExact(total, ciphertext.size.toLong())
            }
        }
        return total
    }

    private suspend fun ingestChunkCiphertext(
        transferIdHex: String,
        chunkIndex: Int,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.state == "COMPLETE" || transfer.state == "FAILED") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val manifest = crypto.parseManifest(manifestBytes)
        val chunkCount = manifest.chunkCount.toInt()
        if (chunkIndex !in 0 until chunkCount) {
            Log.w(TAG, "Chunk index $chunkIndex out of range for $transferIdHex")
            return
        }
        if (manifest.expiresAtMs <= nowMs()) {
            Log.w(TAG, "Chunk for expired transfer $transferIdHex dropped")
            return
        }
        val expectedPlaintext = plaintextLengthOf(manifest, chunkIndex)
        if (ciphertext.size != expectedPlaintext + FileTransferChunkStore.AEAD_TAG_BYTES) {
            Log.w(TAG, "Chunk $chunkIndex geometry mismatch for $transferIdHex")
            return
        }
        val received = receivedIndices.getOrPut(transferIdHex) { HashSet() }
        if (chunkIndex in received) {
            // Дубликат: повторяем ACK с текущим прогрессом — отправитель мог потерять
            // финальный ACK (рестарт/обрыв) и иначе никогда не закроет передачу.
            sendFileAck(transferIdHex, contiguousReceived(transferIdHex))
            return
        }

        val stored = try {
            chunkStore.storeEncryptedChunk(transferIdHex, chunkIndex, ciphertext)
        } catch (storeError: Exception) {
            Log.w(TAG, "Chunk $chunkIndex rejected by store for $transferIdHex: ${storeError.message}")
            return
        }
        transferDao.upsertChunk(
            FileTransferChunkEntity(
                transferId = transferIdHex,
                chunkIndex = chunkIndex,
                state = "RECEIVED",
                ciphertextBytes = stored.ciphertextBytes,
                chunkSha256 = stored.sha256,
                updatedAtMs = nowMs(),
            )
        )
        received.add(chunkIndex)
        Log.i(
            TAG,
            "File chunk stored: $chunkIndex for $transferIdHex " +
                "(${received.size}/${chunkCount}, contiguous=${contiguousReceived(transferIdHex)})",
        )

        val contiguous = contiguousReceived(transferIdHex)
        val transferredBytes = received.sumOf { index -> plaintextLengthOf(manifest, index).toLong() }
        val updated = advance(
            transfer,
            newState = if (received.size == chunkCount) "VERIFYING" else "TRANSFERRING",
            completedChunks = received.size,
            transferredBytes = transferredBytes,
        )
        if (updated == 0) return

        sendFileAck(transferIdHex, contiguous)
        if (received.size == chunkCount) {
            finalizeTransfer(transfer, manifest)
        }
    }

    private suspend fun finalizeTransfer(
        transfer: FileTransferEntity,
        manifest: FileTransferManifestFfi,
    ) {
        val transferIdHex = transfer.transferId
        // Re-fetch: the row may have advanced (e.g. VERIFYING) since the caller loaded it, and
        // the monotonic Room guard must never see regressed progress numbers.
        val fresh = transferDao.getTransfer(transferIdHex) ?: return
        if (fresh.state == "COMPLETE") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = receivedStore.openWriter(
            transferIdHex,
            manifest.displayName,
            manifest.fileSize.toLong(),
        )
        try {
            keyVault.withExistingKey(transferIdHex) { fileKey ->
                for (chunkIndex in 0 until manifest.chunkCount.toInt()) {
                    val ciphertext = chunkStore.readEncryptedChunk(transferIdHex, chunkIndex)
                        ?: throw IllegalStateException("Missing chunk $chunkIndex at finalize")
                    try {
                        val plaintext = crypto.decryptChunk(manifestBytes, fileKey, chunkIndex, ciphertext)
                        try {
                            digest.update(plaintext)
                            writer.write(plaintext, plaintext.size)
                        } finally {
                            plaintext.fill(0)
                        }
                    } finally {
                        ciphertext.fill(0)
                    }
                }
            }
            if (digest.digest().toHex() != manifest.fileSha256Hex) {
                throw IllegalStateException("Whole-file SHA-256 mismatch")
            }
            writer.commit()
            advance(fresh, newState = "COMPLETE")
            Log.i(TAG, "File transfer COMPLETE: $transferIdHex (${manifest.displayName})")
            notifier.onFileReceived(
                chatId = fresh.chatId,
                senderId = fresh.peerNodeId,
                messageId = FileTransferWire.chatPlaceholderMessageId(transferIdHex),
                displayName = manifest.displayName,
                mediaType = manifest.mediaType,
                totalBytes = manifest.fileSize.toLong(),
                fileSha256 = manifest.fileSha256Hex,
            )
            sendFileAck(transferIdHex, manifest.chunkCount.toInt())
        } catch (error: Exception) {
            // abort() is a no-op after a successful commit (Writer guards its finished state).
            writer.abort()
            runCatching { receivedStore.deleteTransfer(transferIdHex) }
            advance(fresh, newState = "FAILED", errorCode = "VERIFY_FAILED")
            Log.w(TAG, "File verification failed for $transferIdHex: ${error.message}")
        }
    }

    private suspend fun openAndImportKey(transferIdHex: String, offer: FileOfferPdu.Offer) {
        val myBinding = identity.myBinding()
            ?: throw IllegalStateException("Local file exchange binding unavailable")
        val fileKey = identity.withSecret { secret ->
            crypto.openKeyEnvelope(offer.keyEnvelope, myBinding, secret, offer.manifest)
        } ?: throw IllegalStateException("Local file exchange secret unavailable")
        try {
            keyVault.importKey(transferIdHex, fileKey)
        } finally {
            fileKey.fill(0)
        }
    }

    private fun rememberExistingReceivedIndices(transferIdHex: String) {
        if (receivedIndices.containsKey(transferIdHex)) return
        receivedIndices[transferIdHex] = chunkStore.storedChunkIndices(transferIdHex).toHashSet()
    }

    private fun contiguousReceived(transferIdHex: String): Int {
        val received = receivedIndices[transferIdHex] ?: return 0
        var contiguous = 0
        while (contiguous in received) contiguous++
        return contiguous
    }

    private fun plaintextLengthOf(manifest: FileTransferManifestFfi, chunkIndex: Int): Int = minOf(
        manifest.chunkSize.toLong(),
        manifest.fileSize.toLong() - chunkIndex.toLong() * manifest.chunkSize.toLong(),
    ).toInt()

    private suspend fun sendFileAck(transferIdHex: String, contiguousChunks: Int) {
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.ACK,
                    hexToBytes(transferIdHex),
                    contiguousChunks,
                    0,
                    1,
                    byteArrayOf(1),
                )
            )
            val transfer = transferDao.getTransfer(transferIdHex) ?: return
            transport.send(
                FileTransferWire.ackMessageId(transferIdHex, contiguousChunks),
                transfer.chatId,
                transfer.peerNodeId,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "File ACK send failed for $transferIdHex: ${error.message}")
        }
    }

    private suspend fun insertIncomingTransfer(
        manifest: FileTransferManifestFfi,
        senderId: String,
        chatId: String,
        now: Long,
    ): FileTransferEntity? {
        val entity = FileTransferEntity(
            transferId = manifest.transferIdHex,
            messageId = FileTransferWire.chatPlaceholderMessageId(manifest.transferIdHex),
            chatId = chatId,
            peerNodeId = senderId,
            direction = "INCOMING",
            displayName = manifest.displayName,
            mediaType = manifest.mediaType,
            totalBytes = manifest.fileSize.toLong(),
            chunkSize = manifest.chunkSize.toInt(),
            chunkCount = manifest.chunkCount.toInt(),
            fileSha256 = manifest.fileSha256Hex,
            state = "OFFERED",
            createdAtMs = now,
            expiresAtMs = manifest.expiresAtMs,
            updatedAtMs = now,
        )
        return if (transferDao.insertNewTransfer(entity)) entity else null
    }

    private suspend fun advance(
        transfer: FileTransferEntity,
        newState: String,
        completedChunks: Int = transfer.completedChunks,
        transferredBytes: Long = transfer.transferredBytes,
        errorCode: String? = null,
    ): Int = transferDao.advanceProgress(
        transferId = transfer.transferId,
        state = newState,
        completedChunks = completedChunks,
        transferredBytes = transferredBytes,
        updatedAtMs = nowMs(),
        errorCode = errorCode,
    )

    private fun hexToBytes(transferIdHex: String): ByteArray =
        ByteArray(16) { index -> transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Chat-level notification boundary implemented by the transport host. */
    fun interface FileChatNotifier {
        suspend fun onFileReceived(
            chatId: String,
            senderId: String,
            messageId: String,
            displayName: String,
            mediaType: String,
            totalBytes: Long,
            fileSha256: String,
        )
    }

    companion object {
        private const val TAG = "FileTransferReceiver"
        const val MAX_PENDING_ITEMS = 64
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
        const val MAX_BUFFERED_CHUNK_BYTES = 16L * 1024 * 1024
    }
}
