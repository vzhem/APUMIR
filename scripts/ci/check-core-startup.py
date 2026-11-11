#!/usr/bin/env python3
"""Wiring guards, not a substitute for Android lifecycle or device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def text(path):
    return (APP / path).read_text(encoding="utf-8")


class CoreStartupContracts(unittest.TestCase):
    def test_service_signals_yield_before_preparing_keys(self):
        service = text("service/CoreServerService.kt")
        create = service[service.index("override fun onCreate()"):service.index("override fun onStartCommand")]
        self.assertIn("CoreStartupCoordinator.serviceCreated(this)", create)
        coordinator = text("service/CoreStartupCoordinator.kt")
        self.assertLess(coordinator.index("RustBridge.requestRelayWakeYield()"), coordinator.index("CoreStatus.markStarting()"))

    def test_worker_keeps_serial_ownership_and_yields_only_the_wait(self):
        bridge = text("data/RustBridge.kt")
        init = bridge[bridge.index("    fun initialize("):bridge.index("    internal fun signingRouteCompatible")]
        self.assertLess(init.index("requestRelayWakeYield()"), init.index("lifecycle.start(serviceOwner, startupAllowed)"))
        queue = text("data/CoreLifecycleQueue.kt")
        self.assertLess(queue.index("allowed?.invoke()"), queue.index("val result = block()"))
        self.assertIn("if (result.engineReplaced)", queue)
        self.assertIn("engineOwner = if (result.enginePresent) owner ?: nonServiceOwner else null", queue)
        self.assertIn("result.started && (owner != null || engineOwner == null)", queue)
        self.assertIn("CoreLifecycleQueue.StartResult(started, engine !== previous, engine != null)", init)
        self.assertIn("if (engineOwner === owner)", queue)
        self.assertIn("lifecycle.stopIfOwned(owner) { shutdownLocked() }", bridge)
        self.assertIn("lifecycle.clearOwner()", bridge)
        coordinator = text("service/CoreStartupCoordinator.kt")
        self.assertIn("path, token, owner) { gate.isCurrent(owner) }", coordinator)
        service = text("service/CoreServerService.kt")
        destroy = service[service.index("override fun onDestroy()"):service.index("override fun onTaskRemoved")]
        self.assertLess(destroy.index("ServiceLifecycleHooks.onDestroying(this)"), destroy.index("RustBridge.shutdownServiceAsync(this)"))
        self.assertNotIn("RustBridge.shutdownAsync()", destroy)
        hooks = text("service/ServiceLifecycleHooks.kt")
        self.assertIn("CoreStartupCoordinator.serviceDestroyed(owner)", hooks)
        wake = bridge[bridge.index("    fun runBoundedRelayWake("):bridge.index("    fun isRunningCached()")]
        self.assertLess(wake.index("relayWakeYield.snapshot()"), wake.index("onLifecycleThread {"))
        self.assertLess(wake.index("relayWakeYield.requestedSince(yieldToken)"), wake.index("initializeLocked("))
        self.assertLess(wake.index("initializeLocked("), wake.index("relayWakeYield.await("))
        self.assertIn('if (relayWakeYield.requestedSince(yieldToken)) return@onLifecycleThread RelayWakeResult(true, false, "yielded", 0L)', wake)
        self.assertIn('custodyMode = if (yielded) "yielded" else relayCustodyMode()', wake)
        self.assertIn('quarantineCount = if (yielded) 0L else relayQuarantineCount()', wake)
        self.assertIn("if (ex is InterruptedException) throw ex", wake)
        self.assertIn("finally {\n                        shutdownLocked()", wake)
        worker = text("worker/RelayWakeWorker.kt")
        self.assertIn('if (wake.custodyMode == "yielded")', worker)
        self.assertIn("lifecycle cleanup may still be running", worker)
        self.assertNotIn("Thread.sleep(", wake)
        self.assertIn("newSingleThreadExecutor", queue)
        self.assertIn("generation !== token", text("data/RelayWakeYield.kt"))

    def test_application_start_timing_covers_pre_service_cleanup(self):
        app = text("MessengerApplication.kt")
        create = app[app.index("override fun onCreate()"):app.index("override fun onTrimMemory")]
        ordered = ["ApplicationStartupDiagnostics.progress.start()", "super.onCreate()",
                   "Phase.PREPARING", "Phase.RESTORING", "ProfileBackup.applyStagedIfAny",
                   "DeviceIdentityMarker.discardIfRestored", "Phase.IDENTITY_HISTORY",
                   "Phase.OBSOLETE_CLEANUP", "obsoleteSameDeviceRegistrationCleanup.runForCurrentBuild()",
                   "Phase.AFTER_CLEANUP", "ApplicationStartupDiagnostics.progress.finish()"]
        positions = [create.index(item) for item in ordered]
        self.assertEqual(sorted(positions), positions)
        report = text("data/diagnostics/ApplicationStartupDiagnostics.kt")
        self.assertIn("application_obsolete_cleanup_ms", report)
        self.assertNotIn("RustBridge", report)
        self.assertNotIn("KeyStore", report)

    def test_preliminary_report_is_available_before_native_or_database_queries(self):
        collector = text("data/diagnostics/TransferDiagnostics.kt")
        collect = collector[collector.index("    suspend fun collect("):collector.index("    private fun snapshotOf(")]
        self.assertLess(collect.index("InitialDiagnosticsSnapshot.create("), collect.index("gatherFacts("))
        initial = text("data/diagnostics/InitialDiagnosticsSnapshot.kt")
        self.assertIn("сбор ещё идёт", initial)
        self.assertNotIn("RustBridge", initial)
        self.assertNotIn("KeyStore", initial)
        self.assertNotIn("fileTransferDao", initial)
        self.assertIn("ApplicationStartupDiagnostics.report()", initial)
        self.assertIn("ServiceStartupDiagnostics.report()", initial)

    def test_splash_readiness_alone_cannot_claim_a_running_engine(self):
        stage = text("data/diagnostics/CoreDiagnosticStage.kt")
        self.assertIn("ready && running", stage)
        self.assertIn("!facts.coreRunningKnown", stage)
        self.assertIn("состояние ядра неизвестно", stage)
        self.assertIn("ядро не запущено", stage)
        progress = text("data/diagnostics/ServiceStartupProgress.kt")
        self.assertIn("expected === token", progress)
        self.assertIn("Phase.STOPPED", progress)
        self.assertIn("expected !== token", progress)
        status = text("service/CoreStatus.kt")
        self.assertLess(status.index("if (engineStarted)"), status.index("tracker.coreReady()"))
        runtime = text("data/diagnostics/RuntimeDiagnostics.kt")
        self.assertIn("CoreStatus.engineStarted.value && com.vladimir.messenger.data.RustBridge.isRunningCached()", runtime)
        coordinator = text("service/CoreStartupCoordinator.kt")
        self.assertIn("gate.ifCurrent(owner) { CoreStatus.markReady(engineStarted) }", coordinator)
        self.assertIn("gate.retire(owner) {", coordinator)
        self.assertIn("CoreStatus.markStopped()", coordinator)
        self.assertIn("OperationalDiagnostics.phase(DiagnosticPhase.SERVICE_STOP)", coordinator)
        self.assertIn("currentCoroutineContext().ensureActive()", coordinator)


if __name__ == "__main__":
    unittest.main(verbosity=2)
