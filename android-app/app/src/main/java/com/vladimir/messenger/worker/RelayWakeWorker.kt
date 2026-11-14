package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.security.ExistingRoutingIdentity
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.data.security.RelayAtRestMasterKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M8-E slice 1: bounded receive-only relay wake.
 *
 * WorkManager даёт процессу ограниченное окно выполнения; worker не запускает
 * foreground service, не отправляет пользовательские сообщения и не ставит
 * собственные retry. Необязательное окно ожидания ограничено [ACTIVE_WINDOW_MS]
 * и уступает запуску/остановке службы. Native start, discovery и stop могут
 * ждать дольше; остановка временного движка выполняется в finally.
 */
class RelayWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_IDENTITY_CREATED, false) || !DeviceIdentityMarker.isPresent(applicationContext)) {
            Log.i(TAG, "M8 wake skipped: local identity is not ready")
            return@withContext Result.success()
        }
        val routing = ExistingRoutingIdentity.resolve(
            prefs.getString("node_id", null), prefs.getString("public_key", null),
            prefs.getString(KEY_EXISTING_PUBLIC_KEY, null), prefs.getString(KEY_EXISTING_PRIVATE_KEY, null),
        )
        if (routing == null) {
            Log.e(TAG, "M8 wake skipped: saved routing identity is missing or inconsistent")
            return@withContext Result.failure()
        }
        val displayName = prefs.getString(KEY_DISPLAY_NAME, "Anonymous") ?: "Anonymous"
        val publicKey = routing.nodeId
        val privateKey = routing.legacyPrivateKey
        val relayDbPath = File(applicationContext.filesDir, RELAY_DB_NAME).absolutePath

        try {
            if (RustBridge.isRunning() && !ExistingRoutingIdentity.matchesRunning(publicKey, RustBridge.nodeId())) {
                Log.e(TAG, "M8 wake skipped: running engine identity does not match saved identity")
                return@withContext Result.failure()
            }
            val signing = IdentitySigningKeyStore.installIntoCore(
                applicationContext,
                publicKey.orEmpty(),
            )
            val keyInstalled = RelayAtRestMasterKey.installIntoCore(applicationContext)
            val wake = RustBridge.runBoundedRelayWake(
                displayName = displayName,
                existingPublicKey = publicKey,
                existingPrivateKey = privateKey,
                relayDbPath = relayDbPath,
                activeWindowMillis = ACTIVE_WINDOW_MS,
            )
            if (wake.custodyMode == "identity_mismatch") return@withContext Result.failure()
            if (wake.custodyMode == "yielded") {
                Log.i(TAG, "M8 optional wake yielded to a lifecycle request; no delivery is implied")
                return@withContext Result.success()
            }
            Log.i(
                TAG,
                "M8 wake complete: signing=${signing?.mode ?: "legacy-only"} " +
                    "key=$keyInstalled owned=${wake.engineStartedByWorker} " +
                    "gossip=${wake.gossipTriggered} custody=${wake.custodyMode} " +
                    "quarantined=${wake.quarantineCount} windowMs=$ACTIVE_WINDOW_MS"
            )
            Result.success()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "M8 wake interrupted; lifecycle cleanup may still be running", error)
            Result.failure()
        } catch (error: Exception) {
            // Следующее periodic окно — единственный retry: не создаём быстрый цикл.
            Log.e(TAG, "M8 wake failed; no immediate retry", error)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "RelayWakeWorker"
        const val WORK_NAME = "m8_bounded_relay_wake"
        const val REPEAT_HOURS = 6L
        const val FLEX_HOURS = 1L
        const val INITIAL_DELAY_MINUTES = 30L
        const val ACTIVE_WINDOW_MS = 25_000L

        /**
         * Run one bounded pass now. A push signal is exactly the moment the relay
         * window is needed and the periodic schedule is not: the phone may have
         * been asleep for hours. The name is unique so a burst of signals becomes
         * one pending pass rather than a queue of them, and KEEP means an already
         * scheduled pass is not restarted.
         */
        fun enqueueOnce(context: android.content.Context) {
            try {
                val request = OneTimeWorkRequest.Builder(RelayWakeWorker::class.java)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        WORK_NAME + "_now",
                        ExistingWorkPolicy.KEEP,
                        request,
                    )
            } catch (_: Exception) {
                Log.w(TAG, "bounded wake request not scheduled")
            }
        }

        private const val PREFS_NAME = "p2p_prefs"
        private const val KEY_IDENTITY_CREATED = "identity_created"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EXISTING_PUBLIC_KEY = "existing_public_key"
        private const val KEY_EXISTING_PRIVATE_KEY = "existing_private_key"
        private const val RELAY_DB_NAME = "apu_relay.sqlite"
    }
}
