#!/usr/bin/env python3
"""Фирменный пузырь всегда светлый — чернила внутри него не зависят от темы.

Владелец 2026-11-18 (тёмная тема, «Участники» группы): имена VIP читались, а у
обычных участников вместо имени была пустота. Причина не в списке, а в
подложке: `ApuBubble*`/`HintBubble` рисуют светло-слоновую поверхность в любой
теме, поэтому цвет текста, взятый из темы (`onSurface`, `onBackground`), ночью
оказывается почти белым на белом.

Правило: функция, которая рисует светлый пузырь, не берёт чернила из
`MaterialTheme.colorScheme.on*`. Внутри пузыря уже подставлены `ApuBubbleTextColor`
(через `LocalContentColor`) и `ApuBubbleMutedColor`; цвет, выбранный
пользователем, накладывается сверху через `TextColorPalette.colorOr(...)` и от
темы тоже не зависит.

Исключения (`ALLOWED`): функции, где текст лежит не на пузыре, а прямо на
обоях — парящие стикеры с `transparent = true`. На них подложки нет, поэтому
цвет обязан следовать теме. Каждое исключение обязано оставаться обоснованным:
тест падает и на пропавшей функции, и на функции, где больше нет `transparent`.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"

# Светлая подложка, которая НЕ подменяет палитру темы.
BUBBLE_RENDERERS = (
    "ApuBubble(",
    "ApuBubbleCard(",
    "ApuHeaderBubble(",
    "ApuHeaderIconBubble(",
    "HintBubble(",
)
# Эти контейнеры подменяют `colorScheme` на светлую палитру (`ApuSettingsPalette`),
# поэтому чернила темы внутри них — правильный путь.
SAFE_CONTAINERS = ("ApuSettingsCard(", "ApuSettingsDialog(")

THEME_INK = re.compile(r"colorScheme\.(onSurface|onBackground)(Variant)?\b")
INK_ASSIGNMENT = re.compile(
    r"(\bcolor\s*=|\bcontentColor\s*=|\btint\s*="
    r"|\bval\s+\w*(Name|Text|Ink|Label|Title|Body|Hint|Muted|Primary|Secondary)\w*\s*=)"
)
FUNCTIONS = re.compile(
    r"^(?:@\w+(?:\([^()]*\))?\s*)*\s*(?:private |internal |public )?fun\s+(\w+)",
    re.MULTILINE,
)

# Файл::функция → почему цвет из темы здесь правильный.
ALLOWED = {
    "screens/groups/GroupChatScreen.kt::MessageBubble":
        "парящий стикер рисуется без подложки (transparent), текст на обоях",
}


def strip_comments(text):
    """Заменить комментарии пробелами, сохранив разбиение на строки."""
    text = re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", lambda m: " " * len(m.group(0)), text)


def functions_of(text):
    """[(имя, тело)] — от объявления `fun` до следующего объявления."""
    marks = [(m.start(), m.group(1)) for m in FUNCTIONS.finditer(text)]
    out = []
    for index, (start, name) in enumerate(marks):
        end = marks[index + 1][0] if index + 1 < len(marks) else len(text)
        out.append((name, text[start:end]))
    return out


def theme_ink_lines(body):
    return [line.strip() for line in body.splitlines()
            if THEME_INK.search(line) and INK_ASSIGNMENT.search(line)]


class BubbleInkTest(unittest.TestCase):
    def test_light_bubble_keeps_its_own_ink(self):
        """Имя/подпись внутри светлого пузыря не берут цвет из темы.

        2026-11-18: `GroupAdminMembersTab.kt` красил имя участника в
        `MaterialTheme.colorScheme.onSurface`, а строка лежит в `ApuBubble`: в
        тёмной теме имя исчезало (VIP спасал золотой градиент). Та же строка
        была в диалоге участников и в плашке «идентичность недоступна».
        """
        offenders = []
        for path in sorted(UI.rglob("*.kt")):
            rel = path.relative_to(UI).as_posix()
            for name, body in functions_of(strip_comments(path.read_text(encoding="utf-8"))):
                if not any(marker in body for marker in BUBBLE_RENDERERS):
                    continue
                if any(marker in body for marker in SAFE_CONTAINERS):
                    continue
                if f"{rel}::{name}" in ALLOWED:
                    continue
                for line in theme_ink_lines(body):
                    offenders.append(f"{rel}::{name}: {line}")
        self.assertEqual(
            offenders,
            [],
            "текст внутри светлого пузыря берёт цвет из темы (в тёмной теме он станет невидимым): "
            + " | ".join(offenders),
        )

    def test_exemptions_are_still_justified(self):
        """Исключение живёт, только пока функция действительно без подложки."""
        sources = {path.relative_to(UI).as_posix(): strip_comments(path.read_text(encoding="utf-8"))
                   for path in UI.rglob("*.kt")}
        for key in ALLOWED:
            rel, name = key.split("::")
            self.assertIn(rel, sources, f"{key}: файла больше нет — исключение устарело")
            bodies = dict(functions_of(sources[rel]))
            self.assertIn(name, bodies, f"{key}: функции больше нет — исключение устарело")
            self.assertIn(
                "transparent",
                bodies[name],
                f"{key}: пузырь больше не прозрачный — исключение больше не нужно",
            )

    def test_group_member_name_colors_are_theme_independent(self):
        """Имя участника в списках группы читается в обеих темах.

        Ровно те два списка, где владелец видел пропажу имени: вкладка
        «Участники» админки и диалог участников. Оба стоят на светлом пузыре,
        поэтому по умолчанию обязан быть цвет пузыря, а не ночной темы.
        """
        default = re.compile(r"defaultMemberNameColor\s*=\s*([^\n]+)")
        for rel in ("screens/groups/GroupAdminMembersTab.kt",
                    "screens/groups/GroupMembersDialog.kt"):
            text = strip_comments((UI / rel).read_text(encoding="utf-8"))
            match = default.search(text)
            self.assertIsNotNone(match, f"{rel}: не найден defaultMemberNameColor")
            expression = match.group(1)
            self.assertNotIn("colorScheme", expression,
                             f"{rel}: цвет имени по умолчанию взят из темы: {expression.strip()}")
            self.assertIn("ApuBubbleTextColor", expression,
                          f"{rel}: имя должно краситьcя чернилами пузыря: {expression.strip()}")

    def test_vip_name_stays_readable_on_light_bubble(self):
        """VIP-имя не должно сломаться при починке обычных имён.

        Бронзовый `Color(0xFF8A5F00)` и градиент `apuVipNameStyle` — единственные
        цвета, которые в тёмной теме читались; их трогать нельзя.
        """
        members = (UI / "screens/groups/GroupAdminMembersTab.kt").read_text(encoding="utf-8")
        self.assertIn("Color(0xFF8A5F00)", members)
        self.assertIn("apuVipNameStyle(", members)
        badge = (UI / "components/ApuVipBadge.kt").read_text(encoding="utf-8")
        self.assertIn("ApuVipNameBrush", badge)

    def test_bubble_ink_constant_is_fixed_and_dark(self):
        """`ApuBubbleTextColor` — тёмные чернила, общие для дня и ночи."""
        bubble = (UI / "components/ApuBubble.kt").read_text(encoding="utf-8")
        self.assertIn("val ApuBubbleTextColor: Color = HintBubbleTextColor", bubble)
        self.assertIn("val ApuBubbleMutedColor: Color = HintBubbleMutedColor", bubble)
        hint = (UI / "components/HintBubble.kt").read_text(encoding="utf-8")
        match = re.search(r"val HintBubbleTextColor: Color = Color\(0xFF([0-9A-Fa-f]{6})\)", hint)
        self.assertIsNotNone(match, "HintBubbleTextColor должен быть явным цветом, а не темой")
        value = int(match.group(1), 16)
        luma = (0.2126 * ((value >> 16) & 255) + 0.7152 * ((value >> 8) & 255)
                + 0.0722 * (value & 255)) / 255
        self.assertLess(luma, 0.35, "чернила пузыря обязаны быть тёмными на светлой подложке")


if __name__ == "__main__":
    unittest.main(verbosity=2)
