#!/usr/bin/env python3
"""Контракт защиты от дублей профилей (ANDROID_ID), короткого кода профиля (NNN-NNN)
и привязки живых IPv4/IPv6-адресов к единому паспорту контакта.

Запуск: python3 scripts/ci/check-device-profile-binding.py
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class DeviceProfileBindingContract(unittest.TestCase):
    def test_pure_jvm_modules_stay_android_free(self):
        for rel in ("util/ProfileCallsign.kt", "data/security/DeviceProfileAnchor.kt"):
            src = read(JAVA / rel)
            self.assertNotRegex(
                src,
                r"^import\s+android\.",
                f"{rel} must stay pure JVM so CI runner tests execute without Android SDK",
            )

    def test_android_id_vault_hint_and_p2p_gossip_are_wired(self):
        binding = read(JAVA / "data/security/DeviceProfileBinding.kt")
        coord = read(JAVA / "data/security/DeviceIdentityCoordinator.kt")
        ephemeral = read(JAVA / "data/security/EphemeralControl.kt")
        policy = read(JAVA / "data/security/OutgoingPayloadPolicy.kt")
        router = read(JAVA / "data/group/GroupRouter.kt")
        app = read(JAVA / "MessengerApplication.kt")
        backup = read(JAVA / "data/security/IdentityBackup.kt")
        contacts = read(JAVA / "data/repository/ContactRepository.kt")

        self.assertIn("Settings.Secure.ANDROID_ID", binding)
        self.assertIn("discoverPreviousProfile", binding)
        self.assertIn("syncCurrentProfileToVault", binding)
        self.assertIn("DeviceProfileAnchor.Wire.PREFIX", ephemeral)
        self.assertIn("DeviceProfileAnchor.Wire.parseVerified(text)", policy)
        self.assertIn("deviceIdentities?.receive(senderId, text)", router)
        self.assertIn("deviceIdentities.start()", app)
        self.assertIn("DeviceProfileBinding.saveCurrentProfile(", backup)
        self.assertIn(".areSameVerifiedDevice(", contacts)
        self.assertIn("SwarmPeerDirectory", coord)

    def test_onboarding_detects_previous_device_profile_and_records_replacements(self):
        vm = read(JAVA / "ui/screens/onboarding/OnboardingViewModel.kt")
        screen = read(JAVA / "ui/screens/onboarding/OnboardingScreen.kt")

        self.assertIn("DeviceProfileBinding.discoverPreviousProfile(appContext, botApi)", vm)
        self.assertIn("previousOwnerId = previousOwner", vm)
        self.assertIn("showReplaceConfirmDialog = true", vm)
        self.assertIn("onConfirmReplaceProfileClicked", vm)
        self.assertIn("DeviceIdentityGossip.onProfileOrContactsChanged()", vm)
        self.assertIn("R.string.dpb_device_found_title", screen)
        self.assertIn("R.string.dpb_replace_confirm_title", screen)
        self.assertIn("R.string.dpb_replace_confirm_btn", screen)
        self.assertIn("ProfileCallsign.of(", screen)

    def test_callsign_is_visible_and_searchable_with_route_activation(self):
        badge = read(JAVA / "ui/screens/settings/ProfileIdentityBadge.kt")
        settings = read(JAVA / "ui/screens/settings/SettingsScreen.kt")
        share = read(JAVA / "ui/screens/share/ShareProfileScreen.kt")
        peer_sheet = read(JAVA / "ui/components/PeerProfileSheet.kt")
        add_vm = read(JAVA / "ui/screens/contacts/AddContactViewModel.kt")
        add_screen = read(JAVA / "ui/screens/contacts/AddContactScreen.kt")
        net_mon = read(JAVA / "service/NetworkMonitor.kt")

        self.assertIn("ProfileCallsign.of(fingerprint)", badge)
        self.assertIn("ProfileIdentityBadge(fingerprint = uiState.fingerprint)", settings)
        self.assertIn("ProfileCallsign.of(uiState.nodeId)", share)
        self.assertIn("ProfileCallsign.of(contactId)", peer_sheet)
        self.assertIn("ProfileCallsign.matches(it.ownerId, rawInput)", add_vm)
        self.assertIn("ProfileCallsign.isFullCodeQuery(pasted)", add_vm)
        self.assertIn("activatePeerRoute(", add_vm)
        self.assertIn("R.string.dpb_callsign_short_tpl", add_screen)
        self.assertIn("DeviceIdentityGossip.onProfileOrContactsChanged()", net_mon)
        self.assertIn("RustBridge.triggerGossipDiscovery()", net_mon)

    def test_localized_strings_and_unit_tests_exist(self):
        ru = read(RES / "values/strings.xml")
        en = read(RES / "values-en/strings.xml")
        for key in (
            "dpb_callsign_value_tpl",
            "dpb_callsign_short_tpl",
            "dpb_callsign_copied",
            "dpb_device_found_title",
            "dpb_replace_profile_action",
            "dpb_replace_confirm_title",
            "dpb_replace_confirm_btn",
            "dpb_callsign_not_found",
            "dpb_callsign_multiple",
        ):
            self.assertIn(f'name="{key}"', ru)
            self.assertIn(f'name="{key}"', en)

        test_src = read(TEST / "data/security/ProfileCallsignAndDeviceAnchorTest.kt")
        self.assertIn("profile callsign is deterministic 6-digit NNN-NNN", test_src)
        self.assertIn("signed wire packets verify and ledger proves reinstall replacement", test_src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
