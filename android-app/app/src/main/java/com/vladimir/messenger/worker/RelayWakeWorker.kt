package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.RelayAtRestMasterKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M8-E slice 1: bounded receive-only relay wake.
 *
 * WorkManager даёт процессу ограниченное окно выполнения; worker не запускает
 * foreground service, не отправляет пользовательские сообщения и не ставит
 * собственные retry. Durable engine поднимается максимум на [ACTIVE_WINDOW_MS],
 * делает один bounded gossip discovery, затем гарантированно останавливается.
 */
class RelayWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_IDENTITY_CREATED, false)) {
            Log.i(TAG, "M8 wake skipped: identity is not created")
            return@withContext Result.success()
        }

        val displayName = prefs.getString(KEY_DISPLAY_NAME, "Anonymous") ?: "Anonymous"
        val publicKey = prefs.getString(KEY_EXISTING_PUBLIC_KEY, null)
        val privateKey = prefs.getString(KEY_EXISTING_PRIVATE_KEY, null)
        val relayDbPath = File(applicationContext.filesDir, RELAY_DB_NAME).absolutePath

        try {
            val keyInstalled = RelayAtRestMasterKey.installIntoCore(applicationContext)
            val wake = RustBridge.runBoundedRelayWake(
                displayName = displayName,
                existingPublicKey = publicKey,
                existingPrivateKey = privateKey,
                relayDbPath = relayDbPath,
                activeWindowMillis = ACTIVE_WINDOW_MS,
            )
            Log.i(
                TAG,
                "M8 wake complete: key=$keyInstalled owned=${wake.engineStartedByWorker} " +
                    "gossip=${wake.gossipTriggered} custody=${wake.custodyMode} " +
                    "quarantined=${wake.quarantineCount} windowMs=$ACTIVE_WINDOW_MS"
            )
            Result.success()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "M8 wake interrupted; engine cleanup completed", error)
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

        private const val PREFS_NAME = "p2p_prefs"
        private const val KEY_IDENTITY_CREATED = "identity_created"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EXISTING_PUBLIC_KEY = "existing_public_key"
        private const val KEY_EXISTING_PRIVATE_KEY = "existing_private_key"
        private const val RELAY_DB_NAME = "apu_relay.sqlite"
    }
}
