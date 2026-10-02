package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Local history is published before any network side effect. A single, conflated
 * receipt worker must never hold up the Room collector, including later emissions.
 * Both jobs belong to the screen's scope and stop when the screen is disposed.
 */
internal class ChatHistoryObserver(
    private val scope: CoroutineScope,
    private val messages: () -> Flow<List<Message>>,
    private val onMessages: (List<Message>) -> Unit,
    private val reportRead: suspend (String) -> Unit,
    private val onLoadError: (Exception) -> Unit,
    private val onReadError: (Exception) -> Unit,
) {
    // One latest ID, not a growing queue or a coroutine for every Room emission.
    // Repeated status/pin updates of the same incoming row send no new receipt.
    private val latestIncoming = MutableStateFlow<String?>(null)
    private var historyJob: Job? = null

    init {
        scope.launch {
            latestIncoming.filterNotNull().collect { incomingId ->
                try {
                    reportRead(incomingId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onReadError(error)
                }
            }
        }
    }

    /** Retry replaces only the local subscription, never starts another sender. */
    fun start() {
        historyJob?.cancel()
        historyJob = scope.launch {
            try {
                messages().collect { rows ->
                    onMessages(rows)
                    rows.lastOrNull { !it.isFromMe }?.id?.let { latestIncoming.value = it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onLoadError(error)
            }
        }
    }
}
