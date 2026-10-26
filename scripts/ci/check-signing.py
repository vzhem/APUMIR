#!/usr/bin/env python3
"""Контракт подписи релиза: без ротированного ключа релиз не выпускается.

Что защищаем. `android-app/app/p2p-release.jks` и пароль к нему лежат в
публичном репозитории с самого начала. Владелец выполнил ротацию
(docs/SIGNING_KEY_ROTATION.md): новый ключ живёт в GitHub Secrets, а в
репозитории остаётся только СТАРЫЙ — он нужен CI как первый подписант для
Android 8 (minSdk 26), где ротации ключа нет вовсе. Ключ публичный по
замыслу и защищать его поздно; защищать нужно другое — чтобы релиз не ушёл
подписанным ОДНИМ старым ключом (такой APK не установится поверх
ротированного и снова открывает окно подмены).

Проверяется:
  1) секреты ротированного ключа объявлены в workflow;
  2) на теге без секретов сборка падает ДО 40-минутной сборки (шаг
     «Require rotated signing key for a release»), а не после;
  3) шаг переподписи падает, если родословная закоммичена, а секретов нет;
  4) родословная `apu-signing-lineage` закоммичена (иначе телефоны после
     ротации не примут обновление);
  5) `*.jks` в .gitignore — новый ключ в репозиторий случайно не попадёт.

Запуск: python3 scripts/ci/check-signing.py
"""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/build-release.yml"
APP = ROOT / "android-app/app"
LINEAGE = APP / "apu-signing-lineage"
GRADLE = APP / "build.gradle.kts"


class ReleaseSigningContract(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_rotated_key_secrets_are_used(self):
        for secret in (
            "secrets.APU_RELEASE_KEYSTORE_B64",
            "secrets.APU_RELEASE_KEYSTORE_PASSWORD",
            "secrets.APU_RELEASE_KEY_ALIAS",
        ):
            with self.subTest(secret=secret):
                self.assertIn(secret, self.workflow)

    def test_tag_without_secrets_fails_before_the_build(self):
        """Гейт стоит ДО сборки: иначе APK собран, а потом отброшен."""
        guard = self.workflow.find("Require rotated signing key for a release")
        build = self.workflow.find("- name: Build Release APK")
        self.assertGreater(guard, 0, "нет раннего гейта секретов")
        self.assertGreater(build, 0, "нет шага сборки")
        self.assertLess(guard, build, "гейт секретов должен идти до сборки APK")
        gate = self.workflow[guard:build]
        self.assertIn("github.ref_type == 'tag'", gate)
        self.assertIn("exit 1", gate)

    def test_resign_fails_when_lineage_is_committed_but_secrets_are_missing(self):
        step = self.workflow[self.workflow.find("Re-sign with the rotated release key"):]
        start = step.find('if [ -z "$APU_KS_B64" ]')
        missing = step[start: step.find('if [ ! -f "$LINEAGE" ]')]
        self.assertGreater(len(missing), 0, "не разобрать ветку «секрета нет»")
        # Внутри «секрета нет»: если родословная закоммичена — падаем,
        # и это должно быть ДО тихого «подпишем старым ключом».
        self.assertIn('if [ -f "$LINEAGE" ]', missing)
        self.assertLess(missing.find("exit 1"), missing.find("exit 0"),
                        "тихий exit 0 стоит раньше жёсткого отказа")

    def test_lineage_is_committed(self):
        self.assertTrue(LINEAGE.is_file(), "нет файла apu-signing-lineage")
        try:
            out = subprocess.run(
                ["git", "ls-files", "--error-unmatch", str(LINEAGE.relative_to(ROOT))],
                cwd=ROOT, capture_output=True, text=True, check=False,
            )
        except FileNotFoundError:
            self.skipTest("git недоступен")
        if out.returncode not in (0, 1):
            self.skipTest("git не отвечает")
        self.assertEqual(0, out.returncode, "apu-signing-lineage не закоммичен")

    def test_new_keys_never_land_in_the_repository(self):
        ignore = (ROOT / ".gitignore").read_text()
        self.assertIn("*.jks", ignore)
        gradle = GRADLE.read_text()
        # Единственный разрешённый ключ в репозитории — старый публичный.
        # Любой другой путь к хранилищу означает новый ключ в git.
        keystores = set(re.findall(r'file\("([^"]+\.jks)"\)', gradle))
        self.assertEqual({"p2p-release.jks"}, keystores)


if __name__ == "__main__":
    unittest.main(verbosity=2)
