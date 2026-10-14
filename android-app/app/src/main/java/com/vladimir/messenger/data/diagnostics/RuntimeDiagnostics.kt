package com.vladimir.messenger.data.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Process-local bounded health metrics. No message/identity data or stack traces. */
object RuntimeDiagnostics {
    val operations = OperationHealthTracker { SystemClock.uptimeMillis() }
    private val ui = UiResponsivenessTracker()
    private val main = Handler(Looper.getMainLooper())
    private val probeToken = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "apu-ui-health").apply { isDaemon = true }
    }
    private val trimCount = AtomicLong()
    @Volatile private var trimLevel: Int? = null
    private var installed = false

    @Synchronized fun install(app: Application) {
        if (installed) return
        installed = true
        val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val gate = Any()
        val generation = AtomicLong()
        var scheduled: ScheduledFuture<*>? = null
        var lastWarningAt = 0L
        fun stop() {
            scheduled?.cancel(false)
            scheduled = null
            synchronized(gate) {
                generation.incrementAndGet()
                ui.visible(false)
                main.removeCallbacksAndMessages(probeToken)
            }
        }
        fun update() {
            if (started.isEmpty()) { stop(); return }
            if (scheduled != null) return
            val epoch = generation.incrementAndGet()
            scheduled = timer.scheduleWithFixedDelay({
                runCatching {
                    val interactive = power?.isInteractive == true
                    val now = SystemClock.uptimeMillis()
                    val overdue = synchronized(gate) {
                        if (generation.get() != epoch) return@runCatching
                        ui.visible(interactive)
                        if (!interactive) {
                            main.removeCallbacksAndMessages(probeToken)
                            return@runCatching
                        }
                        val overdue = ui.checkOverdue(now)
                        val id = ui.begin(now)
                        if (id != null && !main.postAtTime({ ui.complete(id, SystemClock.uptimeMillis()) }, probeToken, now)) ui.visible(false)
                        overdue
                    }
                    if (overdue != null && (lastWarningAt == 0L || now - lastWarningAt >= 60_000L)) {
                        lastWarningAt = now
                        TransferDiagnostics.recordWarning("health", "главный поток не ответил на пробу ${overdue}мс; это не подтверждённый ANR")
                    }
                }
            }, 0, 1000, TimeUnit.MILLISECONDS)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started.add(activity); update() }
            override fun onActivityStopped(activity: Activity) { started.remove(activity); update() }
            override fun onActivityDestroyed(activity: Activity) { started.remove(activity); update() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    fun memoryTrim(level: Int) { trimCount.incrementAndGet(); trimLevel = level }

    suspend fun <T> measure(operation: DiagnosticOperation, block: suspend () -> T): T {
        val ticket = operations.begin(operation)
        var outcome = DiagnosticOutcome.OK
        try {
            return block()
        } catch (cancelled: CancellationException) {
            outcome = DiagnosticOutcome.CANCELLED
            throw cancelled
        } catch (error: Throwable) {
            outcome = DiagnosticOutcome.FAILED
            throw error
        } finally {
            operations.finish(ticket, outcome)
        }
    }

    fun snapshot(context: Context): RuntimeHealthSnapshot {
        val exits = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 3).map { info ->
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> ProcessExitReason.ANR
                    ApplicationExitInfo.REASON_CRASH -> ProcessExitReason.CRASH
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitReason.NATIVE_CRASH
                    ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitReason.LOW_MEMORY
                    ApplicationExitInfo.REASON_USER_REQUESTED -> ProcessExitReason.USER_REQUESTED
                    else -> ProcessExitReason.OTHER
                }
                PreviousProcessExit(reason, (System.currentTimeMillis() - info.timestamp).coerceAtLeast(0), info.importance)
            }
        }.getOrNull() else null
        val memory = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss }.getOrNull()
        return RuntimeHealthSnapshot(ui.snapshot(SystemClock.uptimeMillis()), operations.snapshot(), operations.dropped,
            runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull(), memory, trimCount.get(), trimLevel,
            exits.orEmpty(), if (Build.VERSION.SDK_INT < 30) ExitHistoryStatus.UNSUPPORTED
                else if (exits == null) ExitHistoryStatus.UNAVAILABLE else ExitHistoryStatus.READY)
    }
}
