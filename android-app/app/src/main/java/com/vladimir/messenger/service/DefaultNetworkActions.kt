package com.vladimir.messenger.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Applies default-network changes serially, retaining only the latest pending state. */
internal class DefaultNetworkActions(
    scope: CoroutineScope,
    private val apply: suspend (DefaultNetworkState.Action) -> Unit,
    private val recordFailure: (Exception) -> Unit,
) {
    private val pending = Channel<DefaultNetworkState.Action>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (action in pending) {
            try {
                apply(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(error)
            }
        }
    }

    fun submit(action: DefaultNetworkState.Action) {
        pending.trySend(action)
    }

    fun stop() {
        pending.close()
        worker.cancel()
    }
}
