package com.vladimir.messenger.data.push

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The retry pumps' one way to ask the push service to wake a correspondent.
 *
 * Separate from [PushWakePublish] for two reasons.
 *
 *  - **No constructor changes.** The pumps already have callers and emulator
 *    tests; appending a `wake` parameter re-bound an existing trailing lambda to
 *    the wrong argument and turned the whole emulator suite red. A process-wide
 *    hook keeps every existing signature intact.
 *  - **Never in the way.** A queued message must be retried even when the wake
 *    cannot happen, so the call is fire-and-forget, never throws and carries no
 *    peer id into a log line.
 *
 * With a closed gate (every non-test build) [PushWakePublish] returns before
 * reading storage, so this is a no-op there.
 */
internal object PushWakeHook {

    /**
     * A row younger than this may still be confirmed by its own round trip.
     * Waking that early would spend the per-peer budget on a phone that answered.
     */
    const val MIN_MESSAGE_AGE_MS = 15_000L

    private const val TAG = "PushWakeHook"

    /** Process-lifetime scope for best-effort nudges; nothing here is retried. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wake [peerId] unless the message it is about is still fresh.
     *
     * @param messageTimestampMs when the row was written; 0 means "unknown", and
     *        an unknown age is treated as stale, because the row is already in a
     *        retry queue by the time this is called.
     */
    fun wakeIfMessageStale(peerId: String, messageTimestampMs: Long) {
        if (peerId.isBlank()) return
        if (messageTimestampMs > 0L &&
            System.currentTimeMillis() - messageTimestampMs < MIN_MESSAGE_AGE_MS
        ) {
            return
        }
        scope.launch { wake(peerId) }
    }

    /** One bounded attempt; a failure is a log line, never an exception upward. */
    private suspend fun wake(peerId: String) {
        try {
            PushWakePublish.wakeInstalled(peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the capability or exception text.
            Log.w(TAG, "Wake request skipped")
        }
    }
}
