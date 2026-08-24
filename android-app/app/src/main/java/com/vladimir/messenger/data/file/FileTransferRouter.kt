package com.vladimir.messenger.data.file

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade the service layer talks to: routes incoming packet/handshake texts (before they are
 * stored as chat messages) and drives the sender pump. Everything rides the existing durable
 * Rust transport, so multi-day offline custody and restart resume come from the M8 machinery.
 *
 * File-HELLO handshake: contacts without a pinned exchange binding automatically receive a tiny
 * signed HELLO (durable, deterministic per-pair message ID, throttled); a first-time pin
 * auto-replies, so two phones that never exchanged files still end up with both directions
 * pinned and the first real transfer can go through.
 */
@Singleton
class FileTransferRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val transferDao: FileTransferDao,
    private val peerStore: FileExchangePeerStore,
    private val chatRepository: ChatRepository,
) {
    private val appContext: Context
    private val sender: FileTransferSender
    private val receiver: FileTransferReceiver
    private val transport: PacketTransport
    private lateinit var lanChannel: LanDirectChannel
    private lateinit var chunkStore: FileTransferChunkStore
    private val receivedStore: ReceivedFileStore
    private val lastHelloAt = HashMap<String, Long>()

    init {
        appContext = context.applicationContext
        chunkStore = FileTransferChunkStore.forApplication(appContext)
        val receivedStoreLocal = ReceivedFileStore(File(appContext.noBackupFilesDir, "file_received/v1"))
        receivedStore = receivedStoreLocal
        val transportLocal: PacketTransport = RustPacketTransport()
        val lan = LanDirectChannel.get()
        lan.myNodeId = RustBridge.nodeId() ?: ""
        lan.onDiagnostic = { message -> Log.i(TAG, message) }
        lan.incomingRoute = { senderId, chatId, messageId, text ->
            routeIncoming(senderId, chatId, messageId, text)
        }
        lanChannel = lan
        val switchingTransport: PacketTransport = SwitchingPacketTransport(transportLocal, lan)
        transport = switchingTransport
        val crypto: FileCryptoGateway = FfiFileCryptoGateway()
        val identity: LocalExchangeIdentity = AndroidLocalExchangeIdentity(appContext)
        val keyVault: TransferKeyVaultAccess = AndroidTransferKeyVaultAccess(appContext)
        val notifier = FileTransferReceiver.FileChatNotifier(
            { chatId, senderId, messageId, displayName, mediaType, totalBytes, _ ->
                chatRepository.saveIncomingMessage(
                    chatId = chatId,
                    senderId = senderId,
                    messageId = messageId,
                    content = formatPlaceholder(displayName, mediaType, totalBytes),
                    timestamp = System.currentTimeMillis(),
                    recipientId = RustBridge.nodeId() ?: "",
                )
            },
        )
        val senderLocal = FileTransferSender(
            transferDao = transferDao,
            chunkStore = chunkStore,
            transport = switchingTransport,
            ownBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            directTransport = { recipientId, payload ->
                try {
                    com.vladimir.messenger.data.RustBridge.sendDirectPayload(recipientId, payload)
                } catch (_: Exception) {
                    false
                }
            },
        )
        sender = senderLocal
        receiver = FileTransferReceiver(
            transferDao = transferDao,
            chunkStore = chunkStore,
            receivedStore = receivedStoreLocal,
            pinner = { binding, pinnedAtMs ->
                peerStore.pinFirstSeen(binding, pinnedAtMs).newlyPinned
            },
            crypto = crypto,
            keyVault = keyVault,
            identity = identity,
            transport = switchingTransport,
            ackSink = { transferIdHex, contiguousChunks ->
                senderLocal.onReceiverAck(transferIdHex, contiguousChunks)
                // ACK progress immediately opens the next bounded window. The periodic job is
                // only a restart/offline retry safety net, never a throughput throttle.
                senderLocal.pumpOnce()
            },
            notifier = notifier,
        )
        // LAN server starts only after sender/receiver exist: an early incoming
        // frame must never hit a half-constructed router.
        lan.startServer()
    }

    /** True when the text was a file packet/handshake; the caller must skip chat-text handling. */
    suspend fun routeIncoming(senderId: String, chatId: String, messageId: String, text: String): Boolean {
        if (LanDirectChannel.isLanSignalText(text)) {
            handleLanSignal(senderId, text)
            return true
        }
        if (FileTransferWire.isHelloText(text)) {
            when (receiver.onHelloText(senderId, text)) {
                FileTransferReceiver.HelloResult.PINNED_NEW -> {
                    runCatching { sendHello(senderId, force = true) }
                        .onFailure { Log.w(TAG, "File HELLO auto-reply failed: ${it.message}") }
                }
                else -> Unit
            }
            return true
        }
        if (!FileTransferWire.isFilePacketText(text)) return false

        // Chat UUIDs are device-local. In particular, direct QUIC frames carry the explicit
        // "direct" transport scope rather than a remote chat UUID. Resolve it to THIS phone's
        // chat before the receiver writes transfer state; never let the sentinel reach Room.
        val localChatId = runCatching { chatRepository.getChatByContactId(senderId)?.id }
            .onFailure { Log.w(TAG, "Cannot resolve local chat for incoming file packet: ${it.message}") }
            .getOrNull()
        val resolvedChatId = FileTransferChatRouting.resolve(chatId, localChatId)
        if (resolvedChatId == null) {
            Log.w(TAG, "Direct file packet dropped: no local chat for sender ${senderId.takeLast(8)}")
            return true
        }
        if (chatId == FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE) {
            Log.i(TAG, "Direct file packet routed to local chat $resolvedChatId")
        }
        return receiver.onIncomingText(senderId, resolvedChatId, messageId, text)
    }

    /**
     * F4-F v1 LAN signalling over the mesh: "APULAN1|req" is answered with
     * "APULAN1|offer|<lan-ip>|<port>" so the peer can open a direct socket.
     */
    private suspend fun handleLanSignal(senderId: String, text: String) {
        val endpoint = lanChannel.parseOfferText(text)
        if (endpoint != null) {
            lanChannel.onOfferReceived(senderId, endpoint)
            return
        }
        if (!lanChannel.isRequestText(text)) return
        val offer = lanChannel.buildOfferText()
        if (offer == null) {
            Log.w(TAG, "LAN request from $senderId but no local Wi-Fi endpoint found")
            return
        }
        Log.i(TAG, "LAN request from $senderId, replying with offer $offer")
        runCatching {
            transport.send(
                "lan-" + System.nanoTime(),
                FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                senderId,
                offer,
            )
        }.onFailure { Log.w(TAG, "LAN offer reply failed: ${it.message}") }
        // The request carried the requester's own LAN endpoint: deliver the
        // offer straight to the sender's socket as well, so channel setup does
        // not depend on the (slow, chunk-flooded) mesh in either direction.
        val requester = lanChannel.parseRequestEndpoint(text)
        if (requester != null) {
            val host = requester.address?.hostAddress ?: requester.hostString
            if (host != null) {
                runCatching {
                    lanChannel.sendSignalFrame(host, requester.port, FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE, offer)
                }.onSuccess { delivered ->
                    Log.i(TAG, "LAN offer socket delivery to $host:${requester.port} ok=$delivered")
                }
            }
        }
    }

    /**
     * Получатель появился: возобновить все передачи, которые его ждали.
     */
    suspend fun resumeWaitingForRecipient() {
        val resumed = transferDao.resumeAllWaitingRecipient(System.currentTimeMillis())
        if (resumed > 0) {
            Log.i(TAG, "Resumed $resumed file transfer(s) waiting for recipient")
            pumpOutgoing()
        }
    }

    /** Drives all resumable outgoing transfers plus contact key handshakes; safe to call periodically. */
    suspend fun pumpOutgoing(): FileTransferSender.PumpSummary? {
        if (!RustBridge.isRunning()) {
            Log.d(TAG, "File pump skipped: engine not running")
            return null
        }
        runCatching { sendHelloHandshakes() }
            .onFailure { Log.w(TAG, "File HELLO sweep failed: ${it.message}") }
        return sender.pumpOnce()
    }

    /** UI escape hatch when preparation reports the recipient binding is not pinned yet. */
    suspend fun requestExchangeBinding(recipientNodeId: String) {
        if (!RustBridge.isRunning()) return
        runCatching { sendHello(recipientNodeId, force = false) }
            .onFailure { Log.w(TAG, "File HELLO request failed: ${it.message}") }
    }

    /**
     * Пользовательская «очистка зависших» (настройки → Передача файлов): отменяет все
     * незавершённые ИСХОДЯЩИЕ передачи (передатчик больше не пытается их докачать) и
     * удаляет их локальные зашифрованные куски. Входящие и завершённые не трогает.
     * @return число отменённых передач.
     */
    suspend fun cancelStalledOutgoing(): Int {
        val cancelled = transferDao.cancelAllOutgoing(System.currentTimeMillis())
        val rows = transferDao.getCancelled()
        var cleanedFiles = 0
        for (row in rows) {
            if (runCatching { chunkStore.deleteTransfer(row.transferId) }.getOrDefault(false)) {
                cleanedFiles++
            }
        }
        Log.i(TAG, "Cancelled $cancelled stalled outgoing transfers (files cleaned: $cleanedFiles)")
        return cancelled
    }

    /**
     * Освобождает место: удаляет локальные файлы (куски и принятые копии) завершённых
     * передач и их строки истории. Принятые файлы, уже сохранённые пользователем в папку,
     * остаются у него.
     * @return число очищенных передач.
     */
    suspend fun purgeCompletedTransfers(): Int {
        val completed = transferDao.getCompleted()
        for (row in completed) {
            runCatching { chunkStore.deleteTransfer(row.transferId) }
            runCatching { receivedStore.deleteTransfer(row.transferId) }
            transferDao.deleteTransfer(row.transferId)
        }
        Log.i(TAG, "Purged ${completed.size} completed transfers")
        return completed.size
    }

    /** Verified plaintext of a completed incoming transfer (app-private storage), if present. */
    fun receivedFileFor(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity): java.io.File? {
        if (transfer.direction != "INCOMING" || transfer.state != "COMPLETE") return null
        return runCatching { receivedStore.receivedFile(transfer.transferId, transfer.displayName) }
            .getOrNull()
    }

    /** Copies a completed incoming transfer's plaintext to the user-chosen SAF destination. */
    suspend fun exportReceivedFile(
        transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity,
        target: android.net.Uri,
    ): Boolean {
        val source = receivedFileFor(transfer) ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val output = appContext.contentResolver.openOutputStream(target) ?: return@runCatching false
                output.use { source.inputStream().use { input -> input.copyTo(it) } }
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun sendHelloHandshakes() {
        val myBinding = FileExchangeKeyStore.publicBinding(appContext) ?: return
        val now = System.currentTimeMillis()
        val contacts = runCatching { chatRepository.getAllContactIds() }.getOrDefault(emptyList())
        for (contactId in contacts) {
            if (!contactId.startsWith("pk_")) continue
            val pinned = runCatching { peerStore.bindingFor(contactId) != null }.getOrDefault(true)
            if (!pinned) sendHello(contactId, force = false, now = now, binding = myBinding)
        }
    }

    private suspend fun sendHello(
        recipientNodeId: String,
        force: Boolean,
        now: Long = System.currentTimeMillis(),
        binding: ByteArray? = null,
    ) {
        if (!recipientNodeId.startsWith("pk_")) return
        val myNodeId = RustBridge.nodeId() ?: return
        val myBinding = binding ?: FileExchangeKeyStore.publicBinding(appContext) ?: return
        if (!force) {
            val last = lastHelloAt[recipientNodeId] ?: 0L
            if (now - last < HELLO_MIN_INTERVAL_MS) return
        }
        lastHelloAt[recipientNodeId] = now
        transport.send(
            FileTransferWire.helloMessageId(myNodeId, recipientNodeId),
            recipientNodeId,
            recipientNodeId,
            FileTransferWire.encodeHelloBinding(myBinding),
        )
        Log.i(TAG, "File HELLO sent to ${recipientNodeId.takeLast(8)} (force=$force)")
    }

    companion object {
        private const val TAG = "FileTransferRouter"
        const val HELLO_MIN_INTERVAL_MS = 60_000L

        fun formatPlaceholder(displayName: String, mediaType: String, totalBytes: Long): String {
            val kind = when {
                mediaType.startsWith("image/") -> "🖼"
                mediaType.startsWith("video/") -> "🎬"
                mediaType.startsWith("audio/") -> "🎵"
                else -> "📎"
            }
            return "$kind $displayName (${formatSize(totalBytes)})"
        }

        fun formatSize(totalBytes: Long): String = when {
            totalBytes >= 1024 * 1024 -> "%.1f МБ".format(totalBytes / (1024.0 * 1024.0))
            totalBytes >= 1024 -> "%.1f КБ".format(totalBytes / 1024.0)
            else -> "$totalBytes Б"
        }
    }
}
