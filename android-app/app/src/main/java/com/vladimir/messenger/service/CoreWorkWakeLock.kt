package com.vladimir.messenger.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Бодрствование CPU только на время реальной работы ядра.
 *
 * Раньше служба держала PARTIAL_WAKE_LOCK постоянно и продлевала его каждые
 * восемь минут: телефон не засыпал даже когда приложение просто открыто и
 * никто ничего не делает. Теперь замок берётся на обработку пачки событий и
 * на круг файлового насоса, а в простое отпускается. Сеть и будильники
 * доставки ([DeliveryAssurance], [EmergencyKeepAlive]) остаются страховкой.
 *
 * Замок всегда с таймаутом: если корутина умрёт не отпустив аренду, система
 * сама снимет его через [WORK_WAKE_LOCK_MS]. Несколько аренд считаются
 * счётчиком, чтобы параллельные насосы не снимали замок друг у друга.
 */
class CoreWorkWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "P2PMessenger::CoreWorkWakeLock",
    ).apply { setReferenceCounted(false) }
    private val registry = WakeLeaseRegistry()

    /** Взять аренду; закрывающий её [Lease.close] идемпотентен. */
    fun acquire(): Lease {
        val first = registry.acquire()
        if (first) {
            val ok = runCatching {
                wakeLock.acquire(WORK_WAKE_LOCK_MS)
                OperationalDiagnostics.wake.acquire(SystemClock.elapsedRealtime(), WORK_WAKE_LOCK_MS)
            }.onFailure {
                registry.release()
                Log.w(TAG, "work wake lock acquire failed: ${it.message}")
            }.isSuccess
            if (!ok) return Lease(active = false)
        }
        return Lease(active = true)
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        val lease = acquire()
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    /** Снять всё при остановке службы. */
    fun releaseAll() {
        registry.reset()
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
            OperationalDiagnostics.wake.release(SystemClock.elapsedRealtime())
        }.onFailure { Log.w(TAG, "work wake lock release failed: ${it.message}") }
    }

    inner class Lease internal constructor(private val active: Boolean) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!active || !closed.compareAndSet(false, true)) return
            val last = registry.release()
            if (last) {
                runCatching {
                    if (wakeLock.isHeld) wakeLock.release()
                    OperationalDiagnostics.wake.release(SystemClock.elapsedRealtime())
                }.onFailure { Log.w(TAG, "work wake lock lease release failed: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "CoreWorkWakeLock"

        /** Потолок одной аренды: работа дольше него должна взять новую. */
        const val WORK_WAKE_LOCK_MS = 5 * 60 * 1000L
    }
}

/** Чистый счётчик аренд без Android — проверяется JVM-тестом. */
class WakeLeaseRegistry {
    private var holders = 0

    val held: Boolean get() = holders > 0

    /** true, если это первая аренда (надо взять системный замок). */
    @Synchronized fun acquire(): Boolean {
        holders++
        return holders == 1
    }

    /** true, если ушла последняя аренда (надо снять системный замок). */
    @Synchronized fun release(): Boolean {
        if (holders == 0) return false
        holders--
        return holders == 0
    }

    @Synchronized fun reset() {
        holders = 0
    }
}
