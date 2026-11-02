#!/usr/bin/env python3
"""Contract: external maps open only on a tap, with a persistent local default."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class MapOpeningContract(unittest.TestCase):
    def test_both_location_surfaces_share_the_explicit_action(self):
        card = read("ui/components/LocationCard.kt")
        picker = read("ui/components/LocationPicker.kt")
        self.assertIn(".clickable { openMap(location.lat, location.lon) }", card)
        self.assertIn("openMap(centerLat, centerLon)", picker)
        self.assertIn("onClick = { openMap(centerLat, centerLon) }", picker)
        self.assertNotIn("startActivity", card)
        self.assertNotIn("startActivity", picker)
        # Sending remains a separate explicit action, not a side effect of viewing.
        self.assertIn("label = stringResource(R.string.loc_send_point)", picker)
        self.assertIn("onSend(", picker)
        action = read("ui/components/ExternalMapAction.kt")
        self.assertNotIn("LaunchedEffect", action)
        self.assertNotIn("SideEffect", action)
        self.assertIn("MapAppPreferenceStore.current(context)", action)

    def test_default_is_local_persisted_and_reset_with_chat_settings(self):
        store = read("data/location/MapAppPreferenceStore.kt")
        policy = read("data/location/MapOpeningPolicy.kt")
        self.assertIn('ASK("ask", null)', policy)
        self.assertIn('const val DEFAULT_APP_ID = "ask"', policy)
        self.assertIn(".commit()", store)
        self.assertIn("MapAppPreferenceStore.initialize(this)", read("MainActivity.kt"))
        reset = read("data/settings/SettingsResetStore.kt").split("suspend fun resetChats(", 1)[1]
        self.assertIn("MapAppPreferenceStore.set", reset)
        self.assertIn("MapApp.ASK", reset)
        for forbidden in ("GroupWire", "ProfileMirror", "ChatRepository", "http://", "https://"):
            self.assertNotIn(forbidden, store)

    def test_settings_are_extracted_and_do_not_grow_the_god_file(self):
        settings = read("ui/screens/settings/SettingsScreen.kt")
        self.assertIn("item { MapAppSettingsCard() }", settings)
        self.assertLessEqual(len(settings.splitlines()), 2837)
        card = read("ui/screens/settings/MapAppSettings.kt")
        self.assertIn("ApuPremiumDialog(", card)
        self.assertIn("ApuPremiumChoiceRow(", card)
        self.assertIn("MapAppPreferenceStore.set(context, app)", card)
        self.assertIn("MapAppLauncher.isAvailable(context, it)", card)
        self.assertIn("withContext(Dispatchers.IO)", card)
        self.assertNotIn("MapAppLauncher.open(", card)
        self.assertNotIn("MapAppLauncher.openChooser(", card)
        self.assertNotIn("MapAppLauncher.openBrowser(", card)

    def test_launcher_never_silently_substitutes_a_provider(self):
        launcher = read("util/MapAppLauncher.kt")
        self.assertIn("if (!resolves(context, intent)) Result.NOT_INSTALLED", launcher)
        self.assertIn("Intent.createChooser(geo, title)", launcher)
        self.assertIn("Intent.EXTRA_INITIAL_INTENTS", launcher)
        self.assertIn(".setPackage(app.packageName)", launcher)
        self.assertIn("MapPoint(0.0, 0.0)", launcher)
        self.assertIn("Log.w(TAG", launcher)
        self.assertNotIn("CurrentLocation.fetch", launcher)
        self.assertNotIn("MapAppPreferenceStore.set", launcher)
        action = read("ui/components/ExternalMapAction.kt")
        self.assertIn("MapAppLauncher.openBrowser", action)
        self.assertIn("MapAppLauncher.openChooser", action)
        self.assertIn("R.string.map_missing_message", action)

    def test_package_visibility_is_narrow(self):
        manifest = ET.parse(ROOT / "android-app/app/src/main/AndroidManifest.xml").getroot()
        text = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text()
        self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", text)
        queries = manifest.find("queries")
        self.assertIsNotNone(queries)
        packages = {node.attrib.get(ANDROID + "name") for node in queries.findall("package")}
        self.assertEqual({"ru.yandex.yandexmaps", "com.google.android.apps.maps", "ru.dublgis.dgismobile"}, packages)
        schemes = {node.attrib.get(ANDROID + "scheme") for node in queries.findall("intent/data")}
        self.assertTrue({"geo", "yandexmaps", "https"}.issubset(schemes))
        categories = {node.attrib.get(ANDROID + "name") for node in queries.findall("intent/category")}
        self.assertIn("android.intent.category.APP_BROWSER", categories)

    def test_coordinate_order_and_jvm_coverage(self):
        policy = read("data/location/MapOpeningPolicy.kt")
        self.assertIn("String.format(Locale.US", policy)
        self.assertIn("point.lat.isFinite() && point.lon.isFinite()", policy)
        self.assertIn('pt=${lonLat(point)}', policy)
        test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/location/MapOpeningPolicyTest.kt").read_text()
        for name in (
            "askIsTheDefaultAndUnknownPreferencesFailSafely",
            "yandexPlacesAPinUsingLongitudeThenLatitude",
            "twoGisOpensAnArbitraryPointUsingLongitudeThenLatitude",
            "invalidCoordinatesNeverProduceAnExternalLink",
        ):
            self.assertIn(name, test)

    def test_ru_en_strings_are_complete(self):
        names = [
            "map_settings_title", "map_settings_selected", "map_settings_dialog_hint",
            "map_app_ask", "map_app_yandex", "map_app_google", "map_app_two_gis", "map_app_browser",
            "map_app_ask_hint", "map_app_native_hint", "map_app_browser_hint", "map_app_not_installed",
            "map_chooser_title", "map_missing_title", "map_missing_message", "map_no_apps_title",
            "map_no_apps_message", "map_no_browser_message", "map_open_failed_title",
            "map_open_failed_message", "map_invalid_point", "map_choose_other", "map_open_web",
        ]
        for locale in ("values", "values-en"):
            xml = (RES / locale / "strings.xml").read_text(encoding="utf-8")
            for name in names:
                self.assertEqual(1, xml.count(f'name="{name}"'), f"{locale}: {name}")

    def test_contract_runs_in_both_ci_paths(self):
        for path in ("scripts/ci/ci-core-check.sh", ".github/workflows/build-release.yml"):
            self.assertIn("python3 scripts/ci/check-map-opening.py", (ROOT / path).read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
