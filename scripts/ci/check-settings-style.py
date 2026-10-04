#!/usr/bin/env python3
"""Profile/settings style and callback source contracts, not an Android screenshot test."""
import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
SCREENS = (
    "settings/SettingsScreen.kt", "settings/IdentityBackupScreen.kt", "settings/ProfileBackupScreen.kt",
    "settings/RankBenefitsScreen.kt", "settings/PeerRatingScreen.kt", "share/ShareProfileScreen.kt",
    "mtproxy/MtProxyListScreen.kt", "support/SupportScreen.kt",
)


def source(path):
    return (UI / path).read_text()


class SettingsStyleTest(unittest.TestCase):
    def test_all_related_cards_use_the_shared_surface(self):
        for path in SCREENS:
            with self.subTest(screen=path):
                text = source("screens/" + path)
                self.assertNotRegex(text, r"\b(?:Card|OutlinedCard|ElevatedCard)\(")
                self.assertNotIn("CardDefaults.cardColors", text)
                if "mtproxy" not in path:
                    self.assertIn("ApuSettingsCard", text)
        shared = source("components/ApuSettingsUi.kt")
        self.assertIn("ApuBubbleCard(", shared)
        self.assertIn("else ApuBubbleSurfaceColor", shared)
        self.assertIn("tonalElevation = 0.dp", shared)

    def test_all_related_headers_use_house_bubbles(self):
        for path in SCREENS:
            self.assertIn("ApuSettingsHeader", source("screens/" + path))
        shared = source("components/ApuSettingsUi.kt")
        self.assertIn("ApuHeaderBubble", shared)
        self.assertIn("overflow = TextOverflow.Ellipsis", shared)

    def test_fixed_surface_palette_is_readable_in_day_and_night(self):
        shared = source("components/ApuSettingsUi.kt")
        self.assertIn("primary = ApuBubbleAccentColor", shared)
        self.assertIn("onSurface = ApuBubbleTextColor", shared)
        self.assertIn("onSurfaceVariant = ApuBubbleMutedColor", shared)
        self.assertIn("surfaceTint = ApuBubbleAccentColor", shared)
        self.assertIn("outlineVariant = ApuBubbleAccentColor.copy(alpha = 0.16f)", shared)
        # Reuse the existing measured palette checker, not a second palette definition.
        spec = importlib.util.spec_from_file_location("chat_style", ROOT / "scripts/ci/check-chat-style.py")
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        checks.ChatStyleTest("test_text_and_actions_contrast_on_any_wallpaper").test_text_and_actions_contrast_on_any_wallpaper()

    def test_profile_keeps_all_identity_actions_and_media_permissions(self):
        text = source("screens/settings/SettingsScreen.kt")
        for event in (
            "onMyQr", "onCopyLink", "onUsername", "onEditName", "onShareProfile", "onRankBenefits",
            "AvatarHolder.set(context, null)", "cameraPermission.launch(android.Manifest.permission.CAMERA)",
            "AvatarFiles.saveCropped", "viewModel.onDisplayNameChanged(nameValue)",
            "UsernameHolder.set(usernameContext, usernameValue)", "UsernameHolder.isValid(usernameValue)",
        ):
            self.assertIn(event, text)
        self.assertIn("SelectionContainer", text)
        self.assertIn("uiState.fingerprint", text)
        self.assertIn("uiState.heartCount", text)
        self.assertIn("QrCodeGenerator.generateQrCode(uiState.inviteLink)", text)

    def test_preferences_and_account_actions_keep_the_original_handlers(self):
        text = source("screens/settings/SettingsScreen.kt")
        for event in (
            "ThemeModeHolder.set(context, it)", "WallpaperHolder.set(context, uri.toString())",
            "viewModel::onProxyTunnelToggle", "viewModel::onRestartEngine", "SwarmSettings.set(context, mode)",
            "StorageSettings", "showSyncDialog = true", "ProfileSyncDialog(onDismiss", "viewModel.logout()",
            "onIdentityBackupClick", "onProfileBackupClick", "onMtProxyClick", "onPeerRatingClick",
        ):
            self.assertIn(event, text)

    def test_touch_layout_and_accessibility_are_not_tiny_fixed_rows(self):
        shared = source("components/ApuSettingsUi.kt")
        self.assertIn("heightIn(min = 72.dp)", shared)
        self.assertIn("Role.Button", shared)
        self.assertIn("ApuSettingsDivider", shared)
        self.assertIn("start = 72.dp, end = 16.dp", shared)
        text = source("screens/settings/SettingsScreen.kt")
        self.assertIn("ApuSettingsLayout.profileActionColumns(maxWidth.value, fontScale)", text)
        self.assertIn("ApuSettingsLayout.horizontalThemeChoices(maxWidth.value, fontScale)", text)
        self.assertIn("Role.RadioButton", text)
        self.assertIn("selectableGroup()", text)
        self.assertNotIn("ShimmerIcon", text)

    def test_related_modals_use_the_same_palette_and_password_features_remain(self):
        main = source("screens/settings/SettingsScreen.kt")
        self.assertNotRegex(main, r"\bAlertDialog\(")
        self.assertIn("ApuSettingsDialog", main)
        sync = source("screens/settings/ProfileSyncDialog.kt")
        self.assertIn("ApuSettingsDialog", sync)
        self.assertIn("ui.passwordAuto", sync)
        self.assertIn("value = ui.password", sync)
        self.assertIn("viewModel.stopShare()", sync)
        self.assertIn("!ui.busy && !ui.restarting", sync)
        for file in ("ProfileBackupScreen.kt", "IdentityBackupScreen.kt"):
            text = source("screens/settings/" + file)
            self.assertIn(".imePadding()", text)
            self.assertIn("PasswordVisualTransformation", text)

    def test_large_profile_initials_and_avatar_io_are_preserved(self):
        avatar = source("components/MyAvatar.kt")
        self.assertIn("size: Int = 52", avatar)
        self.assertIn("Avatar(name = displayName, modifier = modifier, size = size)", avatar)
        self.assertIn("AvatarBitmaps.loadUri(context, avatarUri)", avatar)
        self.assertIn("AvatarBitmaps.cachedUri(avatarUri)", avatar)
        self.assertIn("size = 88", source("screens/settings/SettingsScreen.kt"))
        self.assertIn("size = 96", source("screens/share/ShareProfileScreen.kt"))

    def test_groups_screen_and_create_dialog_use_house_style(self):
        groups = source("screens/groups/GroupsScreen.kt")
        self.assertNotRegex(groups, r"\bAlertDialog\(")
        self.assertIn("ApuSettingsHeader(\"Сообщества\")", groups)
        self.assertIn("ApuSettingsSectionTitle(title)", groups)
        self.assertIn("ApuSettingsDialog(", groups)
        self.assertIn("CommunityTypeChoices(", groups)
        self.assertIn("ApuSettingsLayout.horizontalCommunityTypeChoices(maxWidth.value, fontScale)", groups)
        self.assertIn("Role.RadioButton", groups)
        self.assertIn("Role.Switch", groups)

    def test_moderation_dialog_and_profile_anti_rating_use_house_style(self):
        mod_dialog = source("components/ApuModerationDialog.kt")
        self.assertNotRegex(mod_dialog, r"\bAlertDialog\(")
        self.assertIn("ApuSettingsDialog(", mod_dialog)
        self.assertIn("ApuSettingsCard", mod_dialog)
        self.assertIn("Ещё действия", mod_dialog)
        self.assertIn("Ограничить права пользователя", mod_dialog)
        peer_sheet = source("components/PeerProfileSheet.kt")
        self.assertIn("ApuSettingsDialog(", peer_sheet)
        self.assertIn("antiRatingCount", peer_sheet)
        self.assertIn("onAntiRatingClick", peer_sheet)
        group_chat = source("screens/groups/GroupChatScreen.kt")
        self.assertIn("ApuMessageModerationDialog(", group_chat)
        self.assertIn("PeerProfileSheet(", group_chat)
        channel_screen = source("screens/channels/ChannelScreen.kt")
        self.assertIn("ApuMessageModerationDialog(", channel_screen)
        self.assertIn("PeerProfileSheet(", channel_screen)


if __name__ == "__main__":
    unittest.main(verbosity=2)
