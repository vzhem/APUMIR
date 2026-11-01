package com.vladimir.messenger.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.vladimir.messenger.data.call.CallIncoming
import com.vladimir.messenger.data.call.CallStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Бодрствование CPU на время звонка — и только на время звонка.
 *
 * Прежде эту работу делал постоянный замок службы: он не отпускался и после
 * того как трубку положили. Здесь замок живёт ровно пока фаза звонка активна
 * (гудки, соединение, разговор), продлевается не чаще раза в
 * [CallIncoming.CORE_WAKE_REFRESH_MS] и снимается на ENDED/IDLE. Таймаут
 * страховки — [CallIncoming.CORE_WAKE_LOCK_MS].
 */
class CallWorkWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "P2PMessenger::CallWorkWakeLock",
    ).apply { setReferenceCounted(false) }

    private var job: Job? = null

    /** Вызывается из наблюдателя фазы звонка; повторные вызовы безопасны. */
    @Synchronized fun setPhase(phase: CallStateMachine.Phase, scope: CoroutineScope) {
        val active = isActive(phase)
        if (!active) {
            stop()
            return
        }
        if (job?.isActive == true) return
        acquire()
        job = scope.launch {
            while (isActive) {
                delay(CallIncoming.CORE_WAKE_REFRESH_MS)
                acquire()
            }
        }
    }

    private fun acquire() {
        runCatching {
            wakeLock.acquire(CallIncoming.CORE_WAKE_LOCK_MS)
        }.onFailure { Log.w(TAG, "call wake lock acquire failed: ${it.message}") }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure { Log.w(TAG, "call wake lock release failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "CallWorkWakeLock"

        /** Замок нужен только живому звонку; IDLE/ENDED отпускают CPU. */
        fun isActive(phase: CallStateMachine.Phase): Boolean =
            phase != CallStateMachine.Phase.IDLE && phase != CallStateMachine.Phase.ENDED
    }
}
