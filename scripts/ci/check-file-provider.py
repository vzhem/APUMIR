#!/usr/bin/env python3
"""Контракт FileProvider: наружу отдаются только каталоги «на передачу».

Зачем. До 2026-10-26 в `res/xml/file_paths.xml` стоял `<root-path path="."/>`,
то есть FileProvider мог отдать по `content://` ЛЮБОЙ файл, который читает
приложение, — было бы что передать в `getUriForFile`. Обратная проблема тоже
уже случалась: принятый APK лежал в `noBackupFilesDir/file_received`, каталог
не был объявлен, `getUriForFile` бросал «Failed to find configured root», и
кнопка «Установить» молчала.

Проверяются обе стороны сразу:
  1) в `file_paths.xml` нет `<root-path>` и нет `<path="."/>` — ни один
     каталог не объявляется целиком;
  2) каждый каталог, который реально уходит в `getUriForFile`, объявлен —
     иначе на телефоне будет «Failed to find configured root»;
  3) установка обновления из `noBackupFilesDir` сначала копируется в
     объявленный `cache/updates`.

Запуск: python3 scripts/ci/check-file-provider.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "android-app/app/src/main"
MAIN = APP / "java/com/vladimir/messenger"
PATHS_XML = APP / "res/xml/file_paths.xml"


class FileProviderContract(unittest.TestCase):
    def setUp(self):
        # Комментарии выкидываем: в них объясняется, ЧТО здесь было раньше
        # («<root-path path="."/>»), и проверка не должна ловить саму себя.
        self.xml = PATHS_XML.read_text()
        self.body = re.sub(r"<!--.*?-->", " ", self.xml, flags=re.S)
        self.entries = [
            (m.group(1), m.group(2), m.group(3))
            for m in re.finditer(
                r"<(files-path|cache-path|external-path|external-files-path)\s+"
                r'name="([^"]*)"\s+path="([^"]*)"',
                self.body,
            )
        ]

    def test_no_root_path_and_no_whole_area_declarations(self):
        """Корень ФС и целые files/cache/external наружу не отдаются."""
        self.assertNotIn("<root-path", self.body, "root-path снова открывает всю ФС")
        for tag, name, path in self.entries:
            with self.subTest(entry=name):
                self.assertNotEqual(path.strip("/"), "", f"{name}: объявлен корень хранилища")
                self.assertNotEqual(path.strip("/"), ".", f"{name}: объявлен корень хранилища")

    def test_every_declared_path_is_a_real_transfer_directory(self):
        """Объявлены ровно те каталоги, куда APU кладёт файл перед передачей."""
        expected = {
            "avatar": ("files-path", "avatar/"),
            "wallpaper": ("files-path", "wallpaper/"),
            "apk_seed": ("files-path", "apk_seed/v1/"),
            "shared": ("cache-path", "shared/"),
            "voice": ("cache-path", "voice/"),
            "story_camera": ("cache-path", "story-camera/"),
            "updates": ("cache-path", "updates/"),
            "invite": ("cache-path", "invite/"),
            "feedback": ("cache-path", "feedback/"),
            "downloads": ("external-path", "Download/"),
        }
        got = {name: (tag, path) for tag, name, path in self.entries}
        self.assertEqual(expected, got)

    def test_install_of_received_apk_is_copied_into_declared_cache(self):
        """Принятый APK лежит в noBackupFilesDir — его копируют в cache/updates.

        Тега <no-backup-path> в androidx нет (в FileProvider.java разбираются
        только root/files/cache и пять external-*), поэтому объявить
        noBackupFilesDir нельзя — только скопировать файл в объявленный
        каталог.
        """
        seeder = (MAIN / "data/update/ApkSeeder.kt").read_text()
        self.assertIn("UpdateFileRoots.installHandoff(appContext)", seeder)
        self.assertIn("UpdateFileRoots.migrateLegacySeed(appContext)", seeder)
        roots = (MAIN / "data/update/UpdateFileRoots.kt").read_text()
        # Действующее место — filesDir; noBackupFilesDir остаётся только как
        # источник для переноса уже скачанного.
        self.assertIn("fun seed(context: Context): File = File(context.filesDir, ApkUpdate.DIR_NAME)", roots)
        self.assertIn("fun legacySeed(context: Context): File = File(context.noBackupFilesDir,", roots)

    def test_no_code_path_passes_a_whole_storage_root(self):
        """getUriForFile не получает каталоги верхнего уровня."""
        offenders = []
        for path in sorted(MAIN.rglob("*.kt")):
            text = path.read_text()
            for match in re.finditer(r"getUriForFile\(([^)]*)\)", text):
                args = match.group(1)
                if re.search(r"\b(filesDir|cacheDir|noBackupFilesDir|dataDir)\s*[,)]", args):
                    offenders.append(f"{path.relative_to(MAIN)}: {args.strip()}")
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main(verbosity=2)
