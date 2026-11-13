package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A future periodic worker can repair an unrecorded provider callback by first
 * ensuring a durable, route-free recovery marker and then directly running the
 * bounded drain. This is an unconnected seam: it starts no WorkManager, FCM,
 * network request, full scan or notification by itself.
 *
 * [admission] must re-read the current opt-in and local identity; this initial
 * check is not a lifecycle lock. The drain must independently revalidate the
 * feature, identity, pin and registration around every actual sync effect and
 * supply a bounded full scan with durable continuation. Different workers may
 * overlap; the per-drain batch limit is not a global concurrency limit, and a
 * lease can expire during sync. The runtime needs a shared concurrency bound
 * and idempotent effects. An index hit, stored marker or task success never
 * proves that a message was delivered.
 *
 * [ensureMarker] must be idempotent for an existing recovery marker and retain
 * its lease and backoff. Real provider events still advance the marker revision.
 * If a missed callback races an existing scan, a later periodic tick must run
 * after that marker clears; neither WorkManager nor this seam promises when.
 * Before receiving provider callbacks, the caller must independently install
 * and confirm the periodic WorkManager task, and map [Result.RETRY] to retry.
 */
internal class PushWakePeriodicRecovery(
    private val admission: suspend () -> Admission,
    private val ensureMarker: (Long) -> PushWakeInbox.EnqueueResult,
    private val runDrain: suspend () -> PushWakeRunLoop.Result,
    private val nowMs: () -> Long,
    private val recordFailure: () -> Unit,
) {
    enum class Admission { ALLOW, DISABLED, RETRY }
    enum class Result { DISABLED, TASK_SUCCESS, RETRY }

    suspend fun run(): Result {
        return try {
            currentCoroutineContext().ensureActive()
            val decision = admission()
            currentCoroutineContext().ensureActive()
            when (decision) {
                Admission.DISABLED -> return Result.DISABLED
                Admission.RETRY -> return Result.RETRY
                Admission.ALLOW -> Unit
            }
            val marker = ensureMarker(nowMs())
            // A cancellation after the commit leaves the marker for another pass.
            currentCoroutineContext().ensureActive()
            if (marker != PushWakeInbox.EnqueueResult.RECOVERY_QUEUED) return Result.RETRY
            val drained = runDrain()
            currentCoroutineContext().ensureActive()
            when (drained) {
                PushWakeRunLoop.Result.SUCCESS -> Result.TASK_SUCCESS
                PushWakeRunLoop.Result.RETRY -> Result.RETRY
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cancellation that raced an ordinary exception is still cancellation.
            currentCoroutineContext().ensureActive()
            try {
                // Never report identity, routes, data or exception text.
                recordFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not include exception text or any provider/identity data.
                // A broken counter must not turn this into a terminal failure.
                Logger.getLogger("PushWakePeriodicRecovery").warning("Recovery diagnostics unavailable")
            }
            currentCoroutineContext().ensureActive()
            Result.RETRY
        }
    }
}
