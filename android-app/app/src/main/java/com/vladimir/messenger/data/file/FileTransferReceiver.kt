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
    /** Хранение чужих файлов и приём через хранителя (этап 7 роя); по умолчанию всё отклоняется. */
    private val custody: CustodyPolicy = CustodyPolicy(),
) {
    /**
     * Границы политики хранения у третьего телефона. Приёмник сам решает
     * только про подлинность и геометрию; «кого пускать», «сколько места»
     * и «в какой чат» приходят снаружи, чтобы JVM-тесты жили без Android.
     */
    class CustodyPolicy(
        /** Берём ли на хранение файл этого отправителя (в приложении - он наш контакт). */
        val acceptsFrom: suspend (originId: String) -> Boolean = { false },
        /** Сколько байт ещё можно записать в хранилище кусков (квота «Место под пересылку»). */
        val headroomBytes: () -> Long = { 0L },
        /** Чат с отправителем пересланного файла на ЭТОМ телефоне; null - отправитель не контакт. */
        val chatIdFor: suspend (originId: String) -> String? = { null },
        /** Подтверждение хранителя моей исходящей передаче (см. [FileCustodySender.onCustodianAck]). */
        val onCustodianAck: suspend (transferIdHex: String, from: String, contiguous: Long, status: Byte) -> Unit =
            { _, _, _, _ -> },
        /** Подтверждение получателя хранимой у меня передаче (см. [FileCustodySender.onRecipientAck]). */
        val onRecipientAck: suspend (transferIdHex: String, from: String, contiguous: Long, status: Byte) -> Unit =
            { _, _, _, _ -> },
    )

    private class PendingItem {
        val fragments: MutableMap<Int, ByteArray> = HashMap()
        var fragmentCount: Int = -1
    }

    private val mutex = Mutex()
    private val pendingItems = LinkedHashMap<String, PendingItem>()
    private val bufferedChunks = LinkedHashMap<String, MutableMap<Long, ByteArray>>()
    /** One bounded progress cursor per active transfer; never one heap entry per file chunk. */
    private val contiguousPrefixes = HashMap<String, Long>()
    /**
     * Передачи через хранителя, по которым отправитель объявился и напрямую:
     * тогда подтверждения нужны обоим - хранителю (чтобы освободил место) и
     * отправителю (его окно двигают только обычные ACK).
     */
    private val directFromOrigin = HashSet<String>()
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
                // Самый частый случай - собеседник переустановил приложение и
                // прислал НОВЫЙ ключ, а у нас закреплён старый. Пин намеренно
                // не сбрасывается сам: так подмену ключа не отличить от
                // переустановки. Лечится пересканированием QR собеседника,
                // поэтому подсказка пишется прямо здесь.
                val changed = error.message?.contains("key changed") == true
                if (changed) {
                    Log.w(
                        TAG,
                        "File HELLO from $senderId REJECTED: exchange key changed " +
                            "(peer reinstalled?). Messages to this peer cannot be sealed " +
                            "until the QR code is scanned again.",
                    )
                } else {
                    Log.w(TAG, "File HELLO from $senderId rejected: ${error.message}")
                }
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
                FileTransferPacketCodec.Type.CHUNK -> handleChunk(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.ACK ->
                    handleAck(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.CANCEL ->
                    Log.i(TAG, "File transfer CANCEL notice for $transferIdHex")
                FileTransferPacketCodec.Type.CUSTODY_OFFER -> handleCustodyOffer(senderId, payload)
                FileTransferPacketCodec.Type.CUSTODY_CHUNK ->
                    handleCustodyChunk(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.CUSTODY_ACK ->
                    handleCustodyAck(senderId, transferIdHex, packet.itemIndex, payload)
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
        if (transfer.custodianNodeId.isNotBlank()) directFromOrigin.add(transferIdHex)

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

    private suspend fun handleChunk(
        senderId: String,
        transferIdHex: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer == null || chunkStore.readManifest(transferIdHex) == null) {
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        if (transfer.direction == "CUSTODY") {
            // Обычные куски чужой передаче не адресуются: хранитель принимает
            // только CUSTODY_CHUNK от отправителя.
            Log.w(TAG, "Plain chunk for custody transfer $transferIdHex from ${senderId.takeLast(8)}; dropped")
            return
        }
        // Кусок пришёл напрямую от отправителя, хотя файл идёт и через
        // хранителя: с этого момента подтверждаем обоим (см. sendFileAck).
        if (transfer.direction == "INCOMING" && transfer.custodianNodeId.isNotBlank() &&
            senderId == transfer.peerNodeId
        ) {
            directFromOrigin.add(transferIdHex)
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
        if (transfer.state == "COMPLETE") {
            // Отправитель не получил итогового подтверждения и шлёт снова:
            // повторяем его (id детерминирован, сеть отсеет дубли), иначе
            // он будет качать в пустоту до конца срока.
            sendFileAck(transferIdHex, transfer.chunkCount)
            return
        }
        if (transfer.state == "FAILED") return
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
            // Место под пересылку кончилось: передача не падает (отправитель
            // повторит кусок, а владелец может подвинуть ползунок или
            // очистить завершённые), но пузырь в чате должен сказать, чего
            // ждём. Следующий удачный кусок снимает пометку сам (advance
            // без errorCode).
            if (storeError is FileTransferChunkStore.StorageFullException &&
                transfer.errorCode != ERROR_NO_SPACE
            ) {
                advance(transfer, newState = transfer.state, errorCode = ERROR_NO_SPACE)
            }
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
            directFromOrigin.remove(transferIdHex)
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
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        // Приём через хранителя: окно двигает подтверждение ЕМУ, а
        // отправителю (он, скорее всего, не в сети - потому и хранитель)
        // уходит только итоговое, чтобы не забивать очередь ретранслятора
        // сотней мелких подтверждений.
        if (transfer.direction == "INCOMING" && transfer.custodianNodeId.isNotBlank()) {
            sendCustodyAck(transfer.custodianNodeId, transferIdHex, contiguousChunks, FileCustodyPdu.ACK_OK)
            if (contiguousChunks < transfer.chunkCount && transferIdHex !in directFromOrigin) return
        }
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

    // ── Хранение у третьего телефона (этап 7 роя) ──────────────────────────

    private suspend fun handleCustodyOffer(senderId: String, payload: ByteArray) {
        val custodyOffer = FileCustodyPdu.decode(payload)
        val me = identity.myNodeId() ?: return
        if (custodyOffer.recipientId == me) {
            handleForwardedOffer(senderId, custodyOffer)
        } else {
            handleCustodyRequest(senderId, custodyOffer)
        }
    }

    /**
     * Хранитель переслал мне чужой файл: проверяем ровно как прямое
     * предложение, только отправитель - [FileCustodyPdu.Custody.originId], а
     * не тот, от кого пришёл пакет. Конверт с ключом запечатан отправителем
     * для меня, хранитель его вскрыть не мог.
     */
    private suspend fun handleForwardedOffer(custodianId: String, custodyOffer: FileCustodyPdu.Custody) {
        val manifest = crypto.parseManifest(custodyOffer.manifest)
        val transferIdHex = manifest.transferIdHex
        val originId = custodyOffer.originId
        val now = nowMs()
        val me = identity.myNodeId() ?: return
        suspend fun refuse(reason: String) {
            Log.w(TAG, "Forwarded file offer $transferIdHex via ${custodianId.takeLast(8)} refused: $reason")
            sendCustodyAck(custodianId, transferIdHex, 0L, FileCustodyPdu.ACK_REFUSED)
        }
        if (manifest.fileSize > Long.MAX_VALUE.toULong() || manifest.chunkCount > Long.MAX_VALUE.toULong()) {
            refuse("geometry"); return
        }
        if (manifest.senderNodeId != originId || manifest.recipientNodeId != me) {
            refuse("manifest parties mismatch"); return
        }
        if (manifest.expiresAtMs <= now) {
            refuse("expired"); return
        }
        if (!crypto.verifyBinding(custodyOffer.senderBinding) ||
            crypto.bindingNodeId(custodyOffer.senderBinding) != originId
        ) {
            refuse("origin binding"); return
        }
        val existing = transferDao.getTransfer(transferIdHex)
        if (existing != null && (existing.direction != "INCOMING" || existing.peerNodeId != originId)) {
            refuse("conflicts with local transfer row"); return
        }
        if (existing?.state == "FAILED") {
            refuse("already failed"); return
        }
        if (existing?.state == "COMPLETE") {
            // Уже всё есть: хранителю достаточно знать, что можно удалять.
            sendCustodyAck(custodianId, transferIdHex, existing.chunkCount, FileCustodyPdu.ACK_OK)
            return
        }
        // Чужой файл от не-контакта не принимаем - и ключ чужака не
        // закрепляем: проверка знакомства раньше закрепления.
        val chatId = existing?.chatId ?: custody.chatIdFor(originId)
        if (chatId == null) {
            refuse("origin is not a contact"); return
        }
        try {
            pinner.pinFirstSeen(custodyOffer.senderBinding, now)
        } catch (pinError: Exception) {
            refuse("pinned exchange key changed for $originId (${pinError.message})"); return
        }
        if (existing == null && insertIncomingTransfer(manifest, originId, chatId, now) == null) return
        transferDao.setCustodian(transferIdHex, custodianId, now)
        Log.i(
            TAG,
            "Forwarded file offer accepted: $transferIdHex from ${originId.takeLast(8)} " +
                "via ${custodianId.takeLast(8)} (${manifest.displayName}, ${manifest.fileSize} B)",
        )
        chunkStore.storeManifest(transferIdHex, custodyOffer.manifest)
        chunkStore.storeKeyEnvelope(transferIdHex, custodyOffer.keyEnvelope)
        if (keyVault.mode(transferIdHex) != FileTransferKeyVault.Mode.READY) {
            openAndImportKey(transferIdHex, custodyOffer.asOffer())
        }
        recoverDurableProgress(transferIdHex)
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

    /**
     * Отправитель просит подержать файл для получателя, который не в сети.
     * Берём, если отправитель - наш контакт, лимиты не выбраны и место есть;
     * иначе отвечаем отказом или «полон», и он идёт к следующему кандидату.
     */
    private suspend fun handleCustodyRequest(senderId: String, custodyOffer: FileCustodyPdu.Custody) {
        val manifest = crypto.parseManifest(custodyOffer.manifest)
        val transferIdHex = manifest.transferIdHex
        val now = nowMs()
        suspend fun answer(status: Byte, reason: String) {
            Log.i(TAG, "Custody request $transferIdHex from ${senderId.takeLast(8)}: $reason")
            sendCustodyAck(senderId, transferIdHex, 0L, status)
        }
        if (custodyOffer.originId != senderId) {
            answer(FileCustodyPdu.ACK_REFUSED, "origin is not the sender"); return
        }
        if (manifest.fileSize > Long.MAX_VALUE.toULong() || manifest.chunkCount > Long.MAX_VALUE.toULong()) {
            answer(FileCustodyPdu.ACK_REFUSED, "geometry"); return
        }
        if (manifest.senderNodeId != senderId || manifest.recipientNodeId != custodyOffer.recipientId) {
            answer(FileCustodyPdu.ACK_REFUSED, "manifest parties mismatch"); return
        }
        if (manifest.expiresAtMs <= now) {
            answer(FileCustodyPdu.ACK_REFUSED, "expired"); return
        }
        if (!crypto.verifyBinding(custodyOffer.senderBinding) ||
            crypto.bindingNodeId(custodyOffer.senderBinding) != senderId
        ) {
            answer(FileCustodyPdu.ACK_REFUSED, "sender binding"); return
        }
        if (!custody.acceptsFrom(senderId)) {
            answer(FileCustodyPdu.ACK_REFUSED, "not a contact"); return
        }
        try {
            pinner.pinFirstSeen(custodyOffer.senderBinding, now)
        } catch (pinError: Exception) {
            answer(FileCustodyPdu.ACK_REFUSED, "pinned exchange key changed (${pinError.message})"); return
        }
        val existing = transferDao.getTransfer(transferIdHex)
        if (existing != null) {
            if (existing.direction != "CUSTODY" || existing.originNodeId != senderId ||
                existing.peerNodeId != custodyOffer.recipientId
            ) {
                answer(FileCustodyPdu.ACK_REFUSED, "conflicts with local transfer row"); return
            }
            // Повтор предложения: напоминаем, сколько уже держим.
            recoverDurableProgress(transferIdHex)
            sendCustodyAck(senderId, transferIdHex, contiguousReceived(transferIdHex), FileCustodyPdu.ACK_OK)
            return
        }
        if (transferDao.countActiveCustody() >= FileCustodySender.MAX_CUSTODY_TRANSFERS) {
            answer(FileCustodyPdu.ACK_FULL, "too many files held"); return
        }
        if (transferDao.countActiveCustodyFrom(senderId) >= FileCustodySender.MAX_CUSTODY_PER_ORIGIN) {
            answer(FileCustodyPdu.ACK_FULL, "too many files held for this sender"); return
        }
        val needed = Math.addExact(
            manifest.fileSize.toLong(),
            Math.multiplyExact(manifest.chunkCount.toLong(), FileTransferChunkStore.AEAD_TAG_BYTES.toLong()),
        )
        if (needed > custody.headroomBytes()) {
            answer(FileCustodyPdu.ACK_FULL, "no space ($needed B needed)"); return
        }
        val entity = FileTransferEntity(
            transferId = transferIdHex,
            messageId = "custody-$transferIdHex",
            chatId = FileTransferChatRouting.CUSTODY_SCOPE,
            peerNodeId = custodyOffer.recipientId,
            direction = "CUSTODY",
            displayName = manifest.displayName,
            mediaType = manifest.mediaType,
            totalBytes = manifest.fileSize.toLong(),
            chunkSize = manifest.chunkSize.toInt(),
            chunkCount = manifest.chunkCount.toLong(),
            fileSha256 = manifest.fileSha256Hex,
            state = "HOLDING",
            createdAtMs = now,
            expiresAtMs = minOf(manifest.expiresAtMs, Math.addExact(now, FileCustodySender.CUSTODY_TTL_MS)),
            updatedAtMs = now,
            originNodeId = senderId,
        )
        if (!transferDao.insertNewTransfer(entity)) {
            answer(FileCustodyPdu.ACK_REFUSED, "row collision"); return
        }
        chunkStore.storeManifest(transferIdHex, custodyOffer.manifest)
        chunkStore.storeKeyEnvelope(transferIdHex, custodyOffer.keyEnvelope)
        chunkStore.storeOriginBinding(transferIdHex, custodyOffer.senderBinding)
        recoverDurableProgress(transferIdHex)
        Log.i(
            TAG,
            "Custody accepted: $transferIdHex from ${senderId.takeLast(8)} " +
                "for ${custodyOffer.recipientId.takeLast(8)} (${manifest.fileSize} B, ${manifest.chunkCount} chunks)",
        )
        bufferedChunks.remove(transferIdHex)?.let { buffered ->
            for ((chunkIndex, ciphertext) in buffered.toSortedMap()) {
                ingestCustodyChunk(transferIdHex, chunkIndex, ciphertext)
                ciphertext.fill(0)
            }
        }
        sendCustodyAck(senderId, transferIdHex, contiguousReceived(transferIdHex), FileCustodyPdu.ACK_OK)
    }

    private suspend fun handleCustodyChunk(
        senderId: String,
        transferIdHex: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer == null || chunkStore.readManifest(transferIdHex) == null) {
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        when (transfer.direction) {
            "CUSTODY" -> {
                if (transfer.originNodeId != senderId) {
                    Log.w(TAG, "Custody chunk for $transferIdHex from a stranger ${senderId.takeLast(8)}; dropped")
                    return
                }
                ingestCustodyChunk(transferIdHex, chunkIndex, ciphertext)
            }
            "INCOMING" -> {
                if (transfer.custodianNodeId != senderId) {
                    // Хранитель ещё не представился предложением (или это не он).
                    Log.w(TAG, "Forwarded chunk for $transferIdHex from unexpected ${senderId.takeLast(8)}; dropped")
                    return
                }
                ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
            }
            else -> Log.w(TAG, "Custody chunk for own outgoing $transferIdHex; dropped")
        }
    }

    /** Кусок чужого файла на хранение: только геометрия и место, содержимое нам недоступно. */
    private suspend fun ingestCustodyChunk(transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.direction != "CUSTODY" || transfer.state == "COMPLETE") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val manifest = crypto.parseManifest(manifestBytes)
        val chunkCount = manifest.chunkCount.toLong()
        if (chunkIndex !in 0L until chunkCount) {
            Log.w(TAG, "Custody chunk index $chunkIndex out of range for $transferIdHex")
            return
        }
        if (transfer.expiresAtMs <= nowMs()) return
        val expectedPlaintext = plaintextLengthOf(manifest, chunkIndex)
        if (ciphertext.size != expectedPlaintext + FileTransferChunkStore.AEAD_TAG_BYTES) {
            Log.w(TAG, "Custody chunk $chunkIndex geometry mismatch for $transferIdHex")
            return
        }
        val stored = try {
            chunkStore.storeEncryptedChunk(transferIdHex, chunkIndex, ciphertext)
        } catch (storeError: Exception) {
            Log.w(TAG, "Custody chunk $chunkIndex rejected by store for $transferIdHex: ${storeError.message}")
            if (storeError is FileTransferChunkStore.StorageFullException) {
                // Места нет: честно говорим «полон» и освобождаем начатое -
                // отправитель уйдёт к следующему хранителю.
                sendCustodyAck(transfer.originNodeId, transferIdHex, 0L, FileCustodyPdu.ACK_FULL)
                runCatching { chunkStore.deleteTransfer(transferIdHex) }
                transferDao.deleteTransfer(transferIdHex)
                contiguousPrefixes.remove(transferIdHex)
            }
            return
        }
        val inserted = transferDao.insertChunkIgnore(
            FileTransferChunkEntity(
                transferId = transferIdHex,
                chunkIndex = chunkIndex,
                state = "HELD",
                ciphertextBytes = stored.ciphertextBytes,
                chunkSha256 = stored.sha256,
                updatedAtMs = nowMs(),
            )
        ) != -1L
        val contiguous = advanceContiguousPrefix(transferIdHex)
        if (!inserted) {
            sendCustodyAck(transfer.originNodeId, transferIdHex, contiguous, FileCustodyPdu.ACK_OK)
            return
        }
        val completedChunks = Math.addExact(transfer.completedChunks, 1L)
        val transferredBytes = Math.addExact(transfer.transferredBytes, expectedPlaintext.toLong())
        advance(
            transfer,
            newState = transfer.state,
            completedChunks = completedChunks,
            transferredBytes = transferredBytes,
        )
        if (completedChunks == chunkCount) {
            Log.i(TAG, "Custody complete: $transferIdHex ($chunkCount chunks) held for ${transfer.peerNodeId.takeLast(8)}")
        }
        // Подтверждение на каждый кусок, как у прямого приёма: id
        // детерминирован, а транспорт переключается на прямой канал, когда
        // он есть (SwitchingPacketTransport), так что очередь ретранслятора
        // это не нагружает.
        sendCustodyAck(transfer.originNodeId, transferIdHex, contiguous, FileCustodyPdu.ACK_OK)
    }

    private suspend fun handleCustodyAck(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        val status = FileCustodyPdu.ackStatus(payload)
        if (status == null) {
            Log.w(TAG, "Malformed custody ACK payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (contiguousChunks !in 0L..transfer.chunkCount) {
            Log.w(TAG, "Out-of-range custody ACK $contiguousChunks for $transferIdHex; dropped")
            return
        }
        when (transfer.direction) {
            "OUTGOING" -> custody.onCustodianAck(transferIdHex, senderId, contiguousChunks, status)
            "CUSTODY" -> if (transfer.peerNodeId == senderId) {
                custody.onRecipientAck(transferIdHex, senderId, contiguousChunks, status)
            }
            else -> Log.w(TAG, "Custody ACK for incoming $transferIdHex from ${senderId.takeLast(8)}; dropped")
        }
    }

    private suspend fun sendCustodyAck(to: String, transferIdHex: String, contiguousChunks: Long, status: Byte) {
        val me = identity.myNodeId() ?: return
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.CUSTODY_ACK,
                    hexToBytes(transferIdHex),
                    contiguousChunks,
                    0,
                    1,
                    byteArrayOf(status),
                )
            )
            transport.send(
                FileTransferWire.custodyAckMessageId(transferIdHex, me, contiguousChunks),
                FileTransferChatRouting.CUSTODY_SCOPE,
                to,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "Custody ACK send failed for $transferIdHex: ${error.message}")
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
        /** errorCode строки передачи, пока приём стоит из-за квоты «Место под пересылку». */
        const val ERROR_NO_SPACE = "NO_SPACE"
        const val MAX_PENDING_ITEMS = 64
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
        const val MAX_BUFFERED_TRANSFERS = 32
        const val MAX_BUFFERED_CHUNKS = 64
        const val MAX_BUFFERED_CHUNK_BYTES = 16L * 1024 * 1024
    }
}
