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
        self.assertIn("rememberSettingsScrollState(", self.screen)
        self.assertIn("section?.name ?: SettingsScrollMemory.ROOT", self.screen)

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


class UpdateDownloadRecoveryTest(unittest.TestCase):
    """Жалоба 2026-11-01: рой встал, а сайт не даёт скачать повторно."""

    def setUp(self):
        self.seeder = read(JAVA / "data/update/ApkSeeder.kt")
        self.recovery = read(JAVA / "data/update/ApkUpdateStallRecovery.kt")
        self.policy = read(JAVA / "data/update/ApkUpdate.kt")
        self.checker = read(JAVA / "service/UpdateChecker.kt")
        self.vm = read(JAVA / "ui/screens/settings/SettingsViewModel.kt")
        self.item = read(JAVA / "ui/screens/settings/ApkUpdateDownloadItems.kt")

    def test_stalled_incoming_row_is_dropped_before_reask(self):
        self.assertIn("fun shouldDropStalledIncoming(", self.policy)
        self.assertIn("suspend fun dropStalledIncoming(", self.recovery)
        self.assertIn("router.declineIncoming(row)", self.recovery)
        self.assertIn("router.dropTransfer(row.transferId)", self.recovery)
        reask = self.seeder.split("private suspend fun reask(", 1)[1].split("private suspend fun reaskPatch(", 1)[0]
        self.assertIn("ApkUpdateStallRecovery.dropStalledIncoming", reask)
        self.assertLess(reask.index("dropStalledIncoming"), reask.index("askNext("))
        reask_patch = self.seeder.split("private suspend fun reaskPatch(", 1)[1].split("private suspend fun clearPending(", 1)[0]
        self.assertIn("ApkUpdateStallRecovery.dropStalledIncoming", reask_patch)

    def test_waiting_for_space_is_not_treated_as_a_stall(self):
        self.assertIn("!waitingForDeviceSpace && isDownloadStalled", self.policy)
        self.assertIn("row.errorCode != FileTransferReceiver.ERROR_NO_SPACE", self.seeder)

    def test_official_source_can_replace_a_stuck_neighbor_receive(self):
        self.assertIn("private fun officialDownloadBusy()", self.vm)
        self.assertIn("private fun neighborDownloadBusy()", self.vm)
        self.assertIn("private suspend fun stopNeighborDownloadForOfficial()", self.vm)
        official = self.vm.split("fun onDownloadOfficialRelease()", 1)[1].split("private val officialDownloadReceiver", 1)[0]
        self.assertIn("if (officialDownloadBusy())", official)
        self.assertIn("stopNeighborDownloadForOfficial()", official)
        self.assertIn("apkSeeder.cancelDownload()", self.vm)
        self.assertIn("apkSeeder.cancelPatchDownload()", self.vm)

    def test_official_progress_is_visible_without_the_system_notification(self):
        self.assertIn("data class OfficialDownloadState(", self.checker)
        self.assertIn("fun officialDownloadState(): OfficialDownloadState?", self.checker)
        self.assertIn("fun cancelOfficialDownload(): Boolean", self.checker)
        self.assertIn("UpdateDownloadGate.lost", self.checker)
        self.assertIn("LaunchedEffect(active, state?.downloadId)", self.item)
        self.assertIn("delay(2_000L)", self.item)
        self.assertIn("viewModel.refreshOfficialDownload()", self.item)
        self.assertIn("viewModel::onCancelOfficialDownload", self.item)
        self.assertIn("st_official_retry_download", self.item)

    def test_recovery_strings_exist_in_both_languages(self):
        for folder in ("values", "values-en"):
            strings = read(RES / folder / "strings.xml")
            for key in (
                "st_official_download_cancelled",
                "st_official_switching_from_neighbors",
                "st_official_progress_tpl",
                "st_official_paused",
                "st_official_cancel_download",
                "st_official_retry_download",
                "receiving_stalled_tpl",
            ):
                self.assertIn(f'name="{key}"', strings)

    def test_stall_policy_has_a_unit_test(self):
        test = read(ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/update/ApkUpdateTest.kt")
        self.assertIn("stalledIncomingRowIsDroppedBeforeAskingAnotherSeed", test)


class SiteFirstUpdateTest(unittest.TestCase):
    """Явное скачивание: сначала актуальный сайт, затем запасные источники."""

    def setUp(self):
        self.checker = read(JAVA / "service/UpdateChecker.kt")
        self.downloads = read(JAVA / "service/OfficialUpdateDownloads.kt")
        self.policy = read(JAVA / "service/OfficialUpdatePolicy.kt")
        self.mirror = read(JAVA / "service/UpdateSiteMirror.kt")
        self.worker = read(JAVA / "worker/OfficialUpdateMonitorWorker.kt")

    def test_update_dialog_and_settings_choose_the_official_coordinator(self):
        for name in ("MainActivity.kt", "ui/update/UpdateViewModel.kt", "ui/screens/settings/SettingsViewModel.kt"):
            self.assertRegex(read(JAVA / name), r"downloadApk\([^\n]*preferOfficial\s*=\s*true")
        self.assertIn("UpdateSiteMirror.currentCopy(releaseInfo", self.checker)
        self.assertIn("officialDownloads.start(releaseInfo, site)", self.checker)

    def test_stale_metadata_cannot_turn_into_a_pinned_old_apk(self):
        self.assertIn("!canUseRelayMetadata(metadataStale, response.cacheHeader)", self.checker)
        self.assertIn('conn.getHeaderField("X-Apu-Cache")', self.checker)
        self.assertIn('setRequestProperty("Cache-Control", "no-cache, no-store")', self.checker)
        self.assertIn('/update/latest?nocache=', self.checker)
        self.assertIn('/releases/latest?nocache=', self.checker)
        self.assertIn("siteApkMatchesLatest(", self.mirror)
        self.assertIn('json.optBoolean("allowSiteMirror", false)', read(JAVA / "service/UpdateNotifier.kt"))
        download = self.checker.split("suspend fun downloadApk(", 1)[1].split("private fun downloadApkConfirmed", 1)[0]
        self.assertIn("if (preferOfficial) {", download)
        self.assertIn("confirmOfficialDownloadRelease(releaseInfo", download)
        self.assertIn("checkForUpdate(currentAppVersion(), manual = true)", download)
        self.assertNotIn("!releaseInfo.allowSiteMirror", download)
        self.assertIn("pinnedGithubApkUrl(attempt.version)", self.policy)

    def test_managed_files_wait_for_validation_and_survive_restart(self):
        self.assertIn("officialDownloads.mayExposeCompleted", self.checker)
        self.assertIn("getPackageArchiveInfo", self.downloads)
        self.assertIn("ApkUpdate.sha256OfFile", self.downloads)
        self.assertIn("KEY_START_AFTER_ID", self.downloads)
        self.assertIn("matchesReplacement(manager(), id, pending, source)", self.downloads)
        self.assertIn("OfficialUpdateMonitor.restore", read(JAVA / "MessengerApplication.kt"))
        self.assertIn("reconcileOfficialDownload", self.worker)
        self.assertIn("OfficialUpdateDownloadReceiver", read(ROOT / "android-app/app/src/main/AndroidManifest.xml"))

    def test_executable_switching_simulation_is_in_ci(self):
        script = read(ROOT / "scripts/ci/check-chat-history.sh")
        self.assertIn("check-update-download-sim.sh", script)
        self.assertIn("check-update-check-sim.sh", script)
        harness = read(ROOT / "scripts/ci/update-download-sim/DownloadHarness.kt")
        self.assertIn("interruptedInitialEnqueue()", harness)
        self.assertIn("interruptedSwitchAndCancellation()", harness)
        self.assertIn("duplicateEventsAreSerialized()", harness)
        freshness = read(ROOT / "scripts/ci/update-check-sim/CheckHarness.kt")
        self.assertIn("checker.downloadApk(card(marker), preferOfficial = true)", freshness)
        self.assertIn('staleRelayRejected("stale", "absent")', freshness)
        self.assertIn("pinnedGithubApkUrl(NEW)", freshness)

    def test_site_hint_and_fallback_status_are_localized(self):
        for folder in ("values", "values-en"):
            strings = read(RES / folder / "strings.xml")
            for key in ("upd_site_priority_hint", "upd_fresh_check_unavailable", "st_official_trying_fallback", "st_official_verifying"):
                self.assertIn(f'name="{key}"', strings)


if __name__ == "__main__":
    unittest.main(verbosity=2)
