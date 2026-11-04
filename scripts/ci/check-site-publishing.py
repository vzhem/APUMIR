#!/usr/bin/env python3
"""Контракт автоматической публикации релиза на официальный сайт.

Владелец потребовал: «выпускаем релиз с новыми файлами — они автоматически
загружаются на apu.clan.su», а также готовую систему REST-передач кусками
не больше 100 МБ с паузой 5 минут (таких файлов пока нет, система обязана
быть). Контракт сторожит:

  1) каталог пакетов в сборочном скрипте совпадает с каталогом приложения;
  2) релизная сборка прикладывает 9 zip пакетов и манифест к GitHub Release;
  3) workflow публикации на сайт срабатывает на опубликованный релиз,
     грузит файлы на uCoz по FTP (манифесты последними) и держит
     REST-канал кусками для файлов больше 100 МБ;
  4) сама нарезка: потолок 100 МБ, пауза между кусками, продолжение без
     повторной передачи, сборка обратно сходится по SHA-256.

Запуск:
    python3 scripts/ci/check-site-publishing.py
Код возврата: 0 — в порядке, 1 — нарушение.
"""
import importlib.util
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
WORKFLOWS = ROOT / ".github/workflows"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


packs_script = load_module("make_feature_packs", ROOT / "scripts/release/make-feature-packs.py")
chunked = load_module("chunked_upload", ROOT / "scripts/release/chunked_upload.py")


class CatalogParityTest(unittest.TestCase):
    def test_python_catalog_matches_kotlin_catalog(self):
        kotlin = read(JAVA / "data/featurepacks/FeaturePackCatalog.kt")
        kotlin_packs = {}
        for pack_id, libs in re.findall(r'id = "([a-z0-9-]+)",\s+feature.*?nativeLibs = listOf\(([^)]*)\)',
                                        kotlin, re.S):
            kotlin_packs[pack_id] = re.findall(r'"([^"]+)"', libs)
        self.assertEqual(kotlin_packs, packs_script.PACKS)

    def test_manifest_shape_matches_app_parser(self):
        # Приложение читает {"packs":[{id, abi, file, sha256}]} — форма
        # генератора обязана оставаться именно такой.
        with tempfile.TemporaryDirectory() as tmp:
            import zipfile

            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z:
                for pack_id, libs in packs_script.PACKS.items():
                    for abi in packs_script.ABIS:
                        for lib in libs:
                            z.writestr(f"lib/{abi}/{lib}", b"\x00" * 16)
            out = Path(tmp) / "out"
            manifest = packs_script.build_packs(apk, out, "v0.0.0")
            self.assertEqual(len(manifest["packs"]), 9)
            for entry in manifest["packs"]:
                for key in ("id", "abi", "file", "size", "sha256"):
                    self.assertIn(key, entry)
                self.assertTrue((out / entry["file"]).is_file())
            self.assertTrue((out / "feature-packs.json").is_file())


class ReleasePipelineTest(unittest.TestCase):
    def test_build_release_attaches_packs(self):
        workflow = read(WORKFLOWS / "build-release.yml")
        self.assertIn("scripts/release/make-feature-packs.py", workflow)
        self.assertIn("feature-pack-*.zip", workflow)
        self.assertIn("feature-packs.json", workflow)

    def test_site_publish_workflow(self):
        workflow = read(WORKFLOWS / "site-publish.yml")
        self.assertIn("types: [published]", workflow)
        for secret in ("APU_UCOZ_FTP_HOST", "APU_UCOZ_FTP_USER", "APU_UCOZ_FTP_PASSWORD"):
            self.assertIn(secret, workflow)
        # Манифесты уходят после файлов, которые они описывают.
        self.assertLess(workflow.index("feature-pack-*.zip"), workflow.index('upload site-files/feature-packs.json'))
        self.assertLess(workflow.index("upload site-files/app-release.apk"), workflow.index("upload site-files/latest.json"))
        # REST-канал кусками для больших файлов подключён, но не обязателен.
        self.assertIn("chunked_upload.py", workflow)
        self.assertIn("APU_UPLOAD_REST_BASE", workflow)
        # Релизы без пакетов (до этой автоматики) добирают пакеты из APK.
        self.assertIn("make-feature-packs.py site-files/app-release.apk", workflow)
        # Система кусков обязана доказывать работоспособность в каждом запуске.
        self.assertIn("chunked_upload.py --self-test", workflow)
        # FTP-шаг: предпроверка входа и понятная ошибка, а не молчаливый exit.
        self.assertIn("--list-only", workflow)
        self.assertIn("FTP: не удалось войти", workflow)

    def test_chunked_self_test_really_runs(self):
        import subprocess

        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts/release/chunked_upload.py"), "--self-test"],
            capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("self-test OK", result.stdout)

    def test_app_reads_same_site_paths(self):
        seeder = read(JAVA / "data/featurepacks/FeaturePackSeeder.kt")
        self.assertIn("https://apu.clan.su/downloads", seeder)
        self.assertIn("feature-packs.json", seeder)


class FakeTransport:
    def __init__(self, existing=()):
        self.existing = set(existing)
        self.puts = []

    def exists_with_size(self, name, size):
        return name in self.existing

    def put(self, name, data):
        self.puts.append(name)
        return 200


class ChunkedUploadTest(unittest.TestCase):
    def test_chunk_ceiling_is_100_mb_and_delay_is_5_minutes(self):
        self.assertEqual(chunked.MAX_CHUNK_MB, 100)
        self.assertEqual(chunked.DEFAULT_DELAY_SECONDS, 300)
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "f.bin"
            source.write_bytes(b"x" * 10)
            with self.assertRaises(ValueError):
                chunked.split_file(source, Path(tmp) / "parts", 101)

    def test_split_upload_reassemble_with_pauses_and_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "big.bin"
            source.write_bytes(bytes(range(256)) * 12000)  # ~3 МБ
            parts_dir = Path(tmp) / "parts"
            manifest = chunked.split_file(source, parts_dir, chunk_mb=1)
            self.assertEqual(len(manifest["parts"]), 3)
            self.assertTrue(all(p["size"] <= 1 * 1024 * 1024 for p in manifest["parts"]))

            sleeps = []
            transport = FakeTransport(existing={manifest["parts"][0]["name"]})
            sent = chunked.upload_parts(
                manifest, parts_dir, transport,
                delay_seconds=300, sleeper=sleeps.append, log=lambda *a: None,
            )
            # Первый кусок уже на приёмнике: паузы только между реальными
            # передачами (кусок 2, кусок 3, манифест) — 2 паузы по 300 с.
            self.assertEqual(len(sent), 2)
            self.assertEqual(sleeps, [300, 300])
            self.assertEqual(transport.puts[-1], "big.bin.parts.json")

            target = Path(tmp) / "joined.bin"
            joined = chunked.reassemble(parts_dir, target)
            self.assertEqual(joined["sha256"], manifest["sha256"])
            self.assertEqual(target.read_bytes(), source.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
