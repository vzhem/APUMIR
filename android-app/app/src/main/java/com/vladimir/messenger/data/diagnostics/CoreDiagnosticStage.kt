package com.vladimir.messenger.data.diagnostics

/** UI readiness also covers limited/mirror modes; it is not proof of a running engine. */
internal object CoreDiagnosticStage {
    fun describe(ready: Boolean, running: Boolean, known: Boolean, stage: String): String = when {
        !known -> "состояние ядра неизвестно; последний этап: ${stage.ifBlank { "нет данных" }}"
        ready && running -> "готово (ядро поднято)"
        !ready -> "${stage.ifBlank { "нет данных" }} → ещё поднимается"
        else -> "ядро не запущено; последний этап: ${stage.ifBlank { "нет данных" }}"
    }

    fun summary(facts: DiagnosticsFacts): DiagnosticsLine = when {
        !facts.coreRunningKnown -> DiagnosticsLine(DiagnosticsLevel.WARN, "Ядро", "состояние неизвестно: проверка не удалась")
        facts.coreRunning -> DiagnosticsLine(DiagnosticsLevel.OK, "Ядро", "работает, пиров ${facts.connectedPeers}, очередь событий ${facts.pendingEvents}")
        else -> DiagnosticsLine(DiagnosticsLevel.BAD, "Ядро", "не запущено (${facts.coreStage.ifBlank { "нет этапа" }})")
    }
}
