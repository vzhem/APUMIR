package com.vladimir.messenger.data.push

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge

/**
 * In-process nudge of the transport when a signal finds the app already alive.
 *
 * Why this exists. A provider signal used to be answered only with a
 * WorkManager job ([com.vladimir.messenger.worker.RelayWakeWorker]). That is the
 * right tool when the process is dead: the platform gives the job a bounded
 * window. It is the wrong tool when the signal arrives at a running process,
 * because the job can still be deferred by Doze or by the scheduler, so the
 * message waits for the next periodic run even though the engine is up now.
 *
 * What it does: asks the *running* engine to look for its peers and to redo the
 * bounded discovery pass that fills the address book. Both calls already exist
 * and are used elsewhere (`ForegroundRecovery`, lifecycle callbacks); this adds
 * no new native entry point, no service start, no notification and no wake lock.
 *
 * What it does not do: it claims nothing. It does not read a mailbox, send
 * anything, retry a message or prove delivery. A `false` result means "there is
 * no running engine to nudge", which is the caller's cue to fall back to the
 * worker rather than pretend the phone was woken.
 */
internal object PushCoreWake {
    private const val TAG = "PushCoreWake"

    /** True when an already-running engine accepted the nudge. */
    fun nudge(context: Context): Boolean {
        return try {
            if (!RustBridge.isRunning()) return false
            // Order matters: discovery first, so the presence pass that follows
            // has fresh endpoints to work with.
            val discovery = RustBridge.triggerGossipDiscovery()
            val recovery = RustBridge.onForegroundRecovery()
            discovery || recovery
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the signal, the peer or exception text.
            Log.w(TAG, "in-process wake unavailable")
            false
        }
    }
}
