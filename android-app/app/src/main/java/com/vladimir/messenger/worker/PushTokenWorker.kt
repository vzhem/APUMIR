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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Причина отказа должна дожить до отчёта даже если рабочий процесс
        // поднят системой раньше, чем кто-либо установил счётчики.
        com.vladimir.messenger.data.push.PushDiagnostics.install(app)
        val registrar = PushServiceLocator.registrar(app) ?: return@withContext Result.success()
        try {
            val outcome = runMutex.withLock { registrar.run() }
            com.vladimir.messenger.data.push.PushDiagnostics.noteRegistration(
                when (outcome) {
                    PushTokenRegistrar.Outcome.Disabled ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.DISABLED
                    PushTokenRegistrar.Outcome.NotConfigured ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.NOT_CONFIGURED
                    PushTokenRegistrar.Outcome.NoPlayServices ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.NO_PLAY_SERVICES
                    PushTokenRegistrar.Outcome.Registered,
                    PushTokenRegistrar.Outcome.AlreadyCurrent ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.REGISTERED
                    PushTokenRegistrar.Outcome.Partial ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.PARTIAL
                    PushTokenRegistrar.Outcome.Failed ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.FAILED
                    // Not a failure of the service: there was simply nobody to
                    // register yet. A distinct phase keeps the report honest.
                    PushTokenRegistrar.Outcome.NoTargets ->
                        com.vladimir.messenger.data.diagnostics.PushPhase.NO_TARGETS
                },
            )
            when (outcome) {
                PushTokenRegistrar.Outcome.Disabled,
                PushTokenRegistrar.Outcome.NotConfigured,
                PushTokenRegistrar.Outcome.NoPlayServices,
                // Retrying cannot help: the peer set is empty until a contact
                // exists, and a retry loop would only burn battery.
                PushTokenRegistrar.Outcome.NoTargets -> Result.success()
                PushTokenRegistrar.Outcome.AlreadyCurrent,
                PushTokenRegistrar.Outcome.Registered -> {
                    // A newly registered (or rotated) epoch is useless until the
                    // peers know it; this is a best-effort announcement, not a
                    // delivery claim, so its result does not change ours.
                    com.vladimir.messenger.data.push.PushEpochPublish.noteAllRegistered(app)
                    // The network just worked, so this is the honest moment to ask
                    // /health: the report stops showing a stale startup failure.
                    com.vladimir.messenger.data.push.PushServiceLocator.probeEndpoint(app)
                    Result.success()
                }
                PushTokenRegistrar.Outcome.Partial -> {
                    // Announce the batch that succeeded now while backoff retries the rest.
                    com.vladimir.messenger.data.push.PushEpochPublish.noteAllRegistered(app)
                    Result.retry()
                }
                PushTokenRegistrar.Outcome.Failed -> Result.retry()
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            com.vladimir.messenger.data.push.PushDiagnostics.noteError("token registration")
            Log.w(TAG, "token registration failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushTokenWorker"
        internal const val UNIQUE_NAME = "apu_push_token_registration_v1"
        internal const val UNIQUE_PEER_NAME = "apu_push_peer_registration_v1"
        private const val PEER_DEBOUNCE_SECONDS = 3L
        private val runMutex = Mutex()

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

        /**
         * Debounced pass for newly pinned peers (direct, group or channel).
         * Separate unique slot from [enqueue] so a burst of TOFU pins never
         * cancels a provider token rotation, and serialized via [runMutex].
         */
        fun enqueueIncremental(context: Context) {
            try {
                val request = OneTimeWorkRequest.Builder(PushTokenWorker::class.java)
                    .setInitialDelay(PEER_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_PEER_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "incremental registration not scheduled")
            }
        }
    }
}
