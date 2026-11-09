package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.vladimir.messenger.R
import com.vladimir.messenger.data.chatclear.ChatClearDelivery
import com.vladimir.messenger.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun clearChatAction(context: Context, repository: ChatRepository, clear: ChatClearDelivery, chatId: String, both: Boolean) {
    if (!both) {
        repository.clearHistory(chatId)
        return
    }
    try {
        val batches = clear.enqueueSnapshot(chatId)
        if (batches > 0) {
            try { clear.pump() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("ChatClearAction", "Queued clear will be retried", error) }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, if (batches > 0) R.string.chat_clear_peer_queued else R.string.chat_clear_peer_empty, Toast.LENGTH_LONG).show()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        Log.w("ChatClearAction", "Could not queue chat clear", error)
        withContext(Dispatchers.Main) { Toast.makeText(context, R.string.chat_clear_peer_error, Toast.LENGTH_LONG).show() }
    }
}
