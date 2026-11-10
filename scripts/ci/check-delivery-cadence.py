#!/usr/bin/env python3
"""Контракт ритма доставки: входящее не ждёт пяти секунд, исходящее — минуты.

Владелец 2026-10-29: «смена аватарки у друга видна сразу, а сообщения идут с
задержками или не доходят». Смена аватарки уходила тем же транспортом, но её
никто не ждал: она уезжала на живое соединение. Текст ждал опроса шины событий
(раз в пять секунд), слива очереди (раз в минуту) и блокирующих повторов прямо
в обработчике событий. Этот контракт фиксирует, что ожидание не вернулось.

Проверяется исходник, а не телефон: реальную задержку в сети измеряет владелец.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/service"
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/MainActivity.kt"


def read(path):
    return path.read_text(encoding="utf-8")


class DeliveryCadenceContractTest(unittest.TestCase):
    def setUp(self):
        self.cadence = read(SERVICE / "DeliveryCadence.kt")
        self.service = read(SERVICE / "CoreServerService.kt")
        self.pump = read(SERVICE / "ChatOutboxPump.kt")
        self.main = read(MAIN)

    def test_event_bus_waits_on_a_condvar_instead_of_idle_polling(self):
        # Поток спит в Rust и просыпается от события; таймерного опроса нет.
        self.assertIn("EVENT_WAIT_MS = 5_000L", self.cadence)
        loops = read(SERVICE / "CoreEventLoops.kt")
        self.assertIn("waitMs = DeliveryCadence::eventWaitMs", self.service)
        self.assertIn("RustBridge.awaitMainEvent(timeout)", self.service)
        self.assertIn("RustBridge.awaitFileEvent(timeout)", self.service)
        self.assertIn("if (!await(lane, waitMs())) continue", loops)
        self.assertIn("currentCoroutineContext().ensureActive()", loops)
        self.assertNotIn("RustBridge.drainEvents()", self.service)
        self.assertNotIn("pollDelayMs", self.cadence)
        self.assertNotIn("delay(DeliveryCadence.pollDelayMs(busy))", self.service)
        self.assertNotIn("FOREGROUND_POLL_MS = 250L", self.cadence)
        bridge = read(ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/RustBridge.kt")
        self.assertIn("fun awaitMainEvent(", bridge)
        self.assertIn("fun awaitFileEvent(", bridge)
        udl = read(ROOT / "rust-core/src/lib.udl")
        for method in ("await_main_event", "await_file_event"):
            self.assertIn(f"boolean {method}(u64 timeout_ms);", udl)
        events = read(ROOT / "rust-core/src/engine/events.rs")
        self.assertIn("Condvar", events)
        self.assertIn("wait_for_lane", events)
        self.assertIn("self.ready.notify_all()", events)

    def test_each_incoming_event_marks_activity(self):
        processing = self.service[self.service.index("process = { event ->"):]
        self.assertLess(processing.index("DeliveryCadence.touch()"), processing.index("handleEvent(event)"))

    def test_main_and_file_consumers_are_independent_and_fair(self):
        loops = read(SERVICE / "CoreEventLoops.kt")
        self.assertIn("launch { runLane(Lane.Main) }", loops)
        self.assertIn("launch { runLane(Lane.File) }", loops)
        self.assertIn("MAX_INTERACTIVE_BURST = 8", loops)
        self.assertIn("RustBridge.pollMainEvent(prefer)", self.service)
        self.assertIn("RustBridge.pollFileEvent()", self.service)
        self.assertIn("isRunning = RustBridge::isRunningCached", self.service)
        events = read(ROOT / "rust-core/src/engine/events.rs")
        self.assertIn("CoreEvent::FileChunkReceived { .. }", events)
        self.assertIn("is_interactive(event) == prefer_interactive", events)

    def test_native_send_and_lifecycle_have_distinct_lock_modes(self):
        handle = read(ROOT / "rust-core/src/lib.rs")
        self.assertIn("inner: std::sync::RwLock<P2PCore>", handle)
        self.assertIn("self.inner.write().unwrap().stop()", handle)
        self.assertIn("self.send_gates.for_message(&message_id)", handle)
        self.assertIn("let core = self.inner.read().unwrap();", handle)
        self.assertIn("self.events.poll_lane(", handle)

    def test_waiting_files_do_not_fill_interactive_direct_queue(self):
        scheduler = read(ROOT / "rust-core/src/network/direct_priority.rs")
        transport = read(ROOT / "rust-core/src/network/direct_transport.rs")
        self.assertIn("MAX_INTERACTIVE_BURST: usize = 8", scheduler)
        self.assertIn("interactive: interactive_tx, file: file_tx", scheduler)
        self.assertIn("lane.tx.try_send(interactive, job)", transport)
        self.assertIn("while let Some(job) = rx.recv().await", transport)

    def test_failed_neighbor_offer_is_rearmed_on_a_bounded_fair_sweep(self):
        core = read(ROOT / "rust-core/src/engine/core.rs")
        custody = read(ROOT / "rust-core/src/network/custody_relay.rs")
        self.assertIn("if !sent { offers.note_send_failed(&msg_id, &peer); }", core)
        self.assertIn("const OWN_LOOKUP_TICKS: u64 = 12", core)
        self.assertIn("task.offered_to.retain(|known| known != peer)", custody)
        self.assertIn("tasks.pop_front()", custody)
        self.assertIn("tasks.push_back(task)", custody)
        self.assertIn("task.accepted < task.offered_to.len().min(MAX_CUSTODY_PEERS)", custody)
        self.assertIn("network::custody_relay::tests", read(ROOT / ".github/workflows/build-release.yml"))

    def test_mqtt_wake_does_not_cancel_taken_connack_or_accelerate_presence(self):
        core = read(ROOT / "rust-core/src/engine/core.rs")
        transport = read(ROOT / "rust-core/src/network/mqtt_transport.rs")
        cadence = read(ROOT / "rust-core/src/engine/mqtt_cadence.rs")
        self.assertIn("transport.poll_event_or(outbound_rx.recv()).await", core)
        self.assertIn("super::mqtt_wait::wait(", transport)
        self.assertLess(transport.index("super::mqtt_wait::wait("), transport.index('"primary wildcard subscribe"'))
        self.assertIn("PRESENCE_INTERVAL: Duration = Duration::from_secs(60)", cadence)
        self.assertIn("GOSSIP_INTERVAL: Duration = Duration::from_secs(300)", cadence)

    def test_wake_leases_serialize_platform_and_registry_transitions(self):
        wake = read(SERVICE / "CoreWorkWakeLock.kt")
        leases = read(SERVICE / "WorkWakeLeases.kt")
        self.assertIn("WorkWakeLeases(", wake)
        self.assertIn("synchronized(monitor)", leases)
        self.assertIn("if (stopped) return@synchronized", leases)
        self.assertIn("if (holders == 0 || !platformIsHeld()) platformAcquire()", leases)
        self.assertIn("if (holders == 0) releasePlatform()", leases)

    def test_app_reports_foreground_and_background(self):
        self.assertIn("DeliveryCadence.setForeground(true)", self.main)
        self.assertIn("DeliveryCadence.setForeground(false)", self.main)
        self.assertIn("override fun onStart()", self.main)
        self.assertIn("override fun onStop()", self.main)

    def test_outbox_has_its_own_pump_instead_of_a_one_minute_cycle(self):
        self.assertNotIn("chatQueueCycle", self.service)
        self.assertIn("chatOutboxPump.start(serviceScope)", self.service)
        self.assertIn("OutboxCadence.nextDelayMs(", self.pump)
        self.assertIn("messageDao.countPendingOutgoing()", self.pump)
        dao = read(ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt")
        self.assertIn("suspend fun countPendingOutgoing(): Int", dao)
        self.assertIn("AND status IN ('PENDING', 'QUEUED_OFFLINE')", dao)

    def test_idle_pumps_back_off_and_wake_lock_is_bounded(self):
        idle = read(SERVICE / "ServiceIdleCadence.kt")
        self.assertIn("FILE_PUMP_IDLE_FOREGROUND_MS = 60_000L", idle)
        self.assertIn("FILE_PUMP_IDLE_BACKGROUND_MS = 120_000L", idle)
        self.assertIn("ServiceIdleCadence.filePumpDelayMs(", self.service)
        self.assertIn("ServiceIdleCadence.UPDATE_BARRIER_MS", self.service)
        self.assertNotIn("kotlinx.coroutines.delay(2_500L)", self.service)
        wake = read(SERVICE / "CoreWorkWakeLock.kt")
        self.assertIn("WORK_WAKE_LOCK_MS = 5 * 60 * 1000L", wake)
        self.assertIn("coreWorkWakeLock.acquire()", self.service)
        # Постоянный замок службы больше не берётся в onCreate.
        self.assertNotIn("P2PMessenger::CoreWakeLock", self.service)
        self.assertNotIn("shouldRefreshWakeLock", self.service)

    def test_decorative_ui_has_no_infinite_idle_animation(self):
        ui = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/components"
        for name in ("AnimatedTopicIcon.kt", "RankMedal.kt", "ApuVipRing.kt", "ShinyEmoji.kt",
                     "ShimmerIcon.kt", "ApuSkeleton.kt", "GifCatalogDialog.kt", "MessageReactions.kt"):
            self.assertNotIn("rememberInfiniteTransition", read(ui / name), name)

    def test_first_retry_is_seconds_not_a_minute(self):
        self.assertIn("FOREGROUND_LADDER_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)", self.cadence)
        self.assertIn("EMPTY_CHECK_MS = 15_000L", self.cadence)
        self.assertIn("DRAIN_MS = 2_000L", self.cadence)

    def test_blocking_retry_never_runs_inside_the_event_handler(self):
        # Повтор исходящих - только внутри запущенной корутины: вызов ядра
        # блокирующий, и входящие не должны стоять за ним.
        connected = self.service.index('if (status == "connected") {')
        window = self.service[connected:connected + 900]
        self.assertIn("serviceScope.launch {", window)
        self.assertLess(window.index("serviceScope.launch {"), window.index("retryAllPendingMessages()"))
        self.assertNotIn("val retried = chatRepository.retryAllPendingMessages()\n                    } catch", window)

    def test_send_marks_activity_so_the_reply_does_not_wait(self):
        bridge = read(ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/RustBridge.kt")
        self.assertIn("com.vladimir.messenger.service.DeliveryCadence.touch()", bridge)

    def test_god_file_stays_under_its_ceiling(self):
        import json
        ceiling = json.loads(read(ROOT / "scripts/ci/god-files-baseline.json"))["ceilings"][
            "android-app/app/src/main/java/com/vladimir/messenger/service/CoreServerService.kt"
        ]
        lines = len(self.service.splitlines())
        self.assertLessEqual(lines, ceiling, f"CoreServerService.kt {lines} > {ceiling}")

    def test_policy_is_pure_so_it_can_be_tested_off_device(self):
        for android_import in ("import android.", "import androidx."):
            self.assertNotIn(android_import, self.cadence)

    def test_contract_is_wired_into_release_build(self):
        workflow = read(ROOT / ".github/workflows/build-release.yml")
        self.assertIn("python3 scripts/ci/check-delivery-cadence.py", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
