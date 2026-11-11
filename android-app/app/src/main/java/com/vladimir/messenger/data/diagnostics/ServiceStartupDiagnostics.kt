package com.vladimir.messenger.data.diagnostics

import android.os.SystemClock

/** Never accesses the engine or Keystore; safe to read while either is stalled. */
internal object ServiceStartupDiagnostics {
    val progress = ServiceStartupProgress { SystemClock.elapsedRealtime() }

    fun report(): String {
        val sample = progress.snapshot()
        fun ms(phase: ServiceStartupProgress.Phase): Long = sample.phaseMs[phase] ?: 0L
        return "[запуск службы: текущий процесс]\n" +
            "startup_phase=${sample.phase.name.lowercase()} · startup_elapsed_ms=${sample.totalMs ?: -1}\n" +
            "preparing_keys_ms=${ms(ServiceStartupProgress.Phase.PREPARING_KEYS)} · " +
            "waiting_lifecycle_ms=${ms(ServiceStartupProgress.Phase.WAITING_LIFECYCLE)} · " +
            "initializing_engine_ms=${ms(ServiceStartupProgress.Phase.INITIALIZING_ENGINE)}\n" +
            "Время включает сон устройства; отсчёт начинается с создания службы, не с Application.onCreate. " +
            "FINISHED означает завершение заставки, не подтверждение работающего ядра.\n"
    }
}
