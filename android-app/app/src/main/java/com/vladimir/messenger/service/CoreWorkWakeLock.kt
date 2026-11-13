package com.vladimir.messenger.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics

/**
 * Бодрствование CPU только на время реальной работы ядра.
 *
 * Замок всегда с таймаутом. Параллельные аренды и системные переходы
 * сериализованы вместе: один обработчик не снимет замок у другого.
 * После остановки службы новые аренды больше не выдаются.
 */
class CoreWorkWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "P2PMessenger::CoreWorkWakeLock",
    ).apply { setReferenceCounted(false) }
    private val leases = WorkWakeLeases(
        platformIsHeld = { wakeLock.isHeld },
        platformAcquire = {
            wakeLock.acquire(WORK_WAKE_LOCK_MS)
            OperationalDiagnostics.wake.acquire(SystemClock.elapsedRealtime(), WORK_WAKE_LOCK_MS)
        },
        platformRelease = {
            if (wakeLock.isHeld) wakeLock.release()
            OperationalDiagnostics.wake.release(SystemClock.elapsedRealtime())
        },
        recordFailure = { operation, error ->
            Log.w(TAG, "work wake lock $operation failed", error)
        },
    )

    /** Взять аренду; её закрытие идемпотентно. */
    fun acquire(): AutoCloseable = leases.acquire()

    suspend fun <T> withLock(block: suspend () -> T): T {
        val lease = acquire()
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    /** Снять всё при окончательной остановке этой службы. */
    fun releaseAll() = leases.releaseAll()

    companion object {
        private const val TAG = "CoreWorkWakeLock"

        /** Потолок одной аренды: новая работа после таймаута возьмёт замок снова. */
        const val WORK_WAKE_LOCK_MS = 5 * 60 * 1000L
    }
}
