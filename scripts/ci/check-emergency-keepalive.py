#!/usr/bin/env python3
"""Контракт доставки «как в Telegram»: пробуждение, allowlist, разрешения.

История. Раунд 141 просил владельца: «если приложение убили — запуститься
через 5 минут». Сделали буквально: точный будильник каждые 5 минут, а
`setExactAndAllowWhileIdle` в Doze — самый дорогой по батарее механизм
Android. Получалось 288 пробуждений в сутки, даже когда всё работает.
Аудит 2026-10-26 (P1-6) заменил это на одну неточную страховку в час — и
получилась обратная беда: после убийства процесса письмо могло ждать до часа.

Решение владельца 2026-10-28: «сделать как в Telegram — если убило
приложение, оно понимает и просыпается». У Telegram письма будит серверный
push; у нас сервера нет, поэтому «системный толчок» даёт собственное
расписание, и оно знает о своём состоянии. Три сценария, и контракт стережёт
именно их разделение:

  • служба жива            → тихий сторож раз в 15 минут (проверка «а жива ли»);
  • службу убили/смахнули  → быстрый подъём через 2 минуты;
  • старт не удался        → лестница 1 → 5 → 15 → 30 минут (дальше не растёт).

Числа живут в одном месте — `service/DeliveryWakePolicy.kt` (чистая
арифметика, покрыта `DeliveryWakePolicyTest`), Android-обвязка — в
`service/DeliveryAssurance.kt` и `service/EmergencyKeepAlive.kt`. Проверять
числа в обвязке бессмысленно: их там нет по замыслу.

Плюс приёмник объявлен `exported="true"` ради системных broadcast'ов,
поэтому принимать он обязан только свои действия — иначе любое приложение
поднимет наш foreground-сервис и посадит чужую батарею.

Запуск: python3 scripts/ci/check-emergency-keepalive.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
POLICY = MAIN / "service/DeliveryWakePolicy.kt"
ASSURANCE = MAIN / "service/DeliveryAssurance.kt"
KEEP_ALIVE = MAIN / "service/EmergencyKeepAlive.kt"
HOOKS = MAIN / "service/ServiceLifecycleHooks.kt"
SERVICE = MAIN / "service/CoreServerService.kt"
WATCHDOG = MAIN / "worker/ServiceWatchdogWorker.kt"
CARD = MAIN / "ui/screens/settings/DeliveryAssuranceCard.kt"
SETTINGS = MAIN / "ui/screens/settings/SettingsScreen.kt"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"
RU = ROOT / "android-app/app/src/main/res/values/strings.xml"
EN = ROOT / "android-app/app/src/main/res/values-en/strings.xml"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger/service/DeliveryWakePolicyTest.kt"

MINUTE = 60_000


def minutes_from_literal(value):
    """«15 * 60_000L» → 15 минут."""
    return int(value) * MINUTE // MINUTE


class DeliveryWakeContract(unittest.TestCase):
    def setUp(self):
        self.policy = POLICY.read_text()
        self.assurance = ASSURANCE.read_text()
        self.keep_alive = KEEP_ALIVE.read_text()
        self.service = SERVICE.read_text()

    # ---------------------------------------------------------------- политика

    def _const_minutes(self, name):
        found = re.search(r"const val %s\s*=\s*(\d+)\s*\*\s*60_000L" % name, self.policy)
        self.assertIsNotNone(found, "в политике нет %s" % name)
        return minutes_from_literal(found.group(1))

    def test_wake_numbers_live_in_one_place(self):
        self.assertEqual(15, self._const_minutes("WATCHDOG_MS"), "сторож — раз в 15 минут")
        self.assertEqual(2, self._const_minutes("FAST_WAKE_MS"), "после убийства — через 2 минуты")
        self.assertLess(
            self._const_minutes("FAST_WAKE_MS"),
            self._const_minutes("WATCHDOG_MS"),
            "быстрый подъём обязан быть быстрее сторожа",
        )

    def test_retry_ladder_starts_at_a_minute_and_caps_at_half_an_hour(self):
        ladder = re.search(r"RETRY_LADDER_MS\s*=\s*longArrayOf\(([^)]*)\)", self.policy)
        self.assertIsNotNone(ladder, "нет лестницы повторов")
        steps = [minutes_from_literal(value) for value in
                 re.findall(r"(\d+)\s*\*\s*60_000", ladder.group(1))]
        self.assertEqual([1, 5, 15, 30], steps, "лестница должна быть 1→5→15→30 минут")
        self.assertEqual(sorted(steps), steps, "ступени обязаны расти")

    def test_exact_alarm_requires_battery_exemption(self):
        self.assertIn("DeliveryWakePolicy.canBeExact(", TEST.read_text(),
                      "тест политики не проверяет выдачу права на точный будильник")
        body = self.policy[self.policy.find("fun canBeExact"):]
        body = body[: body.find("\n    }")]
        for condition in ("enabled", "batteryUnrestricted", "exactAlarmsAllowed"):
            self.assertIn(condition, body,
                          "точный будильник обязан учитывать " + condition)
        # Пятиминутного «тика» в нормальном режиме быть не должно.
        self.assertNotIn("5 * 60_000L", self.policy.split("RETRY_LADDER_MS")[0],
                         "пятиминутный будильник — это 288 пробуждений в сутки")

    def test_staleness_tolerates_missed_watchdogs(self):
        threshold = re.search(r"STALE_AFTER_MS\s*=\s*(\d+)\s*\*\s*WATCHDOG_MS", self.policy)
        self.assertIsNotNone(threshold, "порог «служба мертва» не привязан к сторожу")
        self.assertGreaterEqual(int(threshold.group(1)), 2,
                                "порог ниже двух интервалов сторожа — каждый тик будил бы")

    def test_policy_is_covered_by_a_junit_test(self):
        self.assertTrue(TEST.is_file(), "нет DeliveryWakePolicyTest")
        text = TEST.read_text()
        for marker in ("shouldWakeNow", "afterRefusal", "canBeExact", "serviceGone"):
            self.assertIn(marker, text, "тест политики не покрывает " + marker)

    # ---------------------------------------------------------------- обвязка

    def test_service_gone_means_a_fast_wake_not_an_hourly_one(self):
        stopped = self.keep_alive[self.keep_alive.find("fun onServiceStopped"):]
        stopped = stopped[: stopped.find("fun onServiceRefused")]
        self.assertIn("DeliveryWakePolicy.serviceGone(", stopped,
                      "onDestroy без осознанного перезапуска — быстрый подъём")
        self.assertIn("DeliveryWakePolicy.afterPlannedStop(", stopped,
                      "осознанная остановка — редкая страховка, а не круг из двух минут")

    def test_swiped_app_wakes_through_on_task_removed(self):
        self.assertIn("fun onTaskRemoved(context: Context)", self.keep_alive)
        self.assertIn("DeliveryWakePolicy.taskRemoved(", self.keep_alive)
        self.assertIn("override fun onTaskRemoved", self.service,
                      "служба обязана узнать, что приложение смахнули из задач")

    def test_refusals_use_the_shared_ladder(self):
        refused = self.keep_alive[self.keep_alive.find("fun onServiceRefused"):]
        self.assertIn("DeliveryWakePolicy.afterRefusal(", refused)
        self.assertIn("KEY_REFUSALS", self.keep_alive, "счётчик отказов не сохраняется")
        started = self.keep_alive[self.keep_alive.find("fun onServiceStarted"):]
        started = started[: started.find("fun onServiceStopped")]
        self.assertIn("refusals(context, 0)", started, "успешный старт обязан сбрасывать лестницу")

    def test_receiver_accepts_only_its_own_actions(self):
        self.assertIn("fun isOwnAction(", self.keep_alive)
        allow = self.keep_alive[self.keep_alive.find("fun isOwnAction"):]
        allow = allow[: allow.find("\n    }")]
        self.assertIn("ACTION_PUMP", allow)
        self.assertIn("ACTION_BOOT", allow)
        self.assertIn("ACTION_PACKAGE_REPLACED", allow)
        # Сами имена действий — строками: allowlist обязан быть проверяемым без Android.
        self.assertIn('"android.intent.action.BOOT_COMPLETED"', self.keep_alive)
        self.assertIn('"android.intent.action.MY_PACKAGE_REPLACED"', self.keep_alive)
        self.assertNotIn('"android.intent.action.LOCKED_BOOT_COMPLETED"', self.keep_alive,
                         "до разблокировки личность и настройки закрыты — принимать нельзя")

    def test_service_calls_the_right_scenario(self):
        self.assertIn("EmergencyKeepAlive.onServiceStarted(applicationContext)", self.service)
        self.assertEqual(
            2,
            self.service.count("EmergencyKeepAlive.onServiceRefused(applicationContext)"),
            "отказ startForeground и таймаут foreground — оба через лестницу",
        )
        self.assertIn("ServiceLifecycleHooks.onDestroyed(", self.service)
        self.assertIn("ServiceLifecycleHooks.onTaskRemoved(", self.service)
        self.assertNotIn("scheduleNext(", self.service)
        hooks = HOOKS.read_text()
        self.assertIn("EmergencyKeepAlive.onServiceStopped(", hooks)
        self.assertIn("intentional = planned", hooks)

    def test_refusals_are_counted_in_diagnostics(self):
        self.assertIn("OperationalCounter.FOREGROUND_START_REFUSED", self.service)
        self.assertIn("OperationalCounter.FOREGROUND_TIMEOUT", self.service)
        self.assertIn("OperationalCounter.DELIVERY_WAKE_ALARM", self.keep_alive)
        self.assertIn("OperationalCounter.DELIVERY_WAKE_START", self.keep_alive)

    def test_alive_mark_is_written_by_the_running_service(self):
        self.assertIn("DeliveryAssurance.markAlive(applicationContext)", self.service,
                      "без метки жизни сторож не отличит «жив» от «убит»")

    # -------------------------------------------------- сторож и разрешения

    def test_watchdog_is_a_system_level_safety_net(self):
        self.assertTrue(WATCHDOG.is_file(), "нет сторожевого работника")
        worker = WATCHDOG.read_text()
        self.assertIn("CoroutineWorker", worker)
        self.assertIn("DeliveryWakePolicy.shouldWakeNow(", worker,
                      "сторож обязан проверять, жива ли служба, а не поднимать вторую")
        self.assertIn("ExistingPeriodicWorkPolicy.UPDATE", (MAIN / "MessengerApplication.kt").read_text(),
                      "интервал сторожа должен применяться после обновления приложения")

    def test_settings_show_what_blocks_on_time_delivery(self):
        self.assertTrue(CARD.is_file(), "нет карточки «Уведомления приходят вовремя»")
        card = CARD.read_text()
        for item in ("Item.NOTIFICATIONS", "Item.BATTERY", "Item.EXACT_ALARMS", "Item.BACKGROUND"):
            self.assertIn(item, card, "на экране нет пункта " + item)
        self.assertIn("DeliveryAssuranceCard()", SETTINGS.read_text(),
                      "карточка не подключена к разделу «Уведомления»")
        for name in ("status", "setEnabled", "openSystemSettings"):
            self.assertIn("fun " + name, self.assurance)
        for name in ("blocking", "onTime", "lastAliveMs"):
            self.assertIn(name, self.assurance, "состояние для экрана неполное: " + name)

    def test_strings_exist_in_both_languages(self):
        ru = RU.read_text()
        en = EN.read_text()
        keys = set(re.findall(r'<string name="(delivery_[a-z_]+)"', ru))
        self.assertGreaterEqual(len(keys), 10, "текстов экрана доставки слишком мало")
        for key in sorted(keys):
            self.assertIn('name="%s"' % key, en,
                          "нет английского текста для " + key)

    def test_receiver_is_exported_with_the_allowlist_reason_documented(self):
        manifest = MANIFEST.read_text()
        block = manifest[manifest.find("EmergencyKeepAliveReceiver") - 600:]
        block = block[: block.find("/>")]
        if 'android:exported="true"' in block:
            self.assertIn("BOOT_COMPLETED", block,
                          "exported=true без объяснения, зачем нужен системный broadcast")
            self.assertIn("allowlist", block,
                          "exported=true без объяснения, почему это безопасно")


if __name__ == "__main__":
    unittest.main(verbosity=2)
