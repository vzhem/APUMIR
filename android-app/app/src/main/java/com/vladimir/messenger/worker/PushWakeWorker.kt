package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushWakeEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bounded receive pass triggered by a provider signal, a startup resume or
 * a retry.
 *
 * It does nothing unless the whole feature is enabled in *this build*
 * (`PushServiceLocator.enabled`, i.e. verified Firebase client configuration +
 * explicit test-build flag + approved endpoint host). With the shipped defaults
 * that is false, so the worker returns success immediately without touching a
 * journal, the network or the scheduler.
 *
 * When enabled, a pass may: claim leased hints from the encrypted inbox,
 * re-derive each route from the live pin and registration, run a bounded
 * mailbox window, commit authenticated messages to Room, and ACK only what was
 * durably committed. It never posts a notification and never claims delivery.
 * `RETRY` keeps the lease and schedules another independent one-off request.
 */
class PushWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        if (!PushServiceLocator.enabled(app)) return@withContext Result.success()
        val engine = PushWakeEngine.create(app) ?: return@withContext Result.success()
        try {
            when (engine.runOnce()) {
                com.vladimir.messenger.data.push.PushWakeRunLoop.Result.SUCCESS -> Result.success()
                com.vladimir.messenger.data.push.PushWakeRunLoop.Result.RETRY -> {
                    // Requeue independently: the new request must not inherit
                    // this worker's eventual failure.
                    enqueue(app)
                    Result.retry()
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Never log routes, payloads, tokens or exception text.
            Log.w(TAG, "wake pass failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushWakeWorker"
        internal const val WORK_NAME = "apu_push_wake_drain_v1"

        /** Independent request: a new signal must never be lost behind a KEEP. */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequest.Builder(PushWakeWorker::class.java)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .addTag(WORK_NAME)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueue(request)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "wake request not scheduled")
            }
        }
    }
}
