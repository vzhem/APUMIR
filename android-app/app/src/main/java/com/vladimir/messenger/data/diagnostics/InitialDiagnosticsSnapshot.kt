package com.vladimir.messenger.data.diagnostics

import com.vladimir.messenger.BuildConfig

/** Available before any Room, Keystore, logcat or native query can block. */
internal object InitialDiagnosticsSnapshot {
    fun create(createdAtMs: Long, sessionStartedAtMs: Long, journal: List<DiagnosticsJournalEntry>): TransferDiagnostics.Snapshot {
        val report = buildString {
            appendLine("[предварительный отчёт: сбор ещё идёт]")
            appendLine("Это только локальный срез стадий и журнала. Состояние сети, БД и ядра ещё не проверено.")
            appendLine("build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append(ServiceStartupDiagnostics.report())
            append(OperationalDiagnostics.journalReport())
            appendLine("[журнал] (последние события этой сессии)")
            journal.takeLast(40).forEach { entry ->
                appendLine("${DiagnosticsReport.clock(entry.atMs)} ${entry.level.name} ${entry.area}: ${entry.detail.take(200)}")
            }
        }
        return TransferDiagnostics.Snapshot(
            statusLines = listOf(DiagnosticsLine(DiagnosticsLevel.INFO, "Отчёт", "сбор ещё идёт; состояние ядра не проверено")),
            report = report,
            createdAtMs = createdAtMs,
            journalSize = journal.size,
            warnCount = journal.count { it.level == DiagnosticsLevel.WARN },
            badCount = journal.count { it.level == DiagnosticsLevel.BAD },
            uptimeMs = (createdAtMs - sessionStartedAtMs).coerceAtLeast(0),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            events = journal.asReversed().take(40),
        )
    }
}
