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

    def test_event_bus_is_polled_fast_while_the_screen_is_on(self):
        self.assertIn("FOREGROUND_POLL_MS = 250L", self.cadence)
        self.assertIn("IDLE_POLL_MS = 5_000L", self.cadence)
        self.assertIn("delay(DeliveryCadence.pollDelayMs(busy))", self.service)
        # Прежняя формула «пять секунд, если очередь пуста» больше не живёт.
        self.assertNotIn("delay(if (busy) BUSY_POLL_INTERVAL_MS else POLL_INTERVAL_MS)", self.service)
        self.assertNotIn("BUSY_POLL_INTERVAL_MS =", self.service)

    def test_incoming_batch_marks_activity(self):
        self.assertIn("if (events.isNotEmpty()) DeliveryCadence.touch()", self.service)

    def test_app_reports_foreground_and_background(self):
        self.assertIn("DeliveryCadence.setForeground(true)", self.main)
        self.assertIn("DeliveryCadence.setForeground(false)", self.main)
        self.assertIn("override fun onStart()", self.main)
        self.assertIn("override fun onStop()", self.main)

    def test_outbox_has_its_own_pump_instead_of_a_one_minute_cycle(self):
        self.assertNotIn("chatQueueCycle", self.service)
        self.assertIn("chatOutboxPump.start(serviceScope)", self.service)
        self.assertIn("OutboxCadence.nextDelayMs(", self.pump)
        self.assertIn("messageDao.queuedOfflineCount()", self.pump)
        dao = read(ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt")
        self.assertIn("suspend fun queuedOfflineCount(): Int", dao)
        self.assertIn("WHERE isFromMe = 1 AND status = 'QUEUED_OFFLINE'\")", dao)

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
