package com.vladimir.messenger.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The one-off is deliberately independent, not KEEP or a child of an older request. */
internal class NotificationRecoveryWorkManager(
    private val manager: WorkManager,
    private val worker: Class<out ListenableWorker>,
) {
    fun oneOff(id: UUID): Operation {
        val request = OneTimeWorkRequest.Builder(worker).setId(id)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(ONE_OFF_TAG)
            .build()
        return manager.enqueue(request)
    }

    fun periodic(): Operation {
        val request = PeriodicWorkRequest.Builder(worker, 15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(PERIODIC_TAG)
            .build()
        return manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val ONE_OFF_TAG = "apu_local_notifications_once_v2"
        const val PERIODIC_TAG = "apu_local_notifications_periodic_v1"
        const val PERIODIC_NAME = "apu_local_notifications_periodic_v1"
    }
}
