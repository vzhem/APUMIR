package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production sender-side owner for prepared direct file transfers (F3).
 *
 * Pumping is windowed against receiver file-ACKs so a phone-owned relay queue never carries an
 * unbounded fan of a large file: offer first, then at most [MAX_INFLIGHT_MESSAGES] chunk-fragment
 * messages beyond the receiver-confirmed contiguous chunk prefix. Everything rides the existing
 * durable text transport, so multi-day offline custody, restart resume and mesh dedup come from
 * the M8 machinery itself; packet message IDs are deterministic, making re-pumps idempotent.
 *
 * Honest states: PREPARED -> TRANSFERRING -> SENT (all packets handed to transport at least
 * once) -> COMPLETE (receiver's final file ACK). Transfers never claim SENT for delivery itself.
 */
class FileTransferSender(
    private val transferDao: FileTransferDao,
    private val chunkStore: FileTransferChunkStore,
    private val transport: PacketTransport,
    private val ownBindingProvider: () -> ByteArray?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Параллельный QUIC-поток: (recipientId, payload) -> true если доставлено напрямую. */
    val directTransport: ((String, String) -> Boolean)? = null,
) {
    /** Получатель офлайн — файл ждёт когда он появится (только прямая доставка). */
    class RecipientOfflineException(val transferId: String) : Exception("Recipient offline")

    data class PumpSummary(
        val transfersPumped: Int,
        val packetsSent: Int,
        val failures: Int,
    )

    private val mutex = Mutex()
    private val ackedContiguous = ConcurrentHashMap<String, Long>()
    private val lastPumpAt = ConcurrentHashMap<String, Long>()
    private val lastPumpAcked = ConcurrentHashMap<String, Long>()

    /**
     * Receiver file-ACK: remembers the confirmed contiguous prefix (window advance) and, when the
     * receiver confirms the whole file, flips the transfer COMPLETE. The local chunk copies stay
     * on disk until TTL cleanup; no SENT/DELIVERED claim is inferred from transport accepts.
     */
    suspend fun onReceiverAck(transferIdHex: String, contiguousChunks: Long) {
        FileTransferWire.requireValidTransferId(transferIdHex)
        if (contiguousChunks < 0L) return
        mutex.withLock {
            val transfer = transferDao.getTransfer(transferIdHex) ?: return
            if (transfer.direction != "OUTGOING" || transfer.state == "COMPLETE") return
            if (contiguousChunks > transfer.chunkCount) return
            val current = ackedContiguous[transferIdHex] ?: 0L
            if (contiguousChunks > current) {
                ackedContiguous[transferIdHex] = contiguousChunks
            }
            if (contiguousChunks == transfer.chunkCount) {
                advance(transfer, newState = "COMPLETE")
                Log.i(TAG, "File transfer COMPLETE by receiver ACK: $transferIdHex")
            }
        }
    }

    suspend fun pumpOnce(): PumpSummary = mutex.withLock {
        var pumped = 0
        var packets = 0
        var failures = 0
        val now = nowMs()
        for (transfer in transferDao.getActiveOutgoing(now)) {
            if (!shouldPumpNow(transfer.transferId, now)) continue
            lastPumpAt[transfer.transferId] = now
            lastPumpAcked[transfer.transferId] = ackedContiguous[transfer.transferId] ?: 0L
            val result = runCatching { pumpTransfer(transfer) }
            result.getOrNull()?.let { pumped++; packets += it }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is RecipientOfflineException) {
                    advance(transfer, newState = "WAITING_RECIPIENT")
                    Log.i(TAG, "Transfer ${transfer.transferId} waiting for recipient online")
                } else {
                    failures++
                    Log.w(TAG, "File transfer pump failed for ${transfer.transferId}: ${error?.message}")
                }
            }
        }
        return PumpSummary(pumped, packets, failures)
    }

    private suspend fun pumpTransfer(transfer: FileTransferEntity): Int {
        val transferIdHex = transfer.transferId
        val manifestBytes = chunkStore.readManifest(transferIdHex)
            ?: throw IllegalStateException("Manifest missing for $transferIdHex")
        val keyEnvelope = chunkStore.readKeyEnvelope(transferIdHex)
            ?: throw IllegalStateException("Key envelope missing for $transferIdHex")
        val ownBinding = ownBindingProvider()
            ?: throw IllegalStateException("Local file exchange binding unavailable")
        var sentPackets = 0

        // 1) Offer (idempotent message IDs; mesh dedups re-pushed fragments).
        sentPackets += sendItem(
            FileTransferPacketCodec.Type.OFFER,
            transferIdHex,
            itemIndex = 0L,
            payload = FileOfferPdu.encode(manifestBytes, keyEnvelope, ownBinding),
            messageIdFor = { fragment -> FileTransferWire.offerMessageId(transferIdHex, fragment) },
            transfer = transfer,
        )

        // 2) Windowed chunks beyond the receiver-confirmed contiguous prefix.
        val acked = ackedContiguous[transferIdHex] ?: 0L
        if (transfer.chunkCount > 0L && acked >= transfer.chunkCount) return sentPackets
        val fragmentsPerChunk = maxOf(
            1,
            (transfer.chunkSize + FileTransferChunkStore.AEAD_TAG_BYTES +
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES,
        )
        // Гигабайтные файлы: большие чанки (≥1 МиБ) получают расширенное окно —
        // данные льются потоком, а не по капле; мелкие остаются консервативными.
        val inflightBudget = if (transfer.chunkSize >= LARGE_CHUNK_BYTES) {
            MAX_INFLIGHT_MESSAGES_LARGE
        } else {
            MAX_INFLIGHT_MESSAGES
        }
        val windowChunks = maxOf(1, inflightBudget / fragmentsPerChunk)
        val windowEnd = minOf(transfer.chunkCount, acked + windowChunks.toLong())
        for (chunkIndex in acked until windowEnd) {
            val ciphertext = chunkStore.readEncryptedChunk(transferIdHex, chunkIndex)
                ?: throw IllegalStateException("Chunk $chunkIndex missing for $transferIdHex")
            try {
                sentPackets += sendItem(
                    FileTransferPacketCodec.Type.CHUNK,
                    transferIdHex,
                    itemIndex = chunkIndex,
                    payload = ciphertext,
                    messageIdFor = { fragment ->
                        FileTransferWire.chunkMessageId(transferIdHex, chunkIndex, fragment)
                    },
                    transfer = transfer,
                )
            } finally {
                ciphertext.fill(0)
            }
        }

        val targetState = if (windowEnd >= transfer.chunkCount) "SENT" else "TRANSFERRING"
        if (transfer.state != targetState || targetState == "TRANSFERRING") {
            advance(transfer, newState = targetState, errorCode = transfer.errorCode)
        }
        return sentPackets
    }

    private suspend fun sendItem(
        type: FileTransferPacketCodec.Type,
        transferIdHex: String,
        itemIndex: Long,
        payload: ByteArray,
        messageIdFor: (Int) -> String,
        transfer: FileTransferEntity,
    ): Int {
        val transferId = hexToBytes(transferIdHex)
        val fragments = FileTransferPacketCodec.fragment(type, transferId, itemIndex, payload)
        for ((fragmentIndex, encodedFragment) in fragments.withIndex()) {
            val text = FileTransferWire.encodeEncodedPacket(encodedFragment)
            // Параллельный QUIC-поток: сначала пробуем БЕЗ relay queue (быстро, без
            // блокировки текстовой очереди); если получатель недоступен напрямую —
            // обычный sendMessage (durable relay, медленно но надёжно).
            if (directTransport != null) {
                val directOk = directTransport.invoke(transfer.peerNodeId, text)
                if (!directOk) {
                    Log.i(TAG, "Recipient not directly reachable — pausing transfer")
                    throw RecipientOfflineException(transfer.transferId)
                }
            } else {
                // Direct transport not configured (tests) — use regular path
                transport.send(messageIdFor(fragmentIndex), transfer.chatId, transfer.peerNodeId, text)
            }
        }
        return fragments.size
    }

    private suspend fun advance(
        transfer: FileTransferEntity,
        newState: String,
        errorCode: String? = null,
    ) {
        val updated = transferDao.advanceProgress(
            transferId = transfer.transferId,
            state = newState,
            completedChunks = transfer.completedChunks,
            transferredBytes = transfer.transferredBytes,
            updatedAtMs = nowMs(),
            errorCode = errorCode,
        )
        if (updated != 1) {
            Log.w(TAG, "State flip to $newState rejected for ${transfer.transferId}")
        }
    }

    private fun shouldPumpNow(transferIdHex: String, now: Long): Boolean {
        val last = lastPumpAt[transferIdHex] ?: return true
        val acked = ackedContiguous[transferIdHex] ?: 0L
        if (acked > 0L && lastPumpAcked[transferIdHex] != acked) return true
        return now - last >= REPUMP_INTERVAL_MS
    }

    private fun hexToBytes(transferIdHex: String): ByteArray =
        ByteArray(16) { index ->
            transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    companion object {
        private const val TAG = "FileTransferSender"
        const val MAX_INFLIGHT_MESSAGES = 120
        const val MAX_INFLIGHT_MESSAGES_LARGE = 360
        const val LARGE_CHUNK_BYTES = 1024 * 1024
        // 30s: re-pumps are cheap (deterministic IDs, local dedup) and lossy channels open
        // short windows — the 2-minute cadence kept transfers waiting far longer than needed.
        const val REPUMP_INTERVAL_MS = 30_000L
    }
}
