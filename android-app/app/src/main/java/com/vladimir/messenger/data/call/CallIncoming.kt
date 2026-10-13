package com.vladimir.messenger.data.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.ui.navigation.NavCallEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Входящий звонок на спящем и заблокированном телефоне: экран, CallStyle,
 * кнопки «Принять»/«Отклонить» с уведомления. Офер всё равно должен дойти
 * по MQTT — без живого ядра система не узнает о звонке.
 */
object CallIncoming {
    const val ACTION_DECLINE = "com.vladimir.messenger.action.CALL_DECLINE"
    const val EXTRA_ANSWER = "apu_answer_call"
    const val CHANNEL_ID = "apu_calls_ring"

    const val CORE_WAKE_LOCK_MS = 10 * 60 * 1000L
    const val CORE_WAKE_REFRESH_MS = 8 * 60 * 1000L
    const val INCOMING_SCREEN_WAKE_MS = 45_000L

    fun isIncomingLaunch(openCallId: String?, answer: Boolean): Boolean =
        !openCallId.isNullOrBlank() || answer

    fun shouldRefreshWakeLock(held: Boolean, elapsedSinceAcquireMs: Long): Boolean {
        if (!held) return true
        return elapsedSinceAcquireMs >= CORE_WAKE_REFRESH_MS
    }

    /** Зажечь экран, пока полноэкранное уведомление поднимает активность. */
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "APU:IncomingCall",
            ).apply {
                setReferenceCounted(false)
                acquire(INCOMING_SCREEN_WAKE_MS)
            }
        }.onFailure { Log.w("CallIncoming", "wakeScreen: ${it.message}") }
    }
}

/** «Отклонить» с системного уведомления, без открытия экрана. */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CallIncoming.ACTION_DECLINE) return
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NavCallEntryPoint::class.java,
            ).callManager().hangupOrReject()
        }.onFailure { Log.w("CallIncoming", "decline: ${it.message}") }
    }
}

fun Intent.withIncomingCall(callId: String, answer: Boolean = false): Intent = apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
    putExtra(CallManager.EXTRA_OPEN_CALL, callId)
    if (answer) putExtra(CallIncoming.EXTRA_ANSWER, true)
}

fun incomingCallActivityIntent(context: Context, callId: String, answer: Boolean = false): Intent =
    Intent(context, MainActivity::class.java).withIncomingCall(callId, answer)
