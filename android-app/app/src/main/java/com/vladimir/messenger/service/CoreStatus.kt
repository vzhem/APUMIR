package com.vladimir.messenger.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Раунд 263: честный статус холодного старта ядра для интерфейса.
 * Сервис публикует этапы подъёма, сплэш и экраны показывают, что происходит,
 * вместо «пустого приложения, которое будто зависло». Объект живёт в процессе:
 * при тёплом старте (ядро уже поднято) заставка вообще не показывается.
 */
object CoreStatus {

    private val _stage = MutableStateFlow("Поднимаем ядро…")
    /** Текущий этап человеческими словами. */
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** UI may continue even when the engine did not start (limited or mirror mode). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _engineStarted = MutableStateFlow(false)
    /** The current service completed a successful engine start; not a live native health check. */
    internal val engineStarted: StateFlow<Boolean> = _engineStarted.asStateFlow()

    fun report(text: String) {
        _stage.value = text
        when (text) {
            "Готовим ключи безопасности…" -> com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics.progress.preparingKeys()
            "Поднимаем ядро и сеть…" -> com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics.progress.coreRequested()
        }
    }

    internal fun markStarting() {
        com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics.progress.start()
        _stage.value = "Поднимаем ядро…"
        _engineStarted.value = false
        _ready.value = false
    }

    internal fun markReady(engineStarted: Boolean) {
        com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics.progress.finished()
        _engineStarted.value = engineStarted
        if (engineStarted) {
            com.vladimir.messenger.data.diagnostics.OperationalDiagnostics.tracker.coreReady()
            com.vladimir.messenger.data.diagnostics.OperationalDiagnostics.phase(com.vladimir.messenger.data.diagnostics.DiagnosticPhase.CORE_READY)
        }
        _ready.value = true
    }

    internal fun markStopped() {
        _engineStarted.value = false
        com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics.progress.stopped()
    }
}
