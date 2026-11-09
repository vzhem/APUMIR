package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.CancellationException

/** Scheduling uses a process-local monotonic clock, never message or wall-clock time. */
internal object ChatClearPumpPolicy {
    fun due(now: Long, lastAttempt: Long?, retryMs: Long): Boolean =
        lastAttempt == null || now - lastAttempt >= retryMs

    /** A broken mirror channel must not prevent direct peer delivery. */
    suspend fun mirrorThenDeliver(
        mirror: suspend () -> Unit,
        recordFailure: (Exception) -> Unit,
        deliver: suspend () -> Unit,
    ) {
        try { mirror() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { recordFailure(error) }
        deliver()
    }
}
