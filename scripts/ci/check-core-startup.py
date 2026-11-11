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
        self.assertIn("CoreStartupCoordinator.serviceCreated()", create)
        coordinator = text("service/CoreStartupCoordinator.kt")
        self.assertLess(coordinator.index("RustBridge.requestRelayWakeYield()"), coordinator.index("CoreStatus.markStarting()"))

    def test_worker_keeps_serial_ownership_and_yields_only_the_wait(self):
        bridge = text("data/RustBridge.kt")
        init = bridge[bridge.index("    fun initialize("):bridge.index("    internal fun signingRouteCompatible")]
        self.assertLess(init.index("requestRelayWakeYield()"), init.index("onLifecycleThread {"))
        wake = bridge[bridge.index("    fun runBoundedRelayWake("):bridge.index("    fun isRunningCached()")]
        self.assertLess(wake.index("relayWakeYield.snapshot()"), wake.index("onLifecycleThread {"))
        self.assertLess(wake.index("relayWakeYield.requestedSince(yieldToken)"), wake.index("initializeLocked("))
        self.assertLess(wake.index("initializeLocked("), wake.index("relayWakeYield.await("))
        self.assertIn("finally {\n                        shutdownLocked()", wake)
        self.assertNotIn("Thread.sleep(", wake)
        self.assertIn("newSingleThreadExecutor", bridge)
        self.assertIn("generation !== token", text("data/RelayWakeYield.kt"))

    def test_preliminary_report_is_available_before_native_or_database_queries(self):
        collector = text("data/diagnostics/TransferDiagnostics.kt")
        collect = collector[collector.index("    suspend fun collect("):collector.index("    private fun snapshotOf(")]
        self.assertLess(collect.index("InitialDiagnosticsSnapshot.create("), collect.index("gatherFacts("))
        initial = text("data/diagnostics/InitialDiagnosticsSnapshot.kt")
        self.assertIn("сбор ещё идёт", initial)
        self.assertNotIn("RustBridge", initial)
        self.assertNotIn("KeyStore", initial)
        self.assertNotIn("fileTransferDao", initial)
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
