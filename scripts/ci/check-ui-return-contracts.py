#!/usr/bin/env python3
"""Контракты двух жалоб владельца от 2026-10-29.

1. «Возвращаешься в предыдущее меню настроек — оно всегда в верхней части».
   Причина была прямой: `LaunchedEffect(section) { scrollToItem(0) }` на любую
   смену раздела. Теперь у каждого меню своя позиция (`SettingsScrollMemory`).

2. «Люди жмут кнопку и у них качается по два-три обновления». Причина: в окно
   обновления всегда передавалось `isDownloading = false`, кнопка оставалась
   живой, а каждый тап ставил в DownloadManager новую задачу. Теперь один замок
   на всё приложение (`UpdateDownloadGate`, пауза 30 с) и блокировка кнопки.

Проверяется исходник; как это выглядит на телефоне, проверяет владелец.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path):
    return path.read_text(encoding="utf-8")


class SettingsReturnPositionTest(unittest.TestCase):
    def setUp(self):
        self.screen = read(JAVA / "ui/screens/settings/SettingsScreen.kt")
        self.state = read(JAVA / "ui/screens/settings/SettingsScrollState.kt")
        self.memory = read(JAVA / "ui/screens/settings/SettingsScrollMemory.kt")

    def test_returning_to_a_menu_no_longer_jumps_to_the_top(self):
        self.assertNotIn("scrollToItem(0)", self.screen)
        self.assertNotIn("LaunchedEffect(section) { settingsScrollState.scrollToItem(0) }", self.screen)
        self.assertIn("rememberSettingsScrollState(section)", self.screen)

    def test_each_menu_has_its_own_remembered_position(self):
        self.assertIn("class SettingsScrollMemory", self.memory)
        self.assertIn("fun anchorFor(menu: String): Anchor", self.memory)
        self.assertIn("fun remember(menu: String, anchor: Anchor)", self.memory)
        self.assertIn('const val ROOT = "root"', self.memory)
        # Раздел настроек и корневой список — разные меню.
        self.assertIn('"Notifications"', self.memory)
        self.assertIn('"Updates"', self.memory)

    def test_position_survives_rotation_and_process_death(self):
        self.assertIn("rememberSaveable(", self.state)
        self.assertIn("listSaver<SettingsScrollMemory, Int>", self.state)
        self.assertIn("fun flatten(): List<Int>", self.memory)
        self.assertIn("fun fromFlatten(flat: List<Int>)", self.memory)

    def test_restore_waits_for_the_list_to_be_measured(self):
        self.assertIn("snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }", self.state)
        self.assertIn("state.scrollToItem(anchor.index, anchor.offset)", self.state)

    def test_menu_keys_match_the_section_enum(self):
        enum_body = self.screen.split("enum class SettingsSection", 1)[1].split("}", 1)[0]
        enum_names = re.findall(r"\b([A-Z][A-Za-z]+)\(", enum_body)
        menu_list = self.memory.split("MENU_KEYS = listOf(", 1)[1].split(")", 1)[0]
        keys = re.findall(r'"([A-Za-z]+)"', menu_list)
        for name in enum_names:
            self.assertIn(name, keys, f"раздел {name} не имеет своей позиции в списке меню")
        for key in keys:
            if key == "root":
                continue  # корневой список — не раздел, у него своё имя
            self.assertIn(key, enum_names, f"в списке меню лишний ключ {key}")

    def test_the_rule_has_a_unit_test(self):
        test = read(
            ROOT
            / "android-app/app/src/test/java/com/vladimir/messenger/ui/screens/settings/SettingsScrollMemoryTest.kt"
        )
        self.assertIn("returning to a menu restores where it was left", test)
        self.assertIn("each menu keeps its own position", test)


class UpdateDownloadOnceTest(unittest.TestCase):
    def setUp(self):
        self.gate = read(JAVA / "service/UpdateDownloadGate.kt")
        self.checker = read(JAVA / "service/UpdateChecker.kt")
        self.main = read(JAVA / "MainActivity.kt")
        self.dialog = read(JAVA / "ui/update/UpdateDialog.kt")

    def test_the_gate_keeps_the_button_silent_for_thirty_seconds(self):
        self.assertIn("COOLDOWN_MS = 30_000L", self.gate)
        self.assertIn("data object Start", self.gate)
        self.assertIn("data class InFlight", self.gate)
        self.assertIn("data class Cooldown", self.gate)

    def test_every_download_goes_through_one_gate(self):
        download = self.checker.split("fun downloadApk(", 1)[1]
        self.assertIn("UpdateDownloadGate.request(", download)
        self.assertLess(download.index("UpdateDownloadGate.request("), download.index("enqueueApk("))
        self.assertIn("hasActiveUpdateDownload()", download)
        # Отметка не должна пережить неудачу: иначе кнопка заперта 15 минут.
        self.assertIn("UpdateDownloadGate.lost(releaseInfo.version)", download)

    def test_a_download_that_is_already_running_is_detected_in_android(self):
        self.assertIn("fun hasActiveUpdateDownload(): Boolean", self.checker)
        self.assertIn('title.startsWith("APU v")', self.checker)
        self.assertIn("DownloadManager.STATUS_RUNNING", self.checker)

    def test_the_update_dialog_blocks_the_button_while_the_request_runs(self):
        self.assertIn("isDownloading = updateDownloadPending", self.main)
        self.assertNotIn("isDownloading = false", self.main.split("UpdateDialog(", 1)[1].split("onDismissClick", 1)[0])
        self.assertIn("if (!updateDownloadPending) {", self.main)
        self.assertIn("enabled = !isDownloading", self.dialog)

    def test_the_person_is_told_that_the_download_has_started(self):
        for folder in ("values", "values-en"):
            strings = read(RES / folder / "strings.xml")
            self.assertIn('name="upd_download_started"', strings)
            self.assertIn('name="upd_download_already_running"', strings)
            self.assertIn('name="upd_download_wait_tpl"', strings)
        self.assertIn("UpdateDownloadMessages.forStarted(", self.main)

    def test_the_gate_has_a_unit_test(self):
        test = read(
            ROOT / "android-app/app/src/test/java/com/vladimir/messenger/service/UpdateDownloadGateTest.kt"
        )
        self.assertIn("taps in a row never produce more than one download", test)
        self.assertIn("the button stays silent for thirty seconds after the tap", test)

    def test_contract_is_wired_into_release_build(self):
        workflow = read(ROOT / ".github/workflows/build-release.yml")
        self.assertIn("python3 scripts/ci/check-ui-return-contracts.py", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
