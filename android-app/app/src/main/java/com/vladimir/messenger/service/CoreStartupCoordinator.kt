package com.vladimir.messenger.service

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.DiagnosticPhase
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.ServiceStartupDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The service takes precedence over an optional worker-owned relay wait. */
internal object CoreStartupCoordinator {
    private val gate = CoreServiceGate()

    fun serviceCreated(owner: Any) {
        RustBridge.requestRelayWakeYield()
        gate.activate(owner) {
            OperationalDiagnostics.tracker.coreStarting()
            CoreStatus.markStarting()
            OperationalDiagnostics.phase(DiagnosticPhase.SERVICE_START)
        }
    }

    fun report(owner: Any, text: String) {
        gate.ifCurrent(owner) { CoreStatus.report(text) }
    }

    /** UI readiness can be limited/mirror mode; only the successful path marks an engine start. */
    fun markReady(owner: Any, engineStarted: Boolean = false) {
        gate.ifCurrent(owner) { CoreStatus.markReady(engineStarted) }
    }

    /** Retire before queueing shutdown, so stale work cannot start an engine after that stop. */
    fun serviceDestroyed(owner: Any) {
        gate.retire(owner) { CoreStatus.markStopped() }
    }

    suspend fun initialize(owner: Any, name: String, publicKey: String, privateKey: String?, path: String): Boolean {
        currentCoroutineContext().ensureActive()
        val token = gate.ifCurrent(owner) { ServiceStartupDiagnostics.progress.currentToken() }
            ?: throw CancellationException("Service startup is no longer active")
        val started = RustBridge.initialize(name, publicKey, privateKey, path, token) { gate.isCurrent(owner) }
        // The blocking native call may return after onDestroy or after a newer service starts.
        currentCoroutineContext().ensureActive()
        if (!gate.isCurrent(owner)) throw CancellationException("Service startup was superseded")
        return started
    }

    suspend fun nodeId(owner: Any): String? {
        val id = RustBridge.nodeId()
        currentCoroutineContext().ensureActive()
        if (!gate.isCurrent(owner)) throw CancellationException("Service startup was superseded")
        return id
    }
}
