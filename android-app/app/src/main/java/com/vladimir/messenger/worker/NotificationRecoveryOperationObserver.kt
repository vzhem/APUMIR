package com.vladimir.messenger.worker

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Observe completion, not just acceptance of an asynchronous WorkManager enqueue. */
internal object NotificationRecoveryOperationObserver {
    private val direct = Executor { it.run() }

    fun watch(result: ListenableFuture<*>, failure: () -> Unit) {
        result.addListener({
            try {
                result.get() // the listener only runs once the future has finished
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                failure()
            } catch (_: Exception) {
                // Includes cancellation; do not log an operation's contents.
                failure()
            }
        }, direct)
    }
}
