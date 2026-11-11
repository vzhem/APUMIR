package com.vladimir.messenger.service

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.DiagnosticPhase
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics

/** The service takes precedence over an optional worker-owned relay wait. */
internal object CoreStartupCoordinator {
    fun serviceCreated() {
        RustBridge.requestRelayWakeYield()
        OperationalDiagnostics.tracker.coreStarting()
        CoreStatus.markStarting()
        OperationalDiagnostics.phase(DiagnosticPhase.SERVICE_START)
    }
}
