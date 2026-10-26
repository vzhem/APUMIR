#!/usr/bin/env python3
"""Контракт релизной сборки: сокращение только осознанно, нативка без дублей.

P1-4 аудита: APK вырос с 96,6 МБ до 229,3 МБ. R8 + вырезание ресурсов дают
обычно 25–40 %, но включить их «просто так» нельзя: ядро (uniffi → JNA), Vosk и
ML Kit общаются через рефлексию, и пропущенное keep-правило выглядит как
падение на старте уже у людей. Поэтому сокращение живёт за флагом
`-Papu.r8=true`, а правила лежат в `proguard-rules.pro`.

P2 (4.7): собранные `.so` ставит Gradle только в
`android-app/app/src/main/jniLibs`; копия `rust-core/jniLibs` была устаревшей
(390 КБ против 9,2 МБ) и не использовалась — её больше нет в репозитории.

P2 (4.6): перед установкой APK проверяется разрешение
`canRequestPackageInstalls()`, и человека ведут на нужный экран настроек.

Запуск: python3 scripts/ci/check-release-config.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "android-app/app/build.gradle.kts"
RULES = ROOT / "android-app/app/proguard-rules.pro"
SEEDER = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/update/ApkSeeder.kt"
SETTINGS_VM = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/settings/SettingsViewModel.kt"


class ReleaseConfigContract(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text()
        self.rules = RULES.read_text()

    def test_shrinking_is_opt_in_and_wired_to_the_rules_file(self):
        release = self.gradle[self.gradle.find("buildTypes {"):]
        release = release[: release.find("debug {")]
        self.assertIn('providers.gradleProperty("apu.r8")', release,
                      "сокращение должно включаться флагом -Papu.r8=true")
        self.assertIn("isMinifyEnabled = r8", release)
        self.assertIn("isShrinkResources = r8", release)
        self.assertIn('"proguard-rules.pro"', release)
        self.assertTrue(RULES.is_file(), "нет proguard-rules.pro")

    def test_reflective_bridges_are_kept(self):
        for keep in (
            "-keep class uniffi.**",
            "-keep class com.sun.jna.**",
            "-keep class org.vosk.**",
            "-keep class com.google.mlkit.**",
            "-keep class org.osmdroid.**",
            "-keepattributes SourceFile,LineNumberTable",
        ):
            with self.subTest(keep=keep):
                self.assertIn(keep, self.rules)

    def test_packaging_drops_library_paperwork(self):
        for exclude in ("/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES"):
            with self.subTest(exclude=exclude):
                self.assertIn(exclude, self.gradle)

    def test_native_libraries_have_exactly_one_home(self):
        self.assertIn('jniLibs.srcDirs("src/main/jniLibs")', self.gradle)
        ignore = (ROOT / ".gitignore").read_text()
        self.assertIn("rust-core/jniLibs/", ignore, "устаревшая копия .so снова попадёт в git")
        self.assertFalse((ROOT / "rust-core/jniLibs").exists(),
                         "rust-core/jniLibs не используется сборкой — держать его не нужно")

    def test_install_permission_is_checked_before_the_installer_opens(self):
        seeder = SEEDER.read_text()
        settings_vm = SETTINGS_VM.read_text()
        self.assertIn("fun installPermissionMissing()", seeder)
        self.assertIn("canRequestPackageInstalls()", seeder)
        self.assertIn("INSTALL_PERMISSION_HINT", seeder)
        self.assertIn("ACTION_MANAGE_UNKNOWN_APP_SOURCES", settings_vm)


if __name__ == "__main__":
    unittest.main(verbosity=2)
