package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps network calls off the UI thread and repairs an ACK racing a late send. */
internal class NetworkOutboxSender(
    private val messages: MessageDao,
    private val nativeSend: (String, String, String, String, Boolean) -> Boolean = { id, chat, peer, content, manual ->
        if (manual) RustBridge.retryMessage(id, chat, peer, content)
        else RustBridge.sendMessage(id, chat, peer, content)
    },
    private val confirm: (String) -> Unit = RustBridge::confirmMessageDelivered,
) {
    suspend fun send(messageId: String, chatId: String, peerId: String, content: String, manual: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            OperationalDiagnostics.tracker.attempt(messageId)
            val sent = nativeSend(messageId, chatId, peerId, content, manual)
            OperationalDiagnostics.tracker.transportResult(messageId, sent)
            try {
                // A retry can have read SENT before the ACK, but entered the
                // native gate only after the first cleanup. Check again after
                // the attempt so it cannot leave an orphaned relay copy.
                val row = messages.getMessageById(messageId)
                if (row?.isFromMe == true && (row.status == MessageStatus.DELIVERED.name || row.status == MessageStatus.READ.name)) {
                    confirm(messageId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("NetworkOutboxSender", "Post-send custody reconciliation deferred: ${error.javaClass.simpleName}")
            }
            sent
        }
}
