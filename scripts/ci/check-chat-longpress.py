#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Контракт «удержание строки = меню» и пузырей в «Избранном» (владелец 2026-11-18).

Задачи владельца (скриншоты, сборка v11.74.541):

* «Нужно когда удерживаешь на чате или группе или канале то высвечивалось бы
  меню». На скриншоте «Чаты» у строк есть «⋮» — значит меню уже существует,
  но открывается ТОЛЬКО кнопкой. Удержание на самой строке ничего не делало.
* «В избранном есть текст без пузырей». На скриншоте «Избранного» две записи
  со стикерами (`Стикеры` / `стикер_*.webp` / размер / время / действия) лежат
  прямо на обоях, а запись с гифкой — в белом пузыре.

Проверяется по исходникам (экрана в песочнице не собрать):

* жест один на все списки: `apuRowPress` в общем `BubbleMenu.kt` — тап,
  удержание с тактильным откликом и правило «нет действий — нет удержания»;
* им пользуются строка личного чата/контакта, строка группы и канала главного
  экрана и строка «Сообществ»; в этих строках не осталось голого `.clickable`;
* у меню одно состояние на строку: кнопка «⋮» и удержание открывают ОДИН
  список, а не два независимых (иначе меню открывалось бы двойным якорем);
* меню по удержанию есть только там, где есть пункты: карточка «Избранного» в
  списке чатов (без меню) удерживается как обычный клик;
* в «Избранном» ни одна запись не просит прозрачный пузырь: подпись, размер,
  время и действия обязаны лежать на подложке house style;
* парящий стикер в переписке остаётся прозрачным — это отдельный, уже
  проверенный путь (`check-chat-style.py`).

Компиляцию Kotlin и вид на телефоне проверяют `check-android-compile.sh`, CI и
владелец; здесь — только то, что можно проверить без Android SDK.

Запуск: `python3 scripts/ci/check-chat-longpress.py`; код возврата 0 — контракт цел.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"

MENU = UI / "components/BubbleMenu.kt"
CARD = UI / "components/ContactCard.kt"
CHAT_LIST = UI / "screens/chat/ChatListScreen.kt"
GROUPS = UI / "screens/groups/GroupsScreen.kt"
GROUPS_MENU = UI / "screens/groups/GroupMenuActions.kt"
SAVED = UI / "screens/saved/SavedScreen.kt"
GROUP_CHAT = UI / "screens/groups/GroupChatScreen.kt"
BASELINE = ROOT / "scripts/ci/god-files-baseline.json"
CORE_CHECK = ROOT / "scripts/ci/ci-core-check.sh"


def source(path):
    return path.read_text(encoding="utf-8")


def row_block(text, start_marker):
    """Кусок файла от маркера строки до следующего объявления функции."""
    start = text.index(start_marker)
    end = re.search(r"\n(/\*\*|@Composable|private fun |internal fun |fun )", text[start + len(start_marker):])
    return text[start:start + len(start_marker) + (end.start() if end else len(text))]


class SharedGestureTest(unittest.TestCase):
    """Жест живёт в одном месте: строки не обзаводятся своими копиями."""

    def test_the_modifier_is_shared(self):
        menu = source(MENU)
        self.assertIn("fun Modifier.apuRowPress(", menu)
        self.assertIn("import androidx.compose.foundation.combinedClickable", menu)
        self.assertIn("HapticFeedbackType.LongPress", menu)
        self.assertIn("LocalHapticFeedback.current", menu)
        # Пустой список действий — обычный клик: удержание без меню выглядело
        # бы сломанным (карточка «Избранного», каталог сети).
        self.assertIn("if (menuActions.isEmpty()) return this.clickable(onClick = onClick)", menu)
        self.assertIn("onLongClick = {", menu)

    def test_the_menu_has_one_state_per_row(self):
        menu = source(MENU)
        self.assertIn("onExpandedChange: (Boolean) -> Unit = {}", menu)
        self.assertIn("expanded = expanded,", menu)
        self.assertIn("onDismissRequest = { onExpandedChange(false) },", menu)
        self.assertIn("onClick = { onExpandedChange(true) },", menu)
        # Внутри кнопки больше нет своего mutableStateOf: иначе у строки было
        # бы два меню и два якоря.
        body = menu[menu.index("fun BubbleOverflowMenu("):menu.index("fun Modifier.apuRowPress(")]
        self.assertNotIn("mutableStateOf(", body)

    def test_rows_use_the_shared_gesture(self):
        for name, path, marker in (
            ("личный чат / контакт", CARD, "fun ContactCard("),
            ("группа и канал главного экрана", CHAT_LIST, "private fun GroupCard("),
            ("группа и канал «Сообществ»", GROUPS, "private fun GroupRow("),
        ):
            with self.subTest(row=name):
                block = row_block(source(path), marker)
                self.assertIn(".apuRowPress(", block, name + ": нет удержания строки")
                self.assertIn("onMenuRequest = { showMenu = true },", block, name + ": удержание не открывает меню")
                self.assertNotIn(".clickable(onClick = onClick)", block, name + ": остался голимый клик")
                self.assertRegex(block, r"var showMenu by remember \{ mutableStateOf\(false\) \}", )
                self.assertIn("expanded = showMenu,", block, name + ": «⋮» не связано с жестом")
                self.assertIn("onExpandedChange = { showMenu = it },", block, name + ": состояние меню не общее")

    def test_saved_card_still_opens_on_tap(self):
        """У «Избранного» нет пунктов меню — и удержание не должно мешать тапу."""
        block = row_block(source(CHAT_LIST), "private fun SavedContactCard(")
        self.assertNotIn(".apuRowPress(", block)
        self.assertIn(".clickable(onClick = onClick)", block)

    def test_group_menu_actions_live_next_to_the_row(self):
        """Пункты меню — отдельным файлом: экран на потолке god-файлов."""
        actions = source(GROUPS_MENU)
        for marker in (
            "internal fun groupMenuActions(",
            "R.string.menu_mark_read",
            "R.string.menu_manage",
            "GroupRole.isAdminOrOwner(group.myRole)",
            "destructive = true",
        ):
            self.assertIn(marker, actions)
        screen = source(GROUPS)
        self.assertNotIn("private fun groupMenuActions(", screen)
        self.assertIn("groupMenuActions(", screen)
        # Потолки строк не поднимались.
        ceilings = {
            line.split('"')[1]: int(line.rsplit(" ", 1)[1].rstrip(","))
            for line in source(BASELINE).splitlines()
            if line.strip().startswith('"android-app/')
        }
        for path in (CHAT_LIST, GROUPS):
            name = path.relative_to(ROOT).as_posix()
            actual = len(source(path).splitlines())
            with self.subTest(file=name):
                self.assertLessEqual(actual, ceilings[name], name + " вырос выше потолка строк")

    def test_the_contract_runs_in_ci(self):
        self.assertIn("python3 scripts/ci/check-chat-longpress.py", source(CORE_CHECK))


class SavedBubblesTest(unittest.TestCase):
    """«В избранном есть текст без пузырей» — пузырь у каждой записи."""

    def test_no_saved_item_is_transparent(self):
        text = source(SAVED)
        self.assertNotIn("transparent = isStickerItem", text)
        bubble = text[text.index("private fun SavedItemBubble("):]
        bubble = bubble[:bubble.index("\n}\n")]
        self.assertNotIn("transparent =", bubble)
        # Запись остаётся в общем пузыре house style, с отступами и рамкой.
        self.assertIn("ApuBubble(", bubble)

    def test_stickers_still_float_and_animate(self):
        """Прозрачность убрана только у подложки: сам стикер живёт как раньше."""
        text = source(SAVED)
        self.assertIn("val isLiveItem = isGifItem || isStickerItem", text)
        self.assertIn("StickerAnimated(", text)
        self.assertIn("StickerViewer(", text)
        # Парящий стикер в переписке — отдельный путь, его не трогали.
        self.assertIn("transparent = stickerFloat,", source(GROUP_CHAT))


if __name__ == "__main__":
    unittest.main(verbosity=2)
