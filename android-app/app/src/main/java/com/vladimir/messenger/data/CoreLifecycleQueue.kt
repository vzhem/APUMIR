package com.vladimir.messenger.data

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes native engine lifecycle calls and remembers who last successfully
 * started or adopted the engine. Ownership changes only on the lifecycle
 * thread; [stopIfOwned] checks it there, not when the stop is requested.
 *
 * A non-service caller gets a private owner when it creates a new engine, so a
 * stale service stop cannot tear that engine down. Merely reusing a service's
 * engine does not steal ownership. An already-running start is not interruptible
 * here. Cancellation of a waiting caller can return before native cleanup finishes,
 * but no later lifecycle task can run until that cleanup actually completes.
 */
internal class CoreLifecycleQueue(private val recordFailure: (String, Exception) -> Unit) {
    private val lifecycleThread = AtomicReference<Thread?>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "apu-core-lifecycle").also { thread ->
            thread.isDaemon = true
            lifecycleThread.set(thread)
        }
    }
    private val nonServiceOwner = Any()
    private var engineOwner: Any? = null

    fun <T> call(block: () -> T): T {
        if (Thread.currentThread() === lifecycleThread.get()) return block()
        val future = executor.submit(Callable<T> { block() })
        try {
            return future.get()
        } catch (error: InterruptedException) {
            // This interrupts an optional wait; native FFI may ignore interruption.
            // A cancelled Future is not evidence that the task's finally has finished.
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Core lifecycle task failed", cause)
            }
        }
    }

    fun enqueue(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (error: Exception) {
                    recordFailure("Asynchronous core lifecycle task failed", error)
                }
            }
        } catch (error: Exception) {
            recordFailure("Could not queue core lifecycle task", error)
        }
    }

    data class StartResult(val started: Boolean, val engineReplaced: Boolean, val enginePresent: Boolean)

    /** A service may adopt a matching engine; a non-service borrower does not take it away. */
    fun start(owner: Any?, allowed: (() -> Boolean)?, block: () -> StartResult): Boolean = call {
        if (allowed?.invoke() == false) return@call false
        val result = block()
        if (result.engineReplaced) {
            // Even a failed initialization may have published a new handle before
            // an error. The new caller must own cleanup, not the former service.
            engineOwner = if (result.enginePresent) owner ?: nonServiceOwner else null
        } else if (result.started && (owner != null || engineOwner == null)) {
            engineOwner = owner ?: nonServiceOwner
        }
        result.started
    }

    /** A late stop for an older service cannot stop an engine already adopted by another owner. */
    fun stopIfOwned(owner: Any, stop: () -> Unit) = enqueue {
        if (engineOwner === owner) {
            try {
                stop()
            } finally {
                engineOwner = null
            }
        }
    }

    /** Call on the lifecycle thread for an unconditional or worker-owned stop. */
    fun clearOwner() {
        check(Thread.currentThread() === lifecycleThread.get())
        engineOwner = null
    }
}
