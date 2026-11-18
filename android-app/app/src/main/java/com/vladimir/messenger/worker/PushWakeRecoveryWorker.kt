package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushWakeEngine
import com.vladimir.messenger.data.push.PushWakePeriodicRecovery
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Independent periodic repair, roughly every fifteen minutes.
 *
 * It exists because a provider callback can be lost between Google and the app,
 * or between the local inbox commit and the one-off scheduling. Every tick
 * ensures a durable route-free marker and then runs a bounded pass directly —
 * even if the in-memory gate still believes another worker is scheduled.
 *
 * Like the one-off worker it exits immediately, touching nothing, while the
 * feature is disabled in this build. WorkManager may delay a periodic request;
 * the interval is not a delivery deadline and a tick is not proof that anything
 * arrived.
 */
class PushWakeRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        if (!PushServiceLocator.enabled(app)) return@withContext Result.success()
        val engine = PushWakeEngine.create(app) ?: return@withContext Result.success()
        try {
            when (engine.runRecovery {
                    if (PushServiceLocator.enabled(app)) {
                        PushWakePeriodicRecovery.Admission.ALLOW
                    } else {
                        PushWakePeriodicRecovery.Admission.DISABLED
                    }
                }) {
                PushWakePeriodicRecovery.Result.TASK_SUCCESS -> Result.success()
                PushWakePeriodicRecovery.Result.DISABLED -> Result.success()
                // The next periodic tick is the retry; no tight loop.
                PushWakePeriodicRecovery.Result.RETRY -> Result.retry()
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "recovery pass failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushWakeRecovery"
        const val UNIQUE_NAME = "apu_push_wake_periodic_v1"
        const val INTERVAL_MINUTES = 15L

        /**
         * Installs (once) the periodic request. Only reached after the feature
         * gate is open; a released build never enqueues this work.
         */
        fun install(context: Context) {
            try {
                val request = PeriodicWorkRequest.Builder(
                    PushWakeRecoveryWorker::class.java, INTERVAL_MINUTES, TimeUnit.MINUTES,
                )
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .addTag(UNIQUE_NAME)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "periodic request not scheduled")
            }
        }
    }
}
