#!/usr/bin/env python3
"""Splash source/art contracts. Not a GPU/phone visual or frame-rate test."""
import struct
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/components"


def source(name):
    return (COMPONENTS / name).read_text()


class SplashSceneTest(unittest.TestCase):
    def test_dedicated_lossless_art_is_high_resolution_and_not_an_upscaled_launcher(self):
        art = ROOT / "android-app/app/src/main/res/drawable-nodpi/splash_digital_core.webp"
        data = art.read_bytes()
        self.assertEqual(data[:4], b"RIFF")
        self.assertEqual(data[8:16], b"WEBPVP8L")
        self.assertEqual(data[20], 0x2f)
        packed = struct.unpack("<I", data[21:25])[0]
        width = (packed & 0x3fff) + 1
        height = ((packed >> 14) & 0x3fff) + 1
        self.assertGreaterEqual(width, 1024)
        self.assertGreaterEqual(height, 1024)
        self.assertEqual(width, height)
        self.assertLess(len(data), 2_000_000)
        self.assertNotIn("mipmap.ic_launcher", source("AppSplash.kt"))
        self.assertIn("R.drawable.splash_digital_core", source("SplashCoreScene.kt"))

    def test_scene_fits_without_crop_or_density_resampling(self):
        scene = source("SplashCoreScene.kt")
        self.assertIn("ContentScale.Fit", scene)
        self.assertIn("FilterQuality.High", scene)
        self.assertIn("inScaled = false", scene)
        self.assertNotIn("ContentScale.Crop", scene)
        splash = source("AppSplash.kt")
        self.assertIn("SplashOrbitGeometry.heroSizeDp", splash)
        self.assertIn("statusBarsPadding()", splash)
        self.assertIn("navigationBarsPadding()", splash)

    def test_old_node_links_and_linear_packet_flights_are_removed(self):
        splash = source("AppSplash.kt")
        self.assertNotIn("val nodes", splash)
        self.assertNotIn("val links", splash)
        self.assertNotIn("splash-data", splash)
        self.assertNotIn("drawLine(", splash)
        scene = source("SplashCoreScene.kt")
        self.assertNotIn("drawLine(", scene)
        self.assertIn("SplashOrbitGeometry.project", scene)

    def test_electrons_have_perspective_occlusion_and_spherical_materials(self):
        math = source("SplashOrbitGeometry.kt")
        self.assertIn("CAMERA_DISTANCE / (CAMERA_DISTANCE - z)", math)
        self.assertIn("point.depth < 0", math)
        scene = source("SplashCoreScene.kt")
        self.assertIn("sortedBy { it.depth }", scene)
        self.assertIn("SplashOrbitGeometry.hiddenByCore(point)", scene)
        self.assertIn("Brush.radialGradient", scene)
        self.assertIn("point.scale.toFloat()", scene)

    def test_art_decoding_is_off_ui_thread_and_paths_are_cached(self):
        scene = source("SplashCoreScene.kt")
        self.assertIn("withContext(Dispatchers.IO)", scene)
        self.assertIn("produceState<ImageBitmap?>", scene)
        self.assertIn(".drawWithCache", scene)
        self.assertLess(scene.index("val paths ="), scene.index("onDrawWithContent"))
        self.assertIn("rememberInfiniteTransition", scene)
        self.assertNotIn("withFrameNanos", scene)

    def test_readiness_limits_and_warm_start_bypass_are_preserved(self):
        splash = source("AppSplash.kt")
        self.assertIn("SPLASH_MAX_MILLIS = 10_000L", splash)
        self.assertIn("SPLASH_MIN_MILLIS = 900L", splash)
        self.assertIn("SPLASH_FADE_MILLIS = 420", splash)
        self.assertIn("CoreStatus.ready.first { it }", splash)
        self.assertIn("SystemClock.elapsedRealtime()", splash)
        self.assertIn("fun CoreWarmBar()", splash)
        main = (ROOT / "android-app/app/src/main/java/com/vladimir/messenger/MainActivity.kt").read_text()
        self.assertIn("mutableStateOf(!com.vladimir.messenger.service.CoreStatus.ready.value)", main)
        self.assertNotIn("art != null", splash)

    def test_platform_launch_background_matches_the_native_scene(self):
        res = ROOT / "android-app/app/src/main/res"
        for folder in ("values", "values-v31"):
            xml = ET.parse(res / folder / "themes.xml")
            items = {item.attrib["name"]: item.text for item in xml.findall("./style/item")}
            self.assertEqual("#010A16", items["android:windowBackground"])
            if folder == "values-v31":
                self.assertEqual("#010A16", items["android:windowSplashScreenBackground"])

    def test_dark_splash_bars_are_restored_on_exit(self):
        splash = source("AppSplash.kt")
        self.assertIn("controller.isAppearanceLightStatusBars = false", splash)
        self.assertIn("controller.isAppearanceLightNavigationBars = false", splash)
        self.assertIn("onDispose", splash)
        self.assertIn("controller.isAppearanceLightStatusBars = !darkTheme", splash)
        self.assertIn("controller.isAppearanceLightNavigationBars = originalNavigationMode", splash)


if __name__ == "__main__":
    unittest.main(verbosity=2)
