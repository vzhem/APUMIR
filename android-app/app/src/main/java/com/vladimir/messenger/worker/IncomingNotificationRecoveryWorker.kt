package com.vladimir.messenger.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.service.IncomingMessageNotifications
import com.vladimir.messenger.service.IncomingNotificationIdentity
import com.vladimir.messenger.service.IncomingNotificationSweep
import com.vladimir.messenger.service.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recover already committed local notification work, without a network
 * constraint, foreground service, Rust event loop, Firebase or push payload.
 * Android may delay WorkManager; a queued job is not a displayed notification.
 * A crash before enqueue or a stale in-memory ticket is repaired by an
 * independently persisted periodic job, not an immediate delivery guarantee.
 */
@HiltWorker
class IncomingNotificationRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messages: MessageDao,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        appContext = applicationContext
        scheduleGate.workerStarted(id)
        return try {
            val outcome = runOnce()
            currentCoroutineContext().ensureActive()
            // Runs before WorkManager sees this result. A dirty successor is an
            // independent request, not dependent on this worker's result.
            scheduleGate.workerFinished(id, retrying = outcome == Outcome.RETRY)
            if (outcome == Outcome.RETRY) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            scheduleGate.workerStopped(id)
            throw cancelled
        } catch (error: Exception) {
            // No message content, peer, identity or notification text in logs.
            Log.w(TAG, "Local notification recovery deferred: ${error.javaClass.simpleName}")
            scheduleGate.workerFinished(id, retrying = true)
            Result.retry()
        }
    }

    private suspend fun runOnce(): Outcome = withContext(Dispatchers.IO) {
        // The independent periodic job and one-offs may overlap. Serialize the
        // short local sweep; service observers use a separate per-item gate.
        if (!sweepGate.tryLock()) return@withContext Outcome.RETRY
        try {
            val owner = IncomingNotificationIdentity.current(applicationContext)
                ?: return@withContext Outcome.SUCCESS
            val checkpoint = NotificationRecoveryCheckpoint(
                applicationContext.getSharedPreferences(CHECKPOINT_PREFS, Context.MODE_PRIVATE),
            )
            val cursor = checkpoint.read(owner)
            val step = withTimeoutOrNull(WINDOW_MS) {
                IncomingMessageNotifications(applicationContext, messages, notificationHelper).drainBatch(owner, cursor)
            } ?: return@withContext Outcome.RETRY
            currentCoroutineContext().ensureActive()
            if (!IncomingNotificationIdentity.isCurrent(applicationContext, owner) || !checkpoint.save(owner, step.cursor)) {
                return@withContext Outcome.RETRY
            }
            currentCoroutineContext().ensureActive()
            if (step.retry) Outcome.RETRY else Outcome.SUCCESS
        } finally {
            sweepGate.unlock()
        }
    }

    private enum class Outcome { SUCCESS, RETRY }

    companion object {
        private const val TAG = "NotificationRecovery"
        private const val CHECKPOINT_PREFS = "apu_local_notification_recovery_v1"
        private const val WINDOW_MS = 30_000L
        private val sweepGate = Mutex()
        @Volatile private var appContext: Context? = null

        // All producers and workers use this single instance. It is an
        // optimization within one process, not a durable/cross-process lock.
        private val scheduleGate = NotificationRecoveryScheduleGate(
            enqueue = { id, onFailure ->
                val app = checkNotNull(appContext)
                val scheduler = NotificationRecoveryWorkManager(
                    WorkManager.getInstance(app), IncomingNotificationRecoveryWorker::class.java,
                )
                NotificationRecoveryOperationObserver.watch(scheduler.oneOff(id).result, onFailure)
                true
            },
            recordFailure = { Log.w(TAG, "Local notification work scheduling failed") },
        )

        /** Call after the Room commit. No message or recipient goes into WorkManager data. */
        fun enqueue(context: Context) {
            appContext = context.applicationContext
            if (!scheduleGate.request()) Log.w(TAG, "Local notification recovery scheduling deferred")
        }

        /** Startup installs independent periodic repair as well as an initial pass. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            appContext = app
            val scheduler = NotificationRecoveryWorkManager(
                WorkManager.getInstance(app), IncomingNotificationRecoveryWorker::class.java,
            )
            NotificationRecoveryOperationObserver.watch(scheduler.periodic().result) {
                Log.w(TAG, "Periodic notification recovery scheduling failed")
            }
            enqueue(app)
        }
    }
}

/** Private scheduling hint only; never copied by the app's profile backup. */
internal class NotificationRecoveryCheckpoint(private val prefs: SharedPreferences) {
    fun read(owner: String): IncomingNotificationSweep.Cursor = try {
        if (prefs.getString("owner", null) != ownerHash(owner) || !prefs.getBoolean("positioned", false) ||
            !prefs.contains("after") || !prefs.contains("through")
        ) {
            IncomingNotificationSweep.Cursor()
        } else {
            IncomingNotificationSweep.Cursor(
                prefs.getLong("after", 0L), prefs.getLong("through", 0L),
                prefs.getBoolean("hadFailure", false), prefs.getBoolean("hadBlocked", false),
            ).takeIf { it.valid() } ?: IncomingNotificationSweep.Cursor()
        }
    } catch (_: ClassCastException) {
        // This is just a scheduling hint; a malformed type means a full rescan.
        IncomingNotificationSweep.Cursor()
    }

    fun save(owner: String, cursor: IncomingNotificationSweep.Cursor): Boolean {
        require(cursor.valid())
        val editor = prefs.edit().putString("owner", ownerHash(owner))
            .putBoolean("positioned", cursor.through != null)
            .putBoolean("hadFailure", cursor.hadFailure).putBoolean("hadBlocked", cursor.hadBlocked)
        if (cursor.through == null) {
            editor.remove("after").remove("through")
        } else {
            editor.putLong("after", requireNotNull(cursor.after)).putLong("through", cursor.through)
        }
        return editor.commit()
    }

    private fun ownerHash(owner: String): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
