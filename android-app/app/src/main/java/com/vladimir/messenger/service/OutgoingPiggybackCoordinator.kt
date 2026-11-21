package com.vladimir.messenger.service

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide singleton coordinator used by repositories and foreground service
 * workers to attach auxiliary network syncs to genuine user outgoing sends.
 */
object OutgoingPiggybackCoordinator {
    val policy = OutgoingPiggybackPolicy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)
    private val flushListeners = CopyOnWriteArrayList<suspend () -> Unit>()
    private val wakeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        scope.launch {
            for (signal in triggerChannel) {
                val snapshot = flushListeners.toList()
                for (listener in snapshot) {
                    if (!isWindowActive()) break
                    try {
                        withTimeoutOrNull(OutgoingPiggybackPolicy.FLUSH_BUDGET_MS) {
                            com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics.measure(
                                com.vladimir.messenger.data.diagnostics.DiagnosticOperation.PIGGYBACK_FLUSH,
                            ) {
                                listener()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        com.vladimir.messenger.data.diagnostics.TransferDiagnostics.recordWarning(
                            "piggyback",
                            "фоновая попутная задача не завершилась: ${error.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
    }

    /** Called ONLY on genuine user-initiated correspondence sends. */
    fun onUserOutgoing(nowMs: Long = System.currentTimeMillis()) {
        val shouldFlush = policy.noteUserOutgoing(nowMs)
        for (wake in wakeListeners) {
            runCatching { wake() }
        }
        if (shouldFlush) {
            triggerChannel.trySend(Unit)
        }
    }

    /** Mark auxiliary state dirty so it flushes on the next user send (or immediately if active). */
    fun markDirty(nowMs: Long = System.currentTimeMillis()) {
        policy.markDirty()
        if (policy.isActive(nowMs)) {
            triggerChannel.trySend(Unit)
        }
    }

    /** True while inside the ~15 s window after a genuine user outgoing send. */
    fun isWindowActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        policy.isActive(nowMs)

    /** Register a suspend callback executed inside the piggyback window after user sends. */
    fun registerFlushListener(listener: suspend () -> Unit): () -> Unit {
        flushListeners.add(listener)
        return { flushListeners.remove(listener) }
    }

    /** Register a lightweight non-blocking callback invoked immediately on user sends. */
    fun registerWakeListener(listener: () -> Unit): () -> Unit {
        wakeListeners.add(listener)
        return { wakeListeners.remove(listener) }
    }
}
