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
    private val ackSink: suspend (transferIdHex: String, contiguousChunks: Long) -> Unit,
    private val notifier: FileChatNotifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class PendingItem {
        val fragments: MutableMap<Int, ByteArray> = HashMap()
        var fragmentCount: Int = -1
    }

    private val mutex = Mutex()
    private val pendingItems = LinkedHashMap<String, PendingItem>()
    private val bufferedChunks = LinkedHashMap<String, MutableMap<Long, ByteArray>>()
    /** One bounded progress cursor per active transfer; never one heap entry per file chunk. */
    private val contiguousPrefixes = HashMap<String, Long>()
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
        val key = "${transferIdHex}|${packet.wireVersion}|${packet.type.wire}|${packet.itemIndex}"
        val pending = pendingItems[key] ?: PendingItem().also {
            pendingItems[key] = it
            evictOldestIfOverBudget()
        }
        check(pending.fragmentCount == -1 || pending.fragmentCount == packet.fragmentCount) {
            "Fragment count conflict for file item"
        }
        val ownedFragment = packet.payload.copyOf()
        val existing = pending.fragments.putIfAbsent(packet.fragmentIndex, ownedFragment)
        if (existing != null) {
            ownedFragment.fill(0)
            check(existing.contentEquals(packet.payload)) { "Conflicting duplicate file fragment" }
            return
        }
        pending.fragmentCount = packet.fragmentCount
        pendingBytes = Math.addExact(pendingBytes, packet.payload.size.toLong())
        evictOldestIfOverBudget()
        if (pendingItems[key] !== pending) return
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
                    packet.wireVersion,
                )
            )
        }
        releasePending(key, pending)
        val payload = FileTransferPacketCodec.reassemble(encodedFragments)
        try {
            when (packet.type) {
                FileTransferPacketCodec.Type.OFFER -> handleOffer(senderId, chatId, payload)
                FileTransferPacketCodec.Type.CHUNK -> handleChunk(transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.ACK ->
                    handleAck(senderId, transferIdHex, packet.itemIndex, payload)
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

    private suspend fun handleAck(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        if (!payload.contentEquals(byteArrayOf(1))) {
            Log.w(TAG, "Malformed file ACK payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer == null || transfer.direction != "OUTGOING" || transfer.peerNodeId != senderId) {
            Log.w(TAG, "Unauthorized file ACK from $senderId for $transferIdHex; dropped")
            return
        }
        if (contiguousChunks !in 0L..transfer.chunkCount) {
            Log.w(TAG, "Out-of-range file ACK $contiguousChunks for $transferIdHex; dropped")
            return
        }
        ackSink(transferIdHex, contiguousChunks)
    }

    private suspend fun handleOffer(senderId: String, chatId: String, payload: ByteArray) {
        val offer = FileOfferPdu.decode(payload)
        val manifest = crypto.parseManifest(offer.manifest)
        if (manifest.fileSize > Long.MAX_VALUE.toULong() ||
            manifest.chunkCount > Long.MAX_VALUE.toULong()
        ) {
            Log.w(TAG, "File offer geometry exceeds Android durable range; dropped")
            return
        }
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
        recoverDurableProgress(transferIdHex)

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
        if (contiguous >= manifest.chunkCount.toLong()) {
            finalizeTransfer(fresh, manifest)
        } else if (fresh.state == "OFFERED") {
            advance(fresh, newState = "TRANSFERRING")
        }
    }

    private suspend fun handleChunk(transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray) {
        if (transferDao.getTransfer(transferIdHex) == null ||
            chunkStore.readManifest(transferIdHex) == null
        ) {
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
    }

    private fun bufferOrDropChunk(transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray) {
        if (chunkIndex < 0L) return
        val buffered = bufferedChunks.getOrPut(transferIdHex) { LinkedHashMap() }
        // collectFragment wipes its reassembled payload after dispatch; retain an owned copy.
        val existing = buffered.put(chunkIndex, ciphertext.copyOf())
        if (existing != null) existing.fill(0)
        while (bufferedChunks.size > MAX_BUFFERED_TRANSFERS ||
            bufferedChunkCount() > MAX_BUFFERED_CHUNKS ||
            bufferedChunkBytes() > MAX_BUFFERED_CHUNK_BYTES
        ) {
            val oldestTransfer = bufferedChunks.keys.firstOrNull() ?: break
            bufferedChunks.remove(oldestTransfer)?.values?.forEach { it.fill(0) }
            Log.w(TAG, "Dropped pre-offer chunk buffer for $oldestTransfer")
        }
    }

    private fun bufferedChunkCount(): Int = bufferedChunks.values.sumOf { it.size }

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
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.state == "COMPLETE" || transfer.state == "FAILED") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val manifest = crypto.parseManifest(manifestBytes)
        val chunkCount = manifest.chunkCount.toLong()
        if (chunkIndex !in 0L until chunkCount) {
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
        val stored = try {
            chunkStore.storeEncryptedChunk(transferIdHex, chunkIndex, ciphertext)
        } catch (storeError: Exception) {
            Log.w(TAG, "Chunk $chunkIndex rejected by store for $transferIdHex: ${storeError.message}")
            return
        }
        val inserted = transferDao.insertChunkIgnore(
            FileTransferChunkEntity(
                transferId = transferIdHex,
                chunkIndex = chunkIndex,
                state = "RECEIVED",
                ciphertextBytes = stored.ciphertextBytes,
                chunkSha256 = stored.sha256,
                updatedAtMs = nowMs(),
            )
        ) != -1L
        val contiguous = advanceContiguousPrefix(transferIdHex)
        if (!inserted) {
            // Duplicate: repeat current ACK. No per-chunk in-memory set is retained.
            sendFileAck(transferIdHex, contiguous)
            return
        }

        val completedChunks = Math.addExact(transfer.completedChunks, 1L)
        val transferredBytes = Math.addExact(
            transfer.transferredBytes,
            plaintextLengthOf(manifest, chunkIndex).toLong(),
        )
        Log.i(
            TAG,
            "File chunk stored: $chunkIndex for $transferIdHex " +
                "($completedChunks/$chunkCount, contiguous=$contiguous)",
        )

        val updated = advance(
            transfer,
            newState = if (completedChunks == chunkCount) "VERIFYING" else "TRANSFERRING",
            completedChunks = completedChunks,
            transferredBytes = transferredBytes,
        )
        if (updated == 0) return

        sendFileAck(transferIdHex, contiguous)
        if (completedChunks == chunkCount) {
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
                for (chunkIndex in 0L until manifest.chunkCount.toLong()) {
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
            check(advance(fresh, newState = "COMPLETE") == 1) {
                "Cannot persist verified file completion"
            }
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
            sendFileAck(transferIdHex, manifest.chunkCount.toLong())
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

    private suspend fun recoverDurableProgress(transferIdHex: String) {
        if (contiguousPrefixes.containsKey(transferIdHex)) return
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        val completedChunks = transferDao.countChunks(transferIdHex)
        val transferredBytes = transferDao.receivedPlaintextBytes(transferIdHex)
        if (completedChunks > transfer.completedChunks || transferredBytes > transfer.transferredBytes) {
            advance(
                transfer,
                newState = transfer.state,
                completedChunks = completedChunks,
                transferredBytes = transferredBytes,
            )
        }
        contiguousPrefixes[transferIdHex] = discoverContiguousPrefix(transferIdHex)
    }

    private fun discoverContiguousPrefix(transferIdHex: String): Long {
        var contiguous = 0L
        while (chunkStore.hasEncryptedChunk(transferIdHex, contiguous)) {
            contiguous = Math.addExact(contiguous, 1L)
        }
        return contiguous
    }

    private fun advanceContiguousPrefix(transferIdHex: String): Long {
        var contiguous = contiguousPrefixes[transferIdHex]
            ?: discoverContiguousPrefix(transferIdHex)
        while (chunkStore.hasEncryptedChunk(transferIdHex, contiguous)) {
            contiguous = Math.addExact(contiguous, 1L)
        }
        contiguousPrefixes[transferIdHex] = contiguous
        return contiguous
    }

    private fun contiguousReceived(transferIdHex: String): Long =
        contiguousPrefixes[transferIdHex] ?: discoverContiguousPrefix(transferIdHex).also {
            contiguousPrefixes[transferIdHex] = it
        }

    private fun plaintextLengthOf(manifest: FileTransferManifestFfi, chunkIndex: Long): Int {
        val chunkSize = manifest.chunkSize.toLong()
        val offset = Math.multiplyExact(chunkIndex, chunkSize)
        return minOf(chunkSize, manifest.fileSize.toLong() - offset).toInt()
    }

    private suspend fun sendFileAck(transferIdHex: String, contiguousChunks: Long) {
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
            chunkCount = manifest.chunkCount.toLong(),
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
        completedChunks: Long = transfer.completedChunks,
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
        const val MAX_BUFFERED_TRANSFERS = 32
        const val MAX_BUFFERED_CHUNKS = 64
        const val MAX_BUFFERED_CHUNK_BYTES = 16L * 1024 * 1024
    }
}
