#!/usr/bin/env python3
"""Контракт аварийного будильника: батарея, allowlist действий, лестница.

История. Раунд 141 просил владельца: «если приложение убили — запуститься
через 5 минут». Сделали буквально: точный будильник каждые 5 минут, а
`setExactAndAllowWhileIdle` в Doze — самый дорогой по батарее механизм
Android. Получалось 288 пробуждений в сутки, даже когда всё работает.

Сейчас два сценария, и контракт стережёт именно их разделение:
  • сервис жив → одна неточная страховка в час;
  • старт не удался → точные повторы 5 → 15 → 30 → 60 минут.

Плюс приёмник объявлен `exported="true"` ради BOOT_COMPLETED, поэтому
принимать он обязан только свои два действия — иначе любое приложение
поднимет наш foreground-сервис и посадит чужую батарею.

Запуск: python3 scripts/ci/check-emergency-keepalive.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
KEEP_ALIVE = MAIN / "service/EmergencyKeepAlive.kt"
SERVICE = MAIN / "service/CoreServerService.kt"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"


class EmergencyKeepAliveContract(unittest.TestCase):
    def setUp(self):
        self.keep_alive = KEEP_ALIVE.read_text()
        self.service = SERVICE.read_text()

    def test_receiver_accepts_only_its_own_actions(self):
        self.assertIn("action != EmergencyKeepAlive.ACTION_PUMP", self.keep_alive)
        self.assertIn("action != Intent.ACTION_BOOT_COMPLETED", self.keep_alive)
        self.assertIn("return", self.keep_alive.split("action != Intent.ACTION_BOOT_COMPLETED")[1])

    def test_running_service_keeps_only_an_hourly_inexact_safety_net(self):
        self.assertIn("const val SAFETY_NET_MS = 60 * 60_000L", self.keep_alive)
        net = self.keep_alive[self.keep_alive.find("fun onServiceStarted"):]
        net = net[: net.find("fun onServiceRefused")]
        self.assertIn("schedule(context, SAFETY_NET_MS, exact = false)", net)
        # Пятиминутного «тика» в нормальном режиме больше быть не должно.
        self.assertNotIn("5 * 60_000L", net)

    def test_refusals_use_an_escalating_exact_ladder(self):
        ladder = re.search(r"RETRY_LADDER_MS\s*=\s*longArrayOf\(([^)]*)\)", self.keep_alive)
        self.assertIsNotNone(ladder, "нет лестницы повторов")
        # Каждая ступень записана как «<минуты> * 60_000L».
        minutes = [int(value) for value in
                   re.findall(r"(\d+)\s*\*\s*60_000", ladder.group(1))]
        self.assertEqual([5, 15, 30, 60], minutes, "лестница должна быть 5→15→30→60 минут")
        refused = self.keep_alive[self.keep_alive.find("fun onServiceRefused"):]
        self.assertIn("exact = true", refused)
        self.assertIn("KEY_REFUSALS", self.keep_alive, "счётчик отказов не сохраняется")

    def test_successful_start_resets_the_ladder(self):
        started = self.keep_alive[self.keep_alive.find("fun onServiceStarted"):]
        started = started[: started.find("fun onServiceRefused")]
        self.assertIn("refusals(context, 0)", started)

    def test_service_calls_the_right_scenario(self):
        self.assertIn("EmergencyKeepAlive.onServiceStarted(applicationContext)", self.service)
        self.assertEqual(2, self.service.count("EmergencyKeepAlive.onServiceRefused(applicationContext)"),
                         "отказ startForeground и таймаут foreground — оба через лестницу")
        self.assertNotIn("scheduleNext(", self.service)

    def test_refusals_are_counted_in_diagnostics(self):
        self.assertIn("OperationalCounter.FOREGROUND_START_REFUSED", self.service)
        self.assertIn("OperationalCounter.FOREGROUND_TIMEOUT", self.service)

    def test_receiver_is_exported_with_the_allowlist_reason_documented(self):
        manifest = MANIFEST.read_text()
        block = manifest[manifest.find("EmergencyKeepAliveReceiver") - 400:]
        block = block[: block.find("/>")]
        if 'android:exported="true"' in block:
            self.assertIn("BOOT_COMPLETED", block,
                          "exported=true без объяснения, зачем нужен системный broadcast")


if __name__ == "__main__":
    unittest.main(verbosity=2)
