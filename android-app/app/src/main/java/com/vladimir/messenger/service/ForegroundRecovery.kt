package com.vladimir.messenger.service

import android.os.SystemClock
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Decide from elapsed real time (including deep sleep), not wall-clock time. */
internal class ForegroundRecoveryGate(private val minimumBackgroundMs: Long = 60_000L) {
    private var backgroundAt: Long? = null

    @Synchronized fun background(nowMs: Long) {
        if (backgroundAt == null) backgroundAt = nowMs
    }

    @Synchronized fun foreground(nowMs: Long): Boolean {
        val since = backgroundAt ?: return false
        backgroundAt = null
        return nowMs >= since && nowMs - since >= minimumBackgroundMs
    }
}

/**
 * A long sleep may leave sockets or NAT mappings stale even if Android reports
 * the same network. Kick bounded discovery/presence once when the UI returns;
 * do not destroy healthy sessions, restart the service or hold a wake lock.
 * A cold start already runs normal discovery and has no saved background time.
 */
internal object ForegroundRecovery {
    private val gate = ForegroundRecoveryGate()

    fun onBackground() = gate.background(SystemClock.elapsedRealtime())

    fun onForeground(scope: CoroutineScope) {
        if (!gate.foreground(SystemClock.elapsedRealtime())) return
        scope.launch(Dispatchers.IO) {
            if (!DeliveryCadence.isForeground() || !RustBridge.isRunning()) return@launch
            val queued = RustBridge.onForegroundRecovery()
            Log.i("ForegroundRecovery", "Long-background discovery requested: queued=$queued")
        }
    }
}
