package com.vladimir.messenger.data.push

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.diagnostics.PushPhase
import com.vladimir.messenger.data.diagnostics.PushSnapshot
import com.vladimir.messenger.data.diagnostics.PushWakeOutcome

/**
 * The smallest possible record of what the push path did, so a report from a
 * phone can answer "did a signal arrive at all?" without a cable and logcat.
 *
 * This exists because a two-phone test failed with a report that mentioned
 * nothing about push: reception was off in that build and every inbound
 * callback returned before writing anything, so the absence of evidence looked
 * exactly like a quiet, healthy app. A feature that can be silently absent must
 * say so in the report.
 *
 * What is stored (plain preferences, no encryption needed because nothing here
 * is secret): counters, timestamps and fixed enum names. What is never stored: a
 * token, a fingerprint, a route, a peer id, a node id, a chat id, message text or
 * an exception message. A route is a capability digest and a token addresses
 * this installation; neither belongs in a file the owner may share.
 *
 * Every write is best effort. Diagnostics must never be the reason delivery
 * fails, so a broken preferences file degrades to "no counters" and nothing else.
 */
object PushDiagnostics {

    private const val FILE = "apu_push_diag_v1"
    private const val TAG = "PushDiagnostics"

    fun noteReception(enabled: Boolean) = write(if (enabled) "receive=1" else "receive=0")

    fun noteRegistration(phase: PushPhase) = write(
        "phase=${phase.name}",
        "lastRegisterAt=${System.currentTimeMillis()}",
    )

    fun noteSignal(accepted: Boolean) = write(
        if (accepted) "signals=+1" else "signalsRejected=+1",
        "lastSignalAt=${System.currentTimeMillis()}",
    )

    fun noteWake(wake: PushWakeOutcome) = write(
        "wakeAttempts=+1",
        "lastWake=${wake.name}",
        "lastWakeAt=${System.currentTimeMillis()}",
    )

    fun noteInbox(pending: String) = write("inbox=$pending")

    /** Whether the public health route answered; null until it was probed. */
    fun noteEndpoint(reachable: Boolean) = write("endpoint=${if (reachable) "ok" else "no"}")

    /** Fixed vocabulary only: never an exception message, never a path. */
    fun noteError(kind: String) = write("lastError=${kind.take(40)}")

    /**
     * Read the stored record plus the facts that can be read live. Never throws:
     * a report built while the push files are unreadable must still render.
     */
    fun snapshot(
        context: Context,
        gateOpen: Boolean,
        receiveEnabled: Boolean,
        endpointConfigured: Boolean,
    ): PushSnapshot {
        val app = context.applicationContext
        val stored = try {
            app.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
        } catch (_: Exception) {
            // Unreadable store: the report shows defaults instead of failing.
            Log.w(TAG, "push counters unreadable")
            emptyMap<String, Any?>()
        }
        fun text(key: String): String? = stored[key] as? String
        fun number(key: String): Long = (stored[key] as? Long) ?: 0L
        return PushSnapshot(
            gateOpen = gateOpen,
            receiveEnabled = receiveEnabled,
            tokenRecorded = tokenPresent(app),
            endpointConfigured = endpointConfigured,
            registeredRoutes = routeCount(app),
            phase = runCatching { PushPhase.valueOf(text("phase") ?: "UNKNOWN") }.getOrDefault(PushPhase.UNKNOWN),
            lastRegisterAtMs = number("lastRegisterAt"),
            signalsReceived = number("signals"),
            signalsRejected = number("signalsRejected"),
            lastSignalAtMs = number("lastSignalAt"),
            wakeAttempts = number("wakeAttempts"),
            lastWake = runCatching { PushWakeOutcome.valueOf(text("lastWake") ?: "NONE") }
                .getOrDefault(PushWakeOutcome.NONE),
            lastWakeAtMs = number("lastWakeAt"),
            inboxPending = text("inbox") ?: "?",
            endpointReachable = when (text("endpoint")) {
                "ok" -> true
                "no" -> false
                else -> null
            },
            lastError = text("lastError") ?: "-",
        )
    }

    /** True when a token fingerprint was stored; false also on any read failure. */
    private fun tokenPresent(app: Context): Boolean = try {
        PushServiceLocator.sharedOrNull(app)?.journals?.tokens?.present() ?: false
    } catch (_: Exception) {
        // A missing journal is reported as "no token", never as a crash.
        Log.w(TAG, "token journal unreadable")
        false
    }

    /** Number of indexed routes, or null when the index cannot be read. */
    private fun routeCount(app: Context): Int? = try {
        PushServiceLocator.sharedOrNull(app)?.journals?.registrations?.indexedRoutes()
    } catch (_: Exception) {
        // Null means "cannot tell", which the report prints as such.
        Log.w(TAG, "route index unreadable")
        null
    }

    /** Accumulating writes are a read-modify-write on purpose: volume is tiny. */
    private fun write(vararg updates: String) {
        try {
            val app = appContext ?: return
            val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (update in updates) {
                val separator = update.indexOf('=')
                if (separator <= 0) continue
                val key = update.substring(0, separator)
                val raw = update.substring(separator + 1)
                if (raw.startsWith("+")) {
                    val delta = raw.drop(1).toLongOrNull() ?: continue
                    editor.putLong(key, (prefs.getLong(key, 0L) + delta).coerceAtLeast(0L))
                } else if (key in KEY_IS_NUMBER && raw.toLongOrNull() != null) {
                    editor.putLong(key, raw.toLong())
                } else {
                    editor.putString(key, raw.take(40))
                }
            }
            editor.apply()
        } catch (_: Exception) {
            // Diagnostics is never allowed to break a delivery attempt.
            Log.w(TAG, "push diagnostics unavailable")
        }
    }

    private val KEY_IS_NUMBER = setOf("lastRegisterAt", "lastSignalAt", "lastWakeAt")

    /**
     * Set once per process by [install]; the writers are called from deep inside
     * the runtime where a Context is not always at hand.
     */
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }
}
