package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushTokenRegistrar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The job that turns a provider token into registered mailbox routes.
 *
 * The token itself is never put into WorkManager input data: the worker asks
 * the SDK again, so a stale token in a persisted request cannot be replayed, and
 * the token never appears in a database, a log or a backup. The journal then
 * compares fingerprints, so an unchanged token performs no network call.
 *
 * Every outcome except `REGISTERED` / `ALREADY_CURRENT` is treated as a retry;
 * nothing here ever reports a notification or a delivered message.
 */
class PushTokenWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        if (!PushServiceLocator.enabled(app)) return@withContext Result.success()
        val registrar = PushServiceLocator.registrar(app) ?: return@withContext Result.success()
        try {
            when (registrar.run()) {
                PushTokenRegistrar.Outcome.Disabled,
                PushTokenRegistrar.Outcome.NotConfigured,
                PushTokenRegistrar.Outcome.NoPlayServices -> Result.success()
                PushTokenRegistrar.Outcome.AlreadyCurrent,
                PushTokenRegistrar.Outcome.Registered -> {
                    // A newly registered (or rotated) epoch is useless until the
                    // peers know it; this is a best-effort announcement, not a
                    // delivery claim, so its result does not change ours.
                    com.vladimir.messenger.data.push.PushEpochPublish.noteAllRegistered(app)
                    Result.success()
                }
                PushTokenRegistrar.Outcome.Partial,
                PushTokenRegistrar.Outcome.Failed -> Result.retry()
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "token registration failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushTokenWorker"
        internal const val UNIQUE_NAME = "apu_push_token_registration_v1"

        /**
         * Unique work: repeated provider callbacks must coalesce into one
         * registration attempt instead of racing each other.
         */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequest.Builder(PushTokenWorker::class.java)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "registration request not scheduled")
            }
        }
    }
}
