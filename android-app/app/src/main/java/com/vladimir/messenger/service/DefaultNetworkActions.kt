package com.vladimir.messenger.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Apply default-network changes serially, retaining the latest path state.
 * A policy-only update cannot overwrite a queued path change: the path action
 * applies the latest policy itself. Only one wake and one action are retained.
 */
internal class DefaultNetworkActions(
    scope: CoroutineScope,
    private val apply: suspend (DefaultNetworkState.Action) -> Unit,
    private val recordFailure: (Exception) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var pending: DefaultNetworkState.Action? = null
    private var stopped = false
    private val worker = scope.launch {
        for (signal in wake) {
            val action = synchronized(lock) {
                pending.also { pending = null }
            } ?: continue
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
        synchronized(lock) {
            if (stopped || action == DefaultNetworkState.Action.IGNORE ||
                action == DefaultNetworkState.Action.NONE
            ) return
            if (action != DefaultNetworkState.Action.POLICY || pending == null) pending = action
            wake.trySend(Unit)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            pending = null
            wake.close()
        }
        worker.cancel()
    }
}
