package com.vladimir.messenger.data.push

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * WorkManager backend for a future enabled push runtime. It does not itself
 * start a worker, read Firebase, or connect this feature to the application.
 *
 * One-off requests are deliberately independent, not unique KEEP/REPLACE and
 * not children of a running request. Thus an arrival after the final inbox
 * check can run even while the previous worker returns, and cannot inherit its
 * eventual cancellation/failure. [PushWakeScheduleGate] coalesces normal
 * in-process requests; SQLite leases and idempotent message processing are
 * still required when jobs overlap or a process is restarted. The run loop
 * limits concurrent drains within one classloader, but not across processes or
 * other effects: the future runtime must bound shared sync work accordingly.
 *
 * Before accepting FCM callbacks the caller must durably install [recovery]
 * and wait for its [Operation.result] to succeed. The periodic worker needs
 * [PushWakePeriodicRecovery]: ensure a generic scan marker, then run the bounded
 * drain directly rather than merely calling gate.request(). This can also repair
 * a stale ticket or a crash between SQLite commit and one-off scheduling. The
 * marker is not a scan: the authenticated, bounded full-scan callback and its
 * durable continuation are not implemented here. WorkManager/Android may
 * delay both requests; a 15-minute interval is not a delivery deadline. Both
 * workers must re-check the feature/identity gate and use [PushWakeRunLoop].
 *
 * No route, token, identity or message is placed into WorkManager input data.
 */
internal class PushWakeWorkManager(
    private val manager: WorkManager,
    private val drainWorker: Class<out ListenableWorker>,
    private val recoveryWorker: Class<out ListenableWorker>,
    // The seam permits testing the asynchronous enqueue-failure window; the
    // default always submits an ordinary independent WorkManager request.
    private val startOneOff: (OneTimeWorkRequest) -> ListenableFuture<Operation.State.SUCCESS> =
        { manager.enqueue(it).result },
) {
    /** Suitable as [PushWakeScheduleGate]'s enqueue callback. */
    fun enqueue(recordFailure: () -> Unit): Boolean = try {
        val request = OneTimeWorkRequest.Builder(drainWorker)
            .setConstraints(networkRequired())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(DRAIN_TAG)
            .build()
        val result = startOneOff(request)
        result.addListener({
            try {
                result.get() // listener runs only after the operation completes
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                recordFailure()
            } catch (_: Exception) {
                // Generic count through the gate; never provider/exception data.
                recordFailure()
            }
        }, DIRECT)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The token-aware callback clears the gate; the false return is safe
        // even if the callback already ran synchronously.
        recordFailure()
        false
    }

    /**
     * Install one independent periodic job. A successful enqueue Operation is
     * required before the provider path is enabled; KEEP is safe here because
     * the periodic worker drains directly rather than chaining one-off work.
     */
    fun recovery(): Operation {
        val request = PeriodicWorkRequest.Builder(recoveryWorker, RECOVERY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkRequired())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(RECOVERY_TAG)
            .build()
        return manager.enqueueUniquePeriodicWork(RECOVERY_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun networkRequired(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        internal const val DRAIN_TAG = "apu_push_wake_drain_v1"
        internal const val RECOVERY_TAG = "apu_push_wake_recovery_v1"
        internal const val RECOVERY_NAME = "apu_push_wake_periodic_v1"
        internal const val BACKOFF_SECONDS = 15L
        internal const val RECOVERY_MINUTES = 15L
        private val DIRECT = Executor { command -> command.run() }
    }
}
