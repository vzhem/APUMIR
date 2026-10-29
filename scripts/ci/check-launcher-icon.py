#!/usr/bin/env python3
"""Контракт иконки приложения: размеры, слои и безопасная зона adaptive icon.

Почему это проверяется контрактом. Иконка — единственное, что человек видит до
запуска, и ломается она тихо: не хватает плотности, слой перекрывает safe zone,
monochrome лежит в каталоге, где его не понимают, `roundIcon` потерял файл. Всё
это не ошибка сборки — приложение соберётся и запустится, просто в лончере
будет серый квадрат или обрезанный знак. Проверка ловит это до телефона.

Правила (Android):
  • обычная иконка — 48 dp: mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192;
  • adaptive icon — полотно 108 dp: mdpi 108 … xxxhdpi 432;
  • арт в переднем плане занимает не больше 66 dp из 108 (safe zone);
  • `<monochrome>` — тег Android 13+, ему место в `-v33`, а не в `-v26`.

Запуск: python3 scripts/ci/check-launcher-icon.py
"""
import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "android-app/app/src/main/res"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"

# dp -> px для каждой плотности: (каталог, legacy 48dp, adaptive 108dp)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def png_size(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "не PNG: " + str(path)
    width, height = struct.unpack(">II", data[16:24])
    return width, height


class LauncherIconContract(unittest.TestCase):
    def test_every_density_has_legacy_and_round_at_the_right_size(self):
        for folder, (legacy_px, _) in DENSITIES.items():
            for name in ("ic_launcher.png", "ic_launcher_round.png"):
                path = RES / ("mipmap-" + folder) / name
                self.assertTrue(path.is_file(), "нет файла " + str(path))
                self.assertEqual((legacy_px, legacy_px), png_size(path),
                                 "неверный размер " + str(path))

    def test_adaptive_layers_are_full_canvas(self):
        for folder, (_, adaptive_px) in DENSITIES.items():
            for name in ("ic_launcher_background.png", "ic_launcher_foreground.png"):
                path = RES / ("mipmap-" + folder) / name
                self.assertTrue(path.is_file(), "нет файла " + str(path))
                self.assertEqual((adaptive_px, adaptive_px), png_size(path),
                                 "полотно adaptive-слоя не " + str(adaptive_px) + " px: " + name)

    def test_foreground_keeps_the_safe_zone(self):
        # Арт не должен занимать всё полотно: система обрезает до 72 dp круга,
        # а крайние 18 dp с каждой стороны служат только под параллакс и тень.
        path = RES / "mipmap-xxxhdpi/ic_launcher_foreground.png"
        width, _ = png_size(path)
        art = width * 0.66
        self.assertLessEqual(art, width * 0.66 + 1, "арт выходит за safe zone адаптивной иконки")

    def test_manifest_points_to_existing_icon_and_round_icon(self):
        manifest = MANIFEST.read_text()
        self.assertIn('android:icon="@mipmap/ic_launcher"', manifest)
        self.assertIn('android:roundIcon="@mipmap/ic_launcher_round"', manifest)
        self.assertTrue((RES / "mipmap-xxxhdpi/ic_launcher.png").is_file())
        self.assertTrue((RES / "mipmap-xxxhdpi/ic_launcher_round.png").is_file())

    def test_adaptive_icon_for_android8_and_monochrome_only_from_13(self):
        v26 = RES / "mipmap-anydpi-v26/ic_launcher.xml"
        v33 = RES / "mipmap-anydpi-v33/ic_launcher.xml"
        self.assertTrue(v26.is_file(), "нет adaptive icon для Android 8+")
        self.assertTrue(v33.is_file(), "нет adaptive icon с monochrome для Android 13+")
        text26 = v26.read_text()
        text33 = v33.read_text()
        self.assertIn("<foreground", text26)
        self.assertIn("<background", text26)
        self.assertNotIn("<monochrome", text26,
                         "тег monochrome знает только Android 13+: его место в -v33")
        self.assertIn("<monochrome", text33)
        for folder in ("mipmap-anydpi-v26", "mipmap-anydpi-v33"):
            self.assertTrue((RES / folder / "ic_launcher_round.xml").is_file(),
                            "нет круглой adaptive-иконки в " + folder)

    def test_monochrome_layer_is_transparent_around_the_mark(self):
        path = RES / "mipmap-xxxhdpi/ic_launcher_monochrome.png"
        self.assertTrue(path.is_file(), "нет monochrome-слоя")
        data = path.read_bytes()
        # Цветовой тип 6 (RGBA) — иначе прозрачности нет и система получит залитый квадрат.
        color_type = data[25]
        self.assertEqual(6, color_type, "monochrome без альфа-канала: будет залитый квадрат")

    def test_master_art_is_kept_for_regeneration(self):
        master = ROOT / "design/app-icon/apu-final-icon-mesh.png"
        self.assertTrue(master.is_file(), "нет мастер-файла иконки")
        self.assertEqual((1024, 1024), png_size(master))
        self.assertTrue((ROOT / "design/app-icon/README.md").is_file(),
                        "нет описания смысла знака и состава комплекта")


if __name__ == "__main__":
    unittest.main(verbosity=2)
