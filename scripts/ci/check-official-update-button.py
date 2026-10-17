#!/usr/bin/env python3
"""Source wiring checks, not an Android/network integration test."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'android-app/app/src/main/java/com/vladimir/messenger'

class OfficialUpdateButton(unittest.TestCase):
    def test_button_is_permanent_and_clicks_fresh_check(self):
        ui = (MAIN / 'ui/screens/settings/SettingsScreen.kt').read_text().split('private fun ApkUpdatesCard(')[1]
        self.assertLess(ui.index('R.string.st_official_download'), ui.index('seed?.let'))
        self.assertEqual(1, ui.count('viewModel::onDownloadOfficialRelease'))
        self.assertIn('onClick = if (checking) null', ui)
        vm = (MAIN / 'ui/screens/settings/SettingsViewModel.kt').read_text().split('fun onDownloadOfficialRelease()')[1].split('private fun toastIf')[0]
        self.assertLess(vm.index('checkForUpdate('), vm.index('downloadApk('))
        self.assertIn('if (release == null)', vm)
        self.assertIn('outcome == "up_to_date"', vm)
        self.assertIn('finally', vm)
        self.assertIn('STATUS_RUNNING', vm)
        self.assertIn('preferOfficial = true', vm)

    def test_explicit_source_does_not_change_automatic_default(self):
        checker = (MAIN / 'service/UpdateChecker.kt').read_text()
        self.assertIn('preferOfficial: Boolean = false', checker)
        self.assertIn('!preferOfficial && directSwarmHasNewer', checker)
        self.assertIn('if (preferOfficial) extendManualWindow', checker)

if __name__ == '__main__':
    unittest.main()
