package com.vladimir.messenger.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One serial consumer for messages and service events, and one for binary file ranges.
 * Keeping the main handler serial avoids races between contact discovery and messages;
 * the file receiver has its own mutex and can buffer a range that precedes its offer.
 * The source retains unprocessed events until polled, so a slow handler cannot drain
 * an entire batch ahead of a newly arrived message.
 */
internal class CoreEventLoops<E : Any>(
    private val isRunning: () -> Boolean,
    private val await: suspend (Lane, Long) -> Boolean,
    private val poll: (Lane, Boolean) -> E?,
    private val isInteractive: (E) -> Boolean,
    private val process: suspend (E) -> Unit,
    private val refreshStatus: suspend () -> Unit,
    private val recordFailure: (String, Exception) -> Unit,
    private val waitMs: () -> Long,
    private val statusIntervalMs: Long,
) {
    enum class Lane { Main, File }

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { runLane(Lane.Main) }
        launch { runLane(Lane.File) }
        launch { runStatus() }
    }

    private suspend fun runLane(lane: Lane) {
        val fairness = MainEventFairness()
        while (currentCoroutineContext().isActive) {
            try {
                if (!isRunning()) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                if (!await(lane, waitMs())) continue
                // A blocking native wait may return after service cancellation.
                // Never remove another event from the queue after that point.
                currentCoroutineContext().ensureActive()
                val event = poll(lane, fairness.preferInteractive()) ?: continue
                if (lane == Lane.Main) fairness.observe(isInteractive(event))
                process(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("${lane.name} event processing", error)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun runStatus() {
        while (currentCoroutineContext().isActive) {
            try {
                if (isRunning()) refreshStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("status refresh", error)
            }
            delay(statusIntervalMs)
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
    }
}

/** Prefer a bounded burst of message/receipt events, then give service events a turn. */
internal class MainEventFairness {
    private var consecutiveInteractive = 0

    fun preferInteractive(): Boolean = consecutiveInteractive < MAX_INTERACTIVE_BURST

    fun observe(interactive: Boolean) {
        consecutiveInteractive = if (interactive) {
            (consecutiveInteractive + 1).coerceAtMost(MAX_INTERACTIVE_BURST)
        } else 0
    }

    companion object {
        const val MAX_INTERACTIVE_BURST = 8
    }
}
