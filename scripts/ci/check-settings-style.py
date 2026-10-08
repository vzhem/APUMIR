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


def hex_color(text, name):
    """Цвет по имени константы: Color(0xFFRRGGBB) → (r, g, b) в 0..1."""
    match = re.search(r"\b" + name + r"\s*:\s*Color\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", text)
    if match is None:
        match = re.search(r"\b" + name + r"\b[^\n]*?Color\(0xFF([0-9A-Fa-f]{6})\)", text)
    assert match is not None, "не найден цвет " + name
    value = int(match.group(1), 16)
    return tuple((value >> shift & 255) / 255 for shift in (16, 8, 0))


def luminance(rgb):
    def channel(value):
        return value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(part) for part in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(first, second):
    dark, light = sorted((luminance(first), luminance(second)))
    return (light + 0.05) / (dark + 0.05)


class SettingsStyleTest(unittest.TestCase):
    def test_premium_helpers_are_imported_where_used(self):
        """Kotlin не подтягивает импорты сам — и это уже стоило сборки.

        2026-10-07: в `GroupsScreen.kt` поставили `.apuPremiumLift(...)`, а импорт
        не добавили. Компиляция упала на «Unresolved reference 'apuPremiumLift'»,
        причём шаг компиляции в CI неблокирующий, поэтому прогон остался зелёным,
        а ошибку поймал только комментарий к PR. Проверяем все файлы ui/: если
        имя из премиального слоя используется, рядом обязан быть импорт (либо
        файл лежит в самом пакете `ui.components`).
        """
        import re
        ui = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
        names = (
            "apuPremiumLift",
            "apuPremiumGloss",
            "apuPremiumThread",
            "apuBubbleSurface",
            "apuGoldBrush",
            "ApuPremiumIconTile",
            "ApuPremiumButton",
            "ApuPremiumContentButton",
            "ApuPremiumFloatingActionButton",
            "ApuPremiumSwitch",
            "ApuPremiumCheckbox",
            "ApuPremiumRadioButton",
            "ApuPremiumSlider",
            "ApuGoldInk",
        )
        missing = []
        for path in sorted(ui.rglob("*.kt")):
            text = path.read_text()
            package = re.search(r"^package\s+([\w.]+)", text, re.M)
            in_components = bool(package) and package.group(1).endswith("ui.components")
            for name in names:
                if not re.search(r"\b" + name + r"\b", text):
                    continue
                if in_components:
                    continue
                if not re.search(r"^import\s+[\w.]*\." + name + r"\b", text, re.M):
                    missing.append(f"{path.relative_to(ui)}: {name}")
        self.assertEqual([], missing, "не импортированы: " + "; ".join(missing))

    def test_stock_action_and_selection_controls_use_apu_components(self):
        """No screen may quietly fall back to flat Material buttons or toggles."""
        pattern = re.compile(
            r"(?<![A-Za-z])(?:Button|OutlinedButton|FloatingActionButton|Switch|Checkbox|RadioButton|Slider)\s*\("
        )
        leftovers = []
        for path in sorted(UI.rglob("*.kt")):
            if path.name == "ApuPremiumControls.kt":
                # This shared wrapper uses Material Slider only for its gesture
                # engine; all colors and every call-site remain APU-controlled.
                continue
            text = path.read_text()
            text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
            text = re.sub(r"//[^\n]*", " ", text)
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                leftovers.append(f"{path.relative_to(UI)}:{line}")
        self.assertEqual([], leftovers, "остались стандартные controls: " + ", ".join(leftovers))

        controls = source("components/ApuPremiumControls.kt")
        # graphicsLayer is a lowercase extension, so the uppercase unresolved-name
        # scan cannot catch a wrong package import before Android compilation.
        self.assertIn("import androidx.compose.ui.graphics.graphicsLayer", controls)
        self.assertNotIn("import androidx.compose.ui.draw.graphicsLayer", controls)
        for marker in (
            "fun ApuPremiumFloatingActionButton(",
            "role = Role.Button",
            "fun ApuPremiumSwitch(",
            "role = Role.Switch",
            "fun ApuPremiumCheckbox(",
            "role = Role.Checkbox",
            "fun ApuPremiumRadioButton(",
            "role = Role.RadioButton",
            "fun ApuPremiumSlider(",
            "SliderDefaults.colors(",
            "ApuGoldDeep",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, controls)

    def test_premium_style_is_one_palette_for_the_whole_app(self):
        """2026-10-07: «переделать всё приложение, чтобы был стиль как в логах».

        Правило владельца выполняется буквально: у приложения ОДНА золотая
        палитра — та же, что в окне «Логи». Тест сравнивает значения констант
        в премиальном слое и в окне «Логи»: если кто-то поменяет золото в одном
        месте, разъехавшийся стиль поймается здесь, а не на телефоне.
        """
        premium = source("components/ApuPremium.kt")
        logs = source("components/ApuDiagnosticsUi.kt")
        pairs = (
            ("ApuGold", "DiagGold"),
            ("ApuGoldLight", "DiagGoldLight"),
            ("ApuGoldDeep", "DiagGoldDeep"),
            ("ApuGoldInk", "DiagOnGold"),
        )
        for shared_name, logs_name in pairs:
            with self.subTest(color=shared_name):
                self.assertEqual(
                    hex_color(premium, shared_name),
                    hex_color(logs, logs_name),
                    "золото премиального слоя и окна «Логи» должно совпадать",
                )

        # Общие компоненты обязаны пользоваться премиальным слоем, иначе стиль
        # вернётся к «блёклым квадратикам» — ровно на это была жалоба.
        settings = source("components/ApuSettingsUi.kt")
        for marker in (
            "apuGoldBrush()",
            "apuPremiumLift(",
            "apuPremiumGloss(",
            # Нить теперь рисует общая карточка (проверяется ниже по ApuBubble.kt):
            # переданная снаружи, она легла бы под фоном Card.
            "ApuPremiumIconTile(",
            "ApuGoldInk",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, settings)
        # Три «кита» стиля: карточка, секция, строка списка.
        #
        # Карточка настроек больше не навешивает слои снаружи через modifier:
        # слои, переданные в modifier, легли бы ПОД фоном Card и не были видны.
        # Теперь карточка просит премиальную поверхность у общей `ApuBubbleCard`
        # (premium = высота подъёма), а та рисует подъём, нить и блеск сама.
        self.assertIn("premium = if (highlighted) 10.dp else 7.dp", settings)
        self.assertIn("ApuPremiumIconTile(icon = icon)", settings)
        shared_card = source("components/ApuBubble.kt")
        for marker in (
            "premium: Dp? = null",
            ".apuPremiumLift(premium, shape)",
            ".apuPremiumThread(shape = shape)",
            ".apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.55f)",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, shared_card)

        # Батарея: в списках настроек не должно быть вечно бегущих анимаций
        # (правило PROFILE_SETTINGS_STYLE.md — без непрерывного shimmer).
        self.assertNotIn("rememberInfiniteTransition", settings)
        self.assertNotIn("rememberInfiniteTransition", premium)

        # Читаемость: тёмные чернила обязаны читаться на каждом стопе золота.
        ink = hex_color(premium, "ApuGoldInk")
        for name in ("ApuGoldLight", "ApuGold", "ApuGoldDeep"):
            with self.subTest(ink_on=name):
                self.assertGreaterEqual(contrast(ink, hex_color(premium, name)), 4.5)

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
        # Владелец 2026-10-07: «Доделывай все разделы с новым стилем».
        # Общий диалог разделов — уже НЕ stock AlertDialog, а собственный
        # премиальный слой: своя рамка, золотая капсула заголовка, блеск.
        self.assertIn("Dialog(onDismissRequest = onDismissRequest", shared)
        self.assertIn("apuPremiumLift(14.dp, shape, ApuPremiumShadowColor)", shared)
        self.assertIn("apuGoldBrush()", shared)
        self.assertNotRegex(shared, r"\bAlertDialog\(")

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
        # Разделитель — золотая нить APU. Отступ задаётся параметром: в
        # настройках 72dp (под иконкой строки), в карточках разделов 16dp.
        self.assertIn("startPadding: Dp = 72.dp", shared)
        self.assertIn("padding(start = startPadding, end = 16.dp)", shared)
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

    def test_rank_screen_uses_house_rows_and_keeps_promo_field_above_keyboard(self):
        """Ранги: фирменные строки вместо маркеров и поле промокода над клавиатурой.

        Задача владельца 2026-10-06: экран «Ранги и возможности» - в фирменном
        стиле, а поле промокода при вводе не должно уезжать под клавиатуру.
        Проверяем ровно это: stock Material-поле заменено фирменным, возможности
        рисуются общей строкой, а список сжимается клавиатурой и подводит поле.
        """
        text = source("screens/settings/RankBenefitsScreen.kt")
        self.assertNotRegex(text, r"\bOutlinedTextField\(")
        for house_part in (
            "ApuFormTextField(",
            "ApuSettingsFeatureRow(",
            "ApuSettingsChip(",
            "ApuSettingsProgress(",
            ".imePadding()",
            "bringIntoViewRequester",
            "onFocusChanged",
        ):
            self.assertIn(house_part, text)
        # Возможности больше не рисуются текстовыми маркерами «•».
        self.assertNotIn('"• ', text)
        # Пойманное на телефоне 2026-10-06 (v11.74.187): поле было видно, а кнопка
        # «Применить» оставалась под клавиатурой. Подводим не поле, а весь блок
        # «поле + кнопка», и делаем две попытки: клавиатура поднимается анимацией
        # около трети секунды, поэтому одного вызова мало.
        self.assertIn("bringIntoViewRequester(promoBlock)", text)
        promo_block = text.index("bringIntoViewRequester(promoBlock)")
        self.assertIn("ApuFormTextField(", text[promo_block:])
        self.assertIn('Text("Применить")', text[promo_block:])
        self.assertGreaterEqual(text.count("promoBlock.bringIntoView()"), 2)
        shared = source("components/ApuSettingsUi.kt")
        for house_part in (
            "fun ApuSettingsFeatureRow(",
            "fun ApuSettingsChip(",
            "fun ApuSettingsProgress(",
            "Icons.Default.Lock",
            # 2026-10-07: полоска прогресса стала золотой с глянцем (премиальный
            # стиль «как в логах»): трек — спокойный акцент, заполнение —
            # золотая кисть apuGoldBrush() с бликом. Прежний маркер
            # trackColor=…LinearProgressIndicator относился к material-полоске.
            ".background(ApuBubbleAccentColor.copy(alpha = 0.14f))",
            ".background(apuGoldBrush())",
        ):
            self.assertIn(house_part, shared)

    def test_rank_screen_splits_vip_and_marks_the_elite(self):
        """VIP-ранги: с десятого («Проводник») - элита (решение владельца 2026-10-07).

        Проверяем, что список рангов разделён на обычные и VIP, что VIP-ступени
        помечены знаком, а порог живёт в политике рангов (по нему же решают
        медаль и знак у имени), а не размазан по экрану числами.
        """
        text = source("screens/settings/RankBenefitsScreen.kt")
        self.assertIn("ApuSettingsSectionTitle(\"Ранги\")", text)
        self.assertIn('ApuSettingsSectionTitle("VIP — элита APU")', text)
        self.assertIn("FileTransferRankPolicy.regularTiers", text)
        self.assertIn("FileTransferRankPolicy.vipTiers", text)
        self.assertIn("ApuVipBadge(", text)
        self.assertIn("current.isVip", text)
        self.assertIn("FileTransferRankPolicy.referralsToVip(qualified)", text)

        policy = (ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferRankPolicy.kt").read_text()
        self.assertIn("const val VIP_MINIMUM_QUALIFIED_REFERRALS = 10", policy)
        self.assertIn("val isVip: Boolean", policy)
        self.assertIn("val regularTiers: List<Entitlement>", policy)
        self.assertIn("val vipTiers: List<Entitlement>", policy)
        # VIP - ровно с десятого ранга («Проводник»), а не любой второй ранг.
        self.assertIn("minimumQualifiedReferrals >= VIP_MINIMUM_QUALIFIED_REFERRALS", policy)

        # Знак VIP один на всё приложение: собственная плашка с градиентом и
        # звездой, а не текст «VIP» вразнобой по экранам.
        badge = source("components/ApuVipBadge.kt")
        self.assertIn("fun ApuVipBadge(", badge)
        self.assertIn("Icons.Default.Star", badge)
        self.assertIn("Brush.linearGradient(", badge)

        # Медаль VIP отличается от обычной, но остаётся той же наградой.
        medal = source("components/RankMedal.kt")
        self.assertIn("vip: Boolean = false", medal)
        self.assertIn("drawVipAura(", medal)

        # Рядом с собственным званием знак виден и на главном экране.
        chat_list = source("screens/chat/ChatListScreen.kt")
        self.assertIn("RankMedal(size = 26.dp, vip = uiState.rankVip)", chat_list)
        self.assertIn("ApuVipBadge(compact = true)", chat_list)

    def test_large_profile_initials_and_avatar_io_are_preserved(self):
        avatar = source("components/MyAvatar.kt")
        self.assertIn("size: Int = 52", avatar)
        # Инициалы остаются запасным вариантом, когда картинки нет: их рисует
        # тот же компонент, что и раньше, - просто круг вписан в размер экрана.
        self.assertIn("Avatar(name = displayName, modifier = Modifier.fillMaxSize(), size = size)", avatar)
        self.assertIn("AvatarBitmaps.loadUri(context, avatarUri)", avatar)
        self.assertIn("AvatarBitmaps.cachedUri(avatarUri)", avatar)
        # Знак элиты виден и на своём аватаре: кольцо то же, что у собеседников
        # (владелец 2026-10-07: «чтобы аккаунт VIP должно быть видно везде»).
        self.assertIn("VipRing(Modifier.matchParentSize())", avatar)
        self.assertIn("rememberSelfVip()", avatar)
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
