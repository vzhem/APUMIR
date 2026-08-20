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
 * Facade the service layer talks to: routes incoming packet texts (before they are stored as
 * chat messages) and drives the sender pump. Everything rides the existing durable Rust
 * transport, so multi-day offline custody and restart resume come from the M8 machinery.
 */
@Singleton
class FileTransferRouter @Inject constructor(
    @ApplicationContext context: Context,
    transferDao: FileTransferDao,
    peerStore: FileExchangePeerStore,
    chatRepository: ChatRepository,
) {
    private val sender: FileTransferSender
    private val receiver: FileTransferReceiver

    init {
        val appContext = context.applicationContext
        val chunkStore = FileTransferChunkStore.forApplication(appContext)
        val receivedStore = ReceivedFileStore(File(appContext.noBackupFilesDir, "file_received/v1"))
        val transport: PacketTransport = RustPacketTransport()
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
            transport = transport,
            ownBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            sleeper = { millis -> kotlinx.coroutines.delay(millis) },
        )
        sender = senderLocal
        receiver = FileTransferReceiver(
            transferDao = transferDao,
            chunkStore = chunkStore,
            receivedStore = receivedStore,
            pinner = { binding, pinnedAtMs -> peerStore.pinFirstSeen(binding, pinnedAtMs) },
            crypto = crypto,
            keyVault = keyVault,
            identity = identity,
            transport = transport,
            ackSink = { transferIdHex, contiguousChunks ->
                senderLocal.onReceiverAck(transferIdHex, contiguousChunks)
            },
            notifier = notifier,
        )
    }

    /** True when the text was a file packet; the caller must skip normal chat-text handling. */
    suspend fun routeIncoming(senderId: String, chatId: String, messageId: String, text: String): Boolean =
        receiver.onIncomingText(senderId, chatId, messageId, text)

    /** Drives all resumable outgoing transfers; safe to call periodically. */
    suspend fun pumpOutgoing(): FileTransferSender.PumpSummary? {
        if (!RustBridge.isRunning()) {
            Log.d(TAG, "File pump skipped: engine not running")
            return null
        }
        return sender.pumpOnce()
    }

    companion object {
        private const val TAG = "FileTransferRouter"

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
