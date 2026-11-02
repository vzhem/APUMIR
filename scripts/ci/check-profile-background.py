#!/usr/bin/env python3
"""Contract: the profile background is a bounded, local-only customization."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class ProfileBackgroundContract(unittest.TestCase):
    def test_store_is_bounded_atomic_and_local_only(self):
        store = read("data/profile/ProfileBackgroundStore.kt")
        policy = read("data/profile/ProfileBackgroundPolicy.kt")
        self.assertIn("copyWithLimit(input, output, ProfileBackgroundPolicy.MAX_SOURCE_BYTES)", store)
        self.assertIn("Os.rename(temp.absolutePath, target.absolutePath)", store)
        self.assertIn("Files.isSymbolicLink", store)
        self.assertIn("take(64)", store)
        self.assertIn("MAX_IMAGE_BYTES", policy)
        self.assertIn("MAX_SOURCE_BYTES", policy)
        for forbidden in ("ProfileMirror", "GroupWire", "GroupWallpaper", "FileTransfer", "http://", "https://"):
            self.assertNotIn(forbidden, store)

    def test_profile_screens_use_the_background_and_settings_entry_exists(self):
        settings = read("ui/screens/settings/SettingsScreen.kt")
        share = read("ui/screens/share/ShareProfileScreen.kt")
        card = read("ui/screens/settings/ProfileBackgroundSettings.kt")
        self.assertIn("ProfileScreenBackground(active = showProfile)", settings)
        self.assertIn("ProfileBackgroundSettingsCard()", settings)
        self.assertIn("ProfileScreenBackground()", share)
        self.assertIn("PickVisualMedia", card)
        self.assertIn("ProfileBackgroundStore.import", card)
        self.assertIn("ProfileBackgroundStore.clear", card)
        self.assertNotIn("takePersistableUriPermission", card)

    def test_startup_and_appearance_reset_are_wired(self):
        self.assertIn("ProfileBackgroundStore.init(this)", read("MainActivity.kt"))
        self.assertIn("ProfileBackgroundStore.clear(context)", read("data/settings/SettingsResetStore.kt"))

    def test_strings_exist_in_both_languages(self):
        names = [
            "profile_background_title", "profile_background_hint", "profile_background_custom_set",
            "profile_background_local_hint", "profile_background_preview", "profile_background_choose",
            "profile_background_reset", "profile_background_wait", "profile_background_saved",
            "profile_background_reset_done", "profile_background_error",
        ]
        for locale in ("values", "values-en"):
            xml = (RES / locale / "strings.xml").read_text(encoding="utf-8")
            for name in names:
                self.assertEqual(1, xml.count(f'name="{name}"'), f"{locale}: {name}")

    def test_policy_has_jvm_tests(self):
        test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/profile/ProfileBackgroundPolicyTest.kt").read_text()
        self.assertIn("onlyExactManagedNamesAreAccepted", test)
        self.assertIn("samplingNeverUpscalesAndKeepsPowerOfTwo", test)


if __name__ == "__main__":
    unittest.main(verbosity=2)
