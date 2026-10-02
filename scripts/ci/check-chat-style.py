#!/usr/bin/env python3
"""Lightweight chat-style regression checks (source contracts + palette contrast).

Run: python3 scripts/ci/check-chat-style.py
These do not replace APK compilation or visual checks on an Android device.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"


def source(relative):
    return (UI / relative).read_text()


def color(text, name):
    match = re.search(r"\b" + name + r"\s*(?::\s*Color)?\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", text)
    if not match:
        raise AssertionError("Palette color not found: " + name)
    value = int(match.group(1), 16)
    return tuple((value >> shift & 255) / 255 for shift in (16, 8, 0))


def blend(foreground, background, alpha):
    return tuple(alpha * fg + (1 - alpha) * bg for fg, bg in zip(foreground, background))


def luminance(rgb):
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(first, second):
    dark, light = sorted((luminance(first), luminance(second)))
    return (light + 0.05) / (dark + 0.05)


class ChatStyleTest(unittest.TestCase):
    def test_all_conversation_and_admin_headers_use_shared_bubble(self):
        for relative in (
            "screens/channels/ChannelScreen.kt",
            "screens/chat/ChatDetailScreen.kt",
            "screens/groups/GroupChatScreen.kt",
            "screens/groups/GroupAdminScreen.kt",
        ):
            with self.subTest(screen=relative):
                self.assertIn("ApuHeaderBubble", source(relative))
                self.assertIn("TextOverflow.Ellipsis", source(relative))

    def test_no_stock_cards_remain_in_channel_or_chat_feeds(self):
        for relative in (
            "screens/channels/ChannelScreen.kt",
            "screens/chat/ChatDetailScreen.kt",
            "screens/groups/GroupChatScreen.kt",
        ):
            with self.subTest(screen=relative):
                text = source(relative)
                self.assertNotRegex(text, r"\bCard\(")
                self.assertIn("ApuBubbleCard", text)

    def test_channel_actions_use_available_material3_button_defaults(self):
        text = source("screens/channels/ChannelScreen.kt")
        self.assertNotIn("TextButtonDefaults", text)
        self.assertIn("import androidx.compose.material3.ButtonDefaults", text)
        self.assertEqual(text.count("ButtonDefaults.textButtonColors"), 2)

    def test_channel_header_uses_cached_avatar_and_keeps_admin_gate(self):
        text = source("screens/channels/ChannelScreen.kt")
        self.assertIn('rememberAvatar(avatars["g:${uiState.channelId}"])', text)
        self.assertRegex(text, r"onClick\s*=\s*if\s*\(uiState\.canPost\).*onOpenAdmin\(uiState\.channelId\).*else null")
        helper = source("components/ApuBubble.kt")
        self.assertIn("if (onClick != null)", helper)

    def test_transparent_stickers_have_no_fill_border_or_corner_crop(self):
        helper = source("components/ApuBubble.kt")
        self.assertIn("shape = if (transparent) RectangleShape else shape", helper)
        self.assertIn("border = if (transparent) null", helper)
        self.assertIn("containerColor = if (transparent) Color.Transparent", helper)
        self.assertIn("transparent = stickerFloat", source("screens/groups/GroupChatScreen.kt"))
        for relative, flag in (
            ("components/FileTransferBubble.kt", "stickerFloat"),
            ("components/GroupFileCard.kt", "stickerFloating"),
        ):
            self.assertRegex(source(relative), r"if \(" + flag + r"\) Modifier\s+else Modifier\.apuBubbleSurface")

    def test_ime_resize_and_column_pinned_layout_are_retained(self):
        for relative in ("screens/chat/ChatDetailScreen.kt", "screens/groups/GroupChatScreen.kt"):
            self.assertRegex(source(relative), r"Scaffold\(\s*modifier = Modifier\.imePadding\(\)")
        for relative in ("screens/chat/ChatDetailScreen.kt", "screens/channels/ChannelScreen.kt"):
            text = source(relative)
            self.assertIn("Column(modifier = Modifier.fillMaxSize())", text)
            self.assertIn("Закреплённые", text)

    def test_text_and_actions_contrast_on_any_wallpaper(self):
        helper = source("components/ApuBubble.kt")
        hints = source("components/HintBubble.kt")
        surface = color(helper, "ApuBubbleSurfaceColor")
        text = color(hints, "HintBubbleTextColor")
        muted = color(hints, "HintBubbleMutedColor")
        accent = color(helper, "ApuBubbleAccentColor")
        link = color(helper, "ApuBubbleLinkColor")
        for backdrop in ((0, 0, 0), (1, 1, 1)):
            background = blend(surface, backdrop, 0.92)
            for name, ink in (("text", text), ("muted", muted), ("accent", accent), ("link", link)):
                with self.subTest(ink=name, backdrop=backdrop):
                    self.assertGreaterEqual(contrast(ink, background), 4.5)

    def test_own_message_text_and_links_contrast_in_day_and_night(self):
        theme = source("theme/Theme.kt")
        link = color(source("components/ApuBubble.kt"), "ApuBubbleLinkColor")
        for background, text in (
            ((239 / 255, 201 / 255, 117 / 255), (58 / 255, 42 / 255, 5 / 255)),
            (color(theme, "Gold80"), color(theme, "GoldOnDark")),
        ):
            self.assertGreaterEqual(contrast(text, background), 4.5)
            self.assertGreaterEqual(contrast(link, background), 4.5)
        chat = source("screens/chat/ChatDetailScreen.kt")
        self.assertNotIn("linkColor = if (message.isFromMe) Color.White", chat)
        self.assertIn("linkColor = ApuBubbleLinkColor", chat)


if __name__ == "__main__":
    unittest.main(verbosity=2)
