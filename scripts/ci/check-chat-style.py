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

    def test_channel_actions_use_the_house_action_button(self):
        """Действия канала — фирменные кнопки, а не стоковый TextButton.

        2026-10-07: владелец попросил доделать новой стиль во всех разделах,
        поэтому внутри карточек и диалогов тоже стоят наши объёмные кнопки
        (ApuTextAction) — плоских Material-кнопок в разделах больше нет.
        """
        text = source("screens/channels/ChannelScreen.kt")
        self.assertIn("ApuTextAction(", text)
        self.assertNotIn("TextButton(", text)
        self.assertNotIn("TextButtonDefaults", text)

    def test_no_stock_dialogs_or_text_fields_left_in_the_ui(self):
        """Диалоги и поля ввода — тоже фирменные.

        Проверка по всему интерфейсу сразу: стоковый AlertDialog и
        OutlinedTextField на месте вызова выглядят чужеродно на подложке APU
        (плоские углы, серые подписи, чужая подсветка фокуса). Разрешено ровно
        одно место — сам компонент поля, внутри которого стоит Material-поле с
        нашими цветами.
        """
        root = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
        offenders = []
        for path in sorted(root.rglob("*.kt")):
            text = path.read_text()
            if re.search(r"\bAlertDialog\(", text):
                offenders.append("AlertDialog: " + str(path.relative_to(ROOT)))
            if re.search(r"\bOutlinedTextField\(", text) and path.name != "ApuBubbleField.kt":
                offenders.append("OutlinedTextField: " + str(path.relative_to(ROOT)))
        self.assertEqual([], offenders)

    def test_new_style_components_are_actually_used(self):
        """Новые общие компоненты стиля обязаны быть в деле, а не лежать рядом.

        Владелец 2026-10-07: «Доделывай все разделы с новым стилем». Проверка
        страхует от откатов: если кто-то снова начнёт рисовать кнопку, поле или
        диалог «своими руками», стиль снова разъедется по разделам.
        """
        root = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
        used = {"ApuTextAction": 0, "ApuBubbleField": 0, "ApuSettingsDialog": 0}
        for path in root.rglob("*.kt"):
            text = path.read_text()
            if path.stem == "ApuTextAction":
                continue
            for name in used:
                if name + "(" in text:
                    used[name] += 1
        for name, files in used.items():
            with self.subTest(component=name):
                self.assertGreaterEqual(files, 5, name + " почти нигде не используется")
        # Звонок — золотое кольцо и кнопки в общем слое.
        call = source("screens/call/CallScreen.kt")
        self.assertIn("apuGoldBrush()", call)
        self.assertIn("apuPremiumGloss(CircleShape", call)
        # Левая колонка группового чата и панель ввода — премиальные.
        group_chat = source("screens/groups/GroupChatScreen.kt")
        self.assertGreaterEqual(group_chat.count(".apuPremiumThread("), 3)

    def test_no_stock_text_buttons_left_in_the_ui(self):
        """Во всём интерфейсе не осталось плоских Material-кнопок.

        Проверка по всем разделам сразу: один пропущенный TextButton — уже
        чужая кнопка на фирменной подложке. Ловим и класс, и его «чернила».
        """
        root = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
        offenders = []
        for path in sorted(root.rglob("*.kt")):
            text = path.read_text()
            if "TextButton(" in text or "ButtonDefaults.textButtonColors" in text:
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders)

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

    def test_key_desync_notice_tells_the_person_what_to_do(self):
        """«От него не открывается» — плашка в переписке, а не только в «Логах».

        Владелец 2026-10-07 прислал отчёт, где 12 входящих не вскрылось:
        собеседник запечатал для прежней копии нашего ключа. Понять это можно
        было только в отчёте, а человек видел «сообщения не приходят». Теперь у
        такой переписки висит золотая плашка с объяснением и кнопкой.
        """
        notice = (ROOT / "android-app/app/src/main/java/com/vladimir/messenger/"
                  "data/security/KeyDesyncNotice.kt").read_text()
        for marker in (
            "object KeyDesyncNotice",
            "fun note(context: Context, nodeId: String",
            "fun isPending(context: Context, nodeId: String",
            "fun isWorkingRecently(",
            "fun opened(context: Context, nodeId: String",
            "fun clear(context: Context, nodeId: String",
            "TTL_MS",
            # Владелец 2026-10-07: после обмена QR переписка пошла, а плашка
            # вернулась — её зажигала одна застрявшая в пути старая копия.
            "MIN_FAILS",
            "WORKING_QUIET_MS",
            "LEFTOVER_MS",
        ):
            with self.subTest(marker=marker):
                self.assertTrue(marker in notice, f"нет маркера {marker} в KeyDesyncNotice.kt")

        service = (ROOT / "android-app/app/src/main/java/com/vladimir/messenger/"
                   "service/CoreServerService.kt").read_text()
        # Пометка ставится, только когда ключ собеседника нам известен: иначе
        # «не вскрылось» — это чужой конверт, и пугать человека нечем.
        self.assertTrue(
            "KeyDesyncNotice\n                                .note(applicationContext, senderId)"
            in service,
            "служба ядра не отмечает рассинхрон ключа",
        )
        self.assertTrue(
            "fileTransferRouter.requestExchangeBinding(senderId)" in service,
            "собеседнику не отправляется наш ключ ещё раз",
        )
        self.assertTrue(
            "KeyDesyncNotice\n                        .opened(applicationContext, senderId)" in service,
            "пометка не снимается, когда переписка снова открывается",
        )
        # Старая копия в пути не должна ни пугать человека, ни гонять сигнал
        # собеседнику: ключ доказанно рабочий.
        self.assertIn("staleKeyCopy = leftover", service)
        self.assertIn("if (known && !leftover)", service)
        self.assertIn("leftover=$leftover", service)

        view_model = source("screens/chat/ChatDetailViewModel.kt")
        self.assertTrue("val keyDesync: Boolean = false" in view_model, "нет поля в состоянии чата")
        self.assertTrue("observeKeyDesync(chat.contactId)" in view_model, "нет наблюдения за пометкой")
        self.assertTrue("fun onKeyDesyncAction()" in view_model, "нет действия «отдать ключ»")

        screen = source("screens/chat/ChatDetailScreen.kt")
        self.assertTrue("keyDesync    = uiState.keyDesync" in screen, "плашка не подключена к экрану")
        self.assertTrue("onKeyDesyncAction = viewModel::onKeyDesyncAction" in screen, "кнопка не подключена")
        # Плашка — в премиальном стиле «Логов»: золото, нить, блеск ПОД текстом,
        # тёмные чернила (контраст проверяет test_premium_palette_keeps_contrast
        # в контракте отчёта).
        self.assertTrue(".background(apuGoldBrush(), noticeShape)" in screen, "нет золотой подложки")
        self.assertTrue("apuPremiumThread(shape = noticeShape, inset = 18.dp)" in screen, "нет золотой нити")
        self.assertTrue("apuPremiumGloss(noticeShape, intensity = 0.8f" in screen, "нет блеска")
        self.assertTrue("color = ApuGoldInk," in screen, "текст плашки не тёмными чернилами")
        self.assertTrue("Сообщения от собеседника не открываются" in screen, "нет объяснения словами")
        self.assertTrue("Отправить мой ключ ещё раз" in screen, "нет кнопки действия")

    def test_list_rows_and_search_use_the_premium_surface(self):
        """Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».

        Строки списка чатов и строка поиска должны быть той же поверхностью, что
        шапки, пузыри и диалоги: подъём, единая подложка house style, золотая нить
        по верхней кромке и блеск ПОД содержимым. Старая плоская заливка
        (Color(0xFFF5F7FA) вручную + border) в этих строках не остаётся: она
        выглядела как чужая программа рядом с премиальными шапками.
        """
        card = source("components/ContactCard.kt")
        for marker in (
            ".apuPremiumLift(",
            ".apuBubbleSurface()",
            ".apuPremiumThread(",
            ".apuPremiumGloss(",
        ):
            self.assertIn(marker, card, "карточка списка чатов без премиального слоя: " + marker)

        chat_list = source("screens/chat/ChatListScreen.kt")
        for marker in (
            "import com.vladimir.messenger.ui.components.apuPremiumLift",
            "import com.vladimir.messenger.ui.components.apuPremiumThread",
            "import com.vladimir.messenger.ui.components.apuPremiumGloss",
        ):
            self.assertIn(marker, chat_list, "импорт не подтянется сам: " + marker)
        # Три поверхности этого экрана: избранное, строка группы и поиск.
        self.assertGreaterEqual(chat_list.count(".apuBubbleSurface()"), 2)
        self.assertGreaterEqual(chat_list.count(".apuPremiumLift("), 3)
        self.assertGreaterEqual(chat_list.count(".apuPremiumThread("), 3)
        # Приглушённый серый 8A93A2 давал контраст ниже 4.5 на подложке house
        # style — заменён на читаемый HintBubbleMutedColor (5A6472).
        self.assertNotIn("Color(0xFF8A93A2)", chat_list)

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
