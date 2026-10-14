package com.vladimir.messenger.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.schedule.DeferredDelivery
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Local-only work: in particular there is no CONNECTED network constraint. */
@HiltWorker
class DeferredRevealWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val delivery: DeferredDelivery,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("entry") ?: return Result.success()
        return try {
            if (delivery.reveal(key)) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("DeferredReveal", "Reveal deferred; hidden copy retained", error)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, key: String, showAtMs: Long) {
            val request = OneTimeWorkRequest.Builder(DeferredRevealWorker::class.java)
                .setInputData(Data.Builder().putString("entry", key).build())
                .setInitialDelay((showAtMs - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("deferred_reveal_$key", ExistingWorkPolicy.KEEP, request)
        }
    }
}
