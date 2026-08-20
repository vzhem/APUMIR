package com.vladimir.messenger

import android.app.Application
import android.util.Log
import java.util.concurrent.TimeUnit
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.worker.ProxyCollectorWorker
import com.vladimir.messenger.worker.RelayWakeWorker
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MessengerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must run before Room/services/workers can observe restored stale state.
        DeviceIdentityMarker.discardIfRestored(applicationContext)
        createNotificationChannels()
        scheduleBoundedRelayWake()

        // Automatic collection is an Organizer (20 qualified referrals) entitlement.
        try {
            val qualified = ReferralRankStore.qualifiedDirectCount(applicationContext)
            val workManager = WorkManager.getInstance(applicationContext)
            if (FileTransferRankPolicy.canUseAutomaticProxy(qualified)) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val collectRequest = PeriodicWorkRequestBuilder<ProxyCollectorWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    ProxyCollectorWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    collectRequest
                )
                Log.i("MessengerApp", "Rank-entitled proxy collector scheduled")
            } else {
                workManager.cancelUniqueWork(ProxyCollectorWorker.WORK_NAME)
                Log.i("MessengerApp", "Automatic proxy collector locked below rank 20")
            }
        } catch (e: Exception) {
            Log.e("MessengerApp", "Failed to apply proxy collector entitlement", e)
        }
    }

    /** M8-E slice 1: OS-managed bounded wake, без exact alarm/вечного foreground. */
    private fun scheduleBoundedRelayWake() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<RelayWakeWorker>(
                RelayWakeWorker.REPEAT_HOURS,
                TimeUnit.HOURS,
                RelayWakeWorker.FLEX_HOURS,
                TimeUnit.HOURS,
            )
                .setInitialDelay(RelayWakeWorker.INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                RelayWakeWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(
                "MessengerApp",
                "M8 bounded relay wake scheduled: every ${RelayWakeWorker.REPEAT_HOURS}h " +
                    "flex=${RelayWakeWorker.FLEX_HOURS}h window=${RelayWakeWorker.ACTIVE_WINDOW_MS}ms"
            )
        } catch (error: Exception) {
            Log.e("MessengerApp", "Failed to schedule M8 bounded relay wake", error)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2P Messenger Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps P2P connection alive"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "p2p_messenger_service"
    }


}
