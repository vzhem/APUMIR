#!/usr/bin/env python3
"""Контракт вспышки фонарика при SOS из группы и входящих сообщениях.

Запуск: python3 scripts/ci/check-flashlight-alerts.py
"""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"

NEW_STRINGS = (
    "flashlight_sos_title",
    "flashlight_sos_hint",
    "flashlight_messages_title",
    "flashlight_messages_dialog_hint",
    "flashlight_unavailable_hint",
    "flashlight_mode_off",
    "flashlight_mode_off_hint",
    "flashlight_mode_all",
    "flashlight_mode_all_hint",
    "flashlight_mode_personal",
    "flashlight_mode_personal_hint",
    "flashlight_mode_groups",
    "flashlight_mode_groups_hint",
    "flashlight_mode_channels",
    "flashlight_mode_channels_hint",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class FlashlightAlertsContract(unittest.TestCase):
    def test_policy_is_pure_and_covered_by_jvm_runner(self):
        policy = read(MAIN / "data/notification/FlashlightAlertPolicy.kt")
        for forbidden in ("import android.", "Context", "CameraManager"):
            self.assertNotIn(forbidden, policy)
        for token in (
            "DEFAULT_SOS_ENABLED: Boolean = true",
            "DEFAULT_MESSAGE_MODE: FlashlightMessageMode = FlashlightMessageMode.OFF",
            "OFF(\"off\")",
            "ALL(\"all\")",
            "PERSONAL(\"personal\")",
            "GROUPS(\"groups\")",
            "CHANNELS(\"channels\")",
            "fun shouldBlinkForSos(",
            "fun shouldBlinkForMessage(",
            "fun decideSos(",
            "fun decideMessage(",
        ):
            self.assertIn(token, policy)

        runner = read(ROOT / "scripts/ci/check-chat-history.sh")
        self.assertIn("FlashlightAlertPolicy.kt", runner)
        self.assertIn("FlashlightAlertPolicyTest", runner)
        self.assertTrue((TEST / "data/notification/FlashlightAlertPolicyTest.kt").is_file())

    def test_store_is_device_local_and_reset_with_notifications(self):
        store = read(MAIN / "data/notification/FlashlightAlertStore.kt")
        self.assertIn('"notification_flashlight"', store)
        self.assertIn(".commit()", store)
        for forbidden in ("GroupWire", "ProfileMirror", "ChatRepository", "http://", "https://"):
            self.assertNotIn(forbidden, store)

        reset = read(MAIN / "data/settings/SettingsResetStore.kt")
        self.assertIn("SettingsResetCategory.NOTIFICATIONS -> resetNotifications(context, notificationMuteStore)", reset)
        self.assertIn("FlashlightAlertStore.reset(context)", reset)
        self.assertIn("FlashlightAlertStore.initialize(this)", read(MAIN / "MainActivity.kt"))

    def test_notification_helper_wires_sos_and_message_kinds_after_guards(self):
        helper = read(MAIN / "service/NotificationHelper.kt")
        self.assertIn("private val flashlightBlinker: FlashlightBlinker", helper)
        self.assertIn("flashlightBlinker.blinkForSos(isGroup = group != null && !group.isChannel)", helper)
        msg_part = helper.split("suspend fun showMessageNotification(", 1)[1].split("suspend fun showSosNotification(", 1)[0]
        mute_idx = msg_part.index("if (isMuted(chatId, topicId, group, nowMs))")
        blink_idx = msg_part.index("flashlightBlinker.blinkForMessage(")
        self.assertGreater(blink_idx, mute_idx, "мигание обычного сообщения должно стоять после проверки паузы звука")
        self.assertIn("FlashlightMessageKind.classify(", msg_part)
        self.assertIn("silent = silent", msg_part)

    def test_blinker_restores_torch_and_bounds_wake_lock(self):
        blinker = read(MAIN / "service/FlashlightBlinker.kt")
        self.assertIn("cameraManager.setTorchMode(cameraId, enabled)", blinker)
        self.assertIn("PowerManager.PARTIAL_WAKE_LOCK", blinker)
        self.assertIn("acquire(timeoutMs)", blinker)
        self.assertIn("finally {", blinker)
        self.assertIn("setTorchSafe(cameraManager, cameraId, restoreTorchOn)", blinker)
        self.assertIn("releaseWakeLockSafe(wakeLock)", blinker)

    def test_settings_card_is_extracted_and_flash_hardware_is_optional(self):
        settings = read(MAIN / "ui/screens/settings/SettingsScreen.kt")
        notif_block = settings.split("if (section == SettingsSection.Notifications)", 1)[1].split(
            "if (section == SettingsSection.Security)", 1,
        )[0]
        self.assertIn("item { FlashlightAlertSettingsCard() }", notif_block)

        card = read(MAIN / "ui/screens/settings/FlashlightAlertSettings.kt")
        for token in (
            "ApuSettingsCard(",
            "ApuSettingsItem(",
            "ApuPremiumSwitch(",
            "ApuPremiumDialog(",
            "ApuPremiumChoiceRow(",
            "FlashlightAlertStore.setSosEnabled(context, enabled)",
            "FlashlightAlertStore.setMessageMode(context, mode)",
        ):
            self.assertIn(token, card)

        manifest = ET.parse(ROOT / "android-app/app/src/main/AndroidManifest.xml").getroot()
        features = {
            node.attrib.get(ANDROID + "name"): node.attrib.get(ANDROID + "required")
            for node in manifest.findall("uses-feature")
        }
        self.assertEqual("false", features.get("android.hardware.camera.flash"))
        self.assertEqual("false", features.get("android.hardware.camera"))

    def test_strings_exist_in_both_locales_and_are_used(self):
        card = read(MAIN / "ui/screens/settings/FlashlightAlertSettings.kt")
        ru = read(RES / "values/strings.xml")
        en = read(RES / "values-en/strings.xml")
        for key in NEW_STRINGS:
            self.assertIn(f"R.string.{key}", card, f"не используется ключ {key}")
            self.assertIn(f'name="{key}"', ru, f"нет ключа {key} в values/strings.xml")
            self.assertIn(f'name="{key}"', en, f"нет ключа {key} в values-en/strings.xml")


if __name__ == "__main__":
    unittest.main(verbosity=2)
