#!/usr/bin/env python3
"""Контракт докачиваемых пакетов функций (лайт-база + раздел в настройках).

Решение владельца: полноценное приложение ставится маленьким, а тяжёлый
функционал владелец докачивает переключателями в настройках — без
ограничений по рангам. Контракт сторожит три вещи, которые легко потерять
тихой правкой:

  1) каталог пакетов — один (`data/featurepacks/FeaturePackCatalog.kt`),
     ядро сети (libp2p_core) не является пакетом и остаётся в базе;
  2) раздел «Дополнительные функции» существует в настройках и не смотрит
     на ранги вообще (ни FeatureRankPolicy, ни RankCapability);
  3) строки раздела есть в обеих локалях (иначе английская сборка покажет
     русский текст).

Запуск:
    python3 scripts/ci/check-feature-packs.py
Код возврата: 0 — в порядке, 1 — нарушение.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class LightBaseContract(unittest.TestCase):
    def test_light_base_flag_excludes_native_libs(self):
        gradle = read(ROOT / "android-app/app/build.gradle.kts")
        self.assertIn("apu.lightBase", gradle)
        for lib in [
            "libxeno_native.so", "libtranslate_jni.so",
            "liblanguage_id_l2c_jni.so", "libvosk.so",
            "libface_detector_v2_jni.so",
        ]:
            self.assertIn(lib, gradle, lib)

    def test_runtime_loads_libs_from_pack(self):
        runtime = read(JAVA / "data/featurepacks/FeaturePackRuntime.kt")
        self.assertIn("System.load", runtime)
        self.assertIn("feature_packs", runtime)
        seeder = read(JAVA / "data/featurepacks/FeaturePackSeeder.kt")
        self.assertIn("FeaturePackRuntime.extract", seeder)

    def test_native_callers_are_guarded(self):
        voice = read(JAVA / "data/voice/VoiceTranscriber.kt")
        self.assertIn("VOICE_TRANSCRIPTION", voice)
        self.assertIn("FeaturePackRuntime.ensureLoaded", voice)
        ml = read(JAVA / "data/story/StoryFaceTracker.kt")
        self.assertIn("VIDEO_FACE_EFFECTS", ml)
        self.assertIn("FeaturePackRuntime.ensureLoaded", ml)
        translator = read(JAVA / "data/translate/MessageTranslator.kt")
        self.assertIn("MESSAGE_TRANSLATION", translator)
        self.assertIn("FeaturePackRuntime.ensureLoaded", translator)


class FeaturePacksContract(unittest.TestCase):
    def test_catalog_is_single_and_keeps_core_in_base(self):
        catalog = read(JAVA / "data/featurepacks/FeaturePackCatalog.kt")
        for pack_id in ("translate", "voice-text", "face-effects"):
            self.assertIn(f'id = "{pack_id}"', catalog)
        for lib in ("libxeno_native.so", "libtranslate_jni.so", "libvosk.so", "libface_detector_v2_jni.so"):
            self.assertIn(lib, catalog)
        packed_libs = re.findall(r'nativeLibs = listOf\(([^)]*)\)', catalog)
        self.assertTrue(packed_libs)
        self.assertNotIn("p2p_core", "".join(packed_libs),
                         "network core must stay in the base APK, not in a pack")
        self.assertIn("fun totalBytes(", catalog)
        self.assertIn("fun releaseFileNames(", catalog)

    def test_settings_section_exists_without_rank_gates(self):
        screen = read(JAVA / "ui/screens/settings/SettingsScreen.kt")
        self.assertIn("SettingsSection.Features", screen)
        self.assertIn("FeaturePacksSettings()", screen)
        # Пункт обязан быть в корневом списке настроек: без него раздел
        # существует, но владелец не может в него попасть (было в 482).
        self.assertIn("onSectionChange(SettingsSection.Features)", screen)
        settings = read(JAVA / "ui/screens/settings/FeaturePacksSettings.kt")
        self.assertIn("FeaturePackCatalog.packs.forEach", settings)
        self.assertIn("ApuPremiumSwitch", settings)
        # Докачка — без рангов: ни политики, ни прав, ни подсказок про ранг.
        for forbidden in ("FeatureRankPolicy", "RankCapability", "RankRights", "qualified"):
            self.assertNotIn(forbidden, settings)
            self.assertNotIn(
                forbidden,
                read(JAVA / "data/featurepacks/FeaturePackStore.kt"),
            )

    def test_swarm_distribution_is_wired_and_official_is_fallback(self):
        wire = read(JAVA / "data/group/GroupWire.kt")
        for packet in ("FeaturePackHave", "FeaturePackWant", "FeaturePackNone", "FeaturePackAsk"):
            self.assertIn(f"data class {packet}(", wire)
            self.assertIn(f"fun build{packet}(", wire)
        repo = read(JAVA / "data/group/GroupRepository.kt")
        for callback in ("onFeaturePackHave", "onFeaturePackWant", "onFeaturePackNone", "onFeaturePackAsk"):
            self.assertIn(callback, repo)
        module = read(JAVA / "di/GroupsModule.kt")
        self.assertIn("featurePackSeeder", module)
        seeder = read(JAVA / "data/featurepacks/FeaturePackSeeder.kt")
        # Порядок владельца: сайт в первую очередь, рой — запасной.
        self.assertIn("RustBridge.liveSwarmPeerIds()", seeder)
        self.assertIn("groupSeeder.offer", seeder)
        self.assertIn("apu.clan.su/downloads", seeder)
        self.assertIn("SWARM_WAIT_MS", seeder)
        self.assertIn("fallbackToSwarm = true", seeder)
        self.assertIn("Сайт — в первую очередь", seeder)
        # Целостность: пакет принимается только по SHA-256.
        self.assertIn("sha256OfFile", seeder)
        router = read(JAVA / "data/file/FileTransferRouter.kt")
        self.assertIn("routePackOffer", router)
        service = read(JAVA / "service/CoreServerService.kt")
        self.assertIn("featurePackSeeder.pump()", service)

    def test_strings_exist_in_both_locales(self):
        keys = (
            "settings_nav_features", "settings_nav_features_hint",
            "feature_packs_intro", "feature_packs_pack_translate",
            "feature_packs_pack_translate_hint", "feature_packs_pack_voice",
            "feature_packs_pack_voice_hint", "feature_packs_pack_face",
            "feature_packs_pack_face_hint", "feature_packs_size",
            "feature_packs_download_all", "feature_packs_select_all",
            "feature_packs_total", "feature_packs_total_selected",
            "feature_packs_pending",
            "feature_packs_status_installed", "feature_packs_status_asking",
            "feature_packs_status_downloading", "feature_packs_status_official",
            "feature_packs_status_failed", "feature_packs_download",
            "feature_packs_cancel", "feature_packs_retry",
            "feature_packs_status_not_downloaded",
            "feature_packs_status_site_downloading",
            "feature_packs_status_swarm_downloading",
            "feature_packs_status_progress", "feature_packs_status_ready",
            "feature_packs_status_ready_seeding",
            "feature_packs_status_downloaded_off",
            "feature_packs_status_searching", "feature_packs_summary_ready",
            "feature_packs_summary_none", "feature_packs_status_bundled",
        )
        for folder in ("values", "values-en"):
            text = read(RES / folder / "strings.xml")
            for key in keys:
                self.assertEqual(
                    len(re.findall(rf'<string name="{key}"', text)), 1,
                    f"{key} must exist exactly once in {folder}",
                )


class VisibleProgressContract(unittest.TestCase):
    """Владелец должен видеть: что качается, что скачалось, чем можно пользоваться."""

    def test_screen_shows_badges_progress_and_summary(self):
        screen = read(JAVA / "ui/screens/settings/FeaturePacksSettings.kt")
        for needle in [
            "StatusBadge",
            "LinearProgressIndicator",
            "feature_packs_summary_ready",
            "feature_packs_status_ready_seeding",
            "feature_packs_status_downloaded_off",
            "feature_packs_status_not_downloaded",
            "feature_packs_status_progress",
        ]:
            self.assertIn(needle, screen, needle)

    def test_two_download_variants_exist(self):
        # Выбор при скачивании: лёгкая база и полная версия одним файлом.
        workflow = read(ROOT / ".github/workflows/build-release.yml")
        self.assertIn("app-full.apk", workflow)
        self.assertIn("Build full APK", workflow)
        runtime = read(JAVA / "data/featurepacks/FeaturePackRuntime.kt")
        self.assertIn("tryLoadBundled", runtime)
        self.assertIn("System.loadLibrary", runtime)
        landing = read(ROOT / "website/ucoz-landing.html")
        self.assertIn("app-full.apk", landing)
        self.assertIn("Полная версия одним файлом", landing)
        screen = read(JAVA / "ui/screens/settings/FeaturePacksSettings.kt")
        self.assertIn("feature_packs_status_bundled", screen)

    def test_official_download_reports_progress_and_cancels(self):
        seeder = read(JAVA / "data/featurepacks/FeaturePackSeeder.kt")
        for needle in [
            "contentLengthLong",
            "onProgress",
            "downloadJobs",
            "CancellationException",
        ]:
            self.assertIn(needle, seeder, needle)


if __name__ == "__main__":
    unittest.main(verbosity=2)
