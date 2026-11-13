package com.vladimir.messenger.data.diagnostics

import android.os.SystemClock

/** Fixed stage labels and elapsed time only; never accesses the engine, database or Keystore. */
internal object ApplicationStartupDiagnostics {
    val progress = ApplicationStartupProgress { SystemClock.elapsedRealtime() }

    fun report(): String {
        val sample = progress.snapshot()
        fun ms(phase: ApplicationStartupProgress.Phase): Long = sample.phaseMs[phase] ?: 0L
        return "[запуск процесса: Application.onCreate]\n" +
            "application_phase=${sample.phase.name.lowercase()} · application_elapsed_ms=${sample.totalMs ?: -1}\n" +
            "application_injection_ms=${ms(ApplicationStartupProgress.Phase.INJECTION)} · " +
            "application_preparing_ms=${ms(ApplicationStartupProgress.Phase.PREPARING)} · " +
            "application_restore_ms=${ms(ApplicationStartupProgress.Phase.RESTORING)} · " +
            "application_identity_history_ms=${ms(ApplicationStartupProgress.Phase.IDENTITY_HISTORY)} · " +
            "application_obsolete_cleanup_ms=${ms(ApplicationStartupProgress.Phase.OBSOLETE_CLEANUP)} · " +
            "application_after_cleanup_ms=${ms(ApplicationStartupProgress.Phase.AFTER_CLEANUP)}\n" +
            "Время включает сон; отсчёт начинается при входе в Application.onCreate, " +
            "не включает создание процесса до него. Данные текущего процесса могут потеряться при убийстве.\n"
    }
}
