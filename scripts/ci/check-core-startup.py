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
        self.assertIn("ServiceLifecycleHooks.onCreated(this)", create)
        hooks = text("service/ServiceLifecycleHooks.kt")
        self.assertIn("CoreStartupCoordinator.serviceCreated(owner)", hooks)
        self.assertIn("EmergencyKeepAlive.onServiceCreated(owner)", hooks)
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

    def test_failed_native_cleanup_blocks_a_second_engine_and_identity_change(self):
        bridge = text("data/RustBridge.kt")
        startup = bridge[bridge.index("    private fun initializeLocked("):bridge.index("    internal fun requestRelayWakeYield()")]
        self.assertIn("publicationLock = IdentitySigningKeyStore", bridge)
        self.assertLess(startup.index("engineSlot.unsettled()"), startup.index("createEngineDurable("))
        self.assertIn("if (!engineSlot.stop()) return false", startup)
        self.assertIn("return engineSlot.startNew(", startup)
        self.assertIn("IdentitySigningKeyStore.matchesInstalledRoute(existingPublicKey)", startup)
        self.assertLess(startup.index("applyOnlineHelperPolicy(e)"), startup.index("e.start()"))
        self.assertLess(startup.index("applyPathPolicy(e)"), startup.index("e.start()"))
        self.assertNotIn("e.relayCustodyMode()", startup)
        self.assertNotIn("e.relayQuarantineCount()", startup)
        self.assertIn("recordFailure = { error ->", startup)
        signing = bridge[bridge.index("    internal fun signingRouteCompatible("):bridge.index("    private fun initializeLocked(")]
        self.assertIn("synchronized(IdentitySigningKeyStore)", signing)
        self.assertIn("engineSlot.unsettled()", signing)
        self.assertIn("expected == signingEngineNodeId", signing)
        self.assertNotIn(".nodeId()", signing)  # no native lock while holding the identity monitor
        publish = startup[startup.index("            beforePublish = { e ->"):startup.index("            recordFailure = { error ->")]
        self.assertLess(publish.index("e.publicKey()"), publish.index("signingEngineNodeId = startedNodeId"))
        self.assertLess(publish.index("signingEngineNodeId = startedNodeId"), publish.index("engineRunning = true"))
        status = bridge[bridge.index("    fun isRunning(): Boolean"):bridge.index("    fun isNetworkUp()")]
        self.assertIn("if (!engineSlot.ifActive(current) { engineRunning = running }) return false", status)
        self.assertIn("if (current == null) return false", status)
        shutdown = bridge[bridge.index("    private fun shutdownLocked()"):bridge.index("    data class RelayWakeResult(")]
        self.assertIn("engineSlot.stop()", shutdown)
        diagnostics = bridge[bridge.index("    internal fun runningForDiagnostics()"):bridge.index("    fun isRunning()")]
        self.assertIn("if (engineSlot.unsettled()) null else false", diagnostics)
        slot = text("data/CoreEngineSlot.kt")
        start = slot[slot.index("    fun startNew("):slot.index("    fun stop(): Boolean")]
        self.assertLess(start.index("synchronized(publicationLock)"), start.index("!allow()"))
        self.assertLess(start.index("!allow()"), start.index("starting = true"))
        self.assertLess(start.index("starting = true"), start.index("create()"))
        self.assertLess(start.index("pending = handle"), start.index("prepareAndStart(handle)"))
        self.assertLess(start.index("beforePublish(handle)"), start.index("live = handle"))
        self.assertIn("if (claimed) stop()", start)
        stop = slot[slot.index("    fun stop(): Boolean"):]
        self.assertLess(stop.index("pending = candidate"), stop.index("live = null"))
        self.assertLess(stop.index("stop(handle)"), stop.index("pending = null"))
        self.assertIn("finally {", stop)
        self.assertIn("recordFailure(error)", stop)

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

    def test_engine_trace_uses_only_fixed_steps_without_changing_start_order(self):
        bridge = text("data/RustBridge.kt")
        init = bridge[bridge.index("    fun initialize("):bridge.index("    internal fun signingRouteCompatible")]
        self.assertIn("ServiceStartupDiagnostics.progress.lifecycleEntered(ticket)", init)
        self.assertIn("finally { ServiceStartupDiagnostics.progress.engineReturned(ticket) }", init)
        startup = bridge[bridge.index("    private fun initializeLocked("):bridge.index("    internal fun requestRelayWakeYield()")]
        for label, call in (
            ("CLEANING_PREVIOUS", "engineSlot.stop()"),
            ("RESERVING_SLOT", "engineSlot.startNew("),
            ("PREPARING_CORE", "initializeCore()"),
            ("CREATING_ENGINE", "createEngineDurable("),
            ("APPLYING_POLICY", "applyOnlineHelperPolicy(e)"),
            ("STARTING_ENGINE", "e.start()"),
            ("READING_NODE", "e.nodeId()"),
            ("READING_PUBLIC_KEY", "e.publicKey()"),
        ):
            with self.subTest(label=label):
                self.assertLess(startup.index(f"note(EnginePhase.{label})"), startup.index(call))
        self.assertIn("note(EnginePhase.ABORTING)", startup)
        progress = text("data/diagnostics/ServiceStartupProgress.kt")
        self.assertIn("expected !== token", progress)
        self.assertIn("sample.enginePhase.name.lowercase()", progress)
        self.assertIn("sample.enginePhaseMs[step]", progress)
        self.assertNotIn("RustBridge", progress)
        self.assertNotIn("KeyStore", progress)
        self.assertNotIn("displayName", progress)
        self.assertNotIn("existingPrivateKey", progress)
        report = text("data/diagnostics/ServiceStartupDiagnostics.kt")
        self.assertIn("ServiceStartupReport.render(progress.snapshot())", report)

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
