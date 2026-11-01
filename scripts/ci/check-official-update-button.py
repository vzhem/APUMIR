#!/usr/bin/env python3
"""Source wiring checks, not an Android/network integration test."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'android-app/app/src/main/java/com/vladimir/messenger'

class OfficialUpdateButton(unittest.TestCase):
    def test_button_is_permanent_and_clicks_fresh_check(self):
        ui = (MAIN / 'ui/screens/settings/SettingsScreen.kt').read_text().split('private fun ApkUpdatesCard(')[1]
        # The button itself lives in ApkUpdateDownloadItems.kt (one-tap gate);
        # the card must still place the official source before swarm seeding.
        self.assertLess(ui.index('OfficialUpdateDownloadItem('), ui.index('seed?.let'))
        self.assertEqual(1, ui.count('viewModel::onDownloadOfficialRelease'))
        item = (MAIN / 'ui/screens/settings/ApkUpdateDownloadItems.kt').read_text()
        self.assertIn('R.string.st_official_download', item)
        self.assertIn('onClick = if (busy) null else onClick', item)
        vm = (MAIN / 'ui/screens/settings/SettingsViewModel.kt').read_text().split('fun onDownloadOfficialRelease()')[1].split('private val officialDownloadReceiver')[0]
        self.assertLess(vm.index('checkForUpdate('), vm.index('downloadApk('))
        self.assertIn('if (release == null)', vm)
        self.assertIn('outcome == "up_to_date"', vm)
        self.assertIn('finally', vm)
        # One-tap gate: visible "starting" state is raised in the tap itself,
        # before the network request, and released in finally.
        self.assertIn('officialTapGate.tryAcquire()', vm)
        self.assertIn('_officialDownloadStarting.value = true', vm)
        self.assertLess(vm.index('_officialDownloadStarting.value = true'), vm.index('viewModelScope.launch'))
        self.assertIn('st_download_request_accepted', vm)
        self.assertIn('preferOfficial = true', vm)

    def test_explicit_source_does_not_change_automatic_default(self):
        checker = (MAIN / 'service/UpdateChecker.kt').read_text()
        self.assertIn('preferOfficial: Boolean = false', checker)
        self.assertIn('!preferOfficial && directSwarmHasNewer', checker)
        self.assertIn('if (preferOfficial) extendManualWindow', checker)

if __name__ == '__main__':
    unittest.main()
