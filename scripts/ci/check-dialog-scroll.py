#!/usr/bin/env python3
"""Прокрутка внутри прокрутки роняет Compose — этого не должно быть в диалогах.

Что случилось. Владелец 2026-11-19: в группе нажал «Удалить и модерация…» —
приложение упало. В отчёте падения:

    java.lang.IllegalStateException: Vertically scrollable component was
    measured with an infinity maximum height constraints, which is disallowed.

Причина. `ApuSettingsDialog` сам ограничивает высоту карточки
(`heightIn(max = …)`) и прокручивает её целиком — заголовок, тело и кнопки.
`ApuMessageModerationDialog` дополнительно навешивал `verticalScroll` на тело.
Вложенный прокручиваемый узел измеряется с бесконечной максимальной высотой,
и Compose честно падает: одну ось прокручивают два узла.

Тот же дефект уже чинили дважды — в `GroupsScreen` и `ProfileSyncDialog`; оба
раза остались только комментарии «так нельзя». Третьего раза быть не должно,
поэтому правило проверяется:

  внутри содержимого `ApuSettingsDialog` (`text = { … }` и соседних слотов)
  не должно быть `verticalScroll`/`LazyColumn`/`LazyVerticalGrid`, если
  рядом нет `heightIn(max = …)` — именно оно даёт конечную высоту.

Как проверяется. Файл разбирается по строкам: находим вызовы
`ApuSettingsDialog(`, затем — `verticalScroll(`/`LazyColumn(`/`LazyVerticalGrid(`
в пределах функции, где встретился диалог. Для каждого такого места
заглядываем на несколько строк выше в поисках `heightIn`. Исключение —
диалог, который содержимое не ограничивает и не прокручивает (`ApuPremiumDialog`
прокрутку не навязывает: там `Column` с `weight`, без `verticalScroll`).

Запуск:
    python3 scripts/ci/check-dialog-scroll.py
Код возврата: 0 — чисто, 1 — найдено вложение, 2 — ошибка вызова.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"

# Диалог, который уже прокручивает свою карточку: внутри него прокрутка запрещена.
SCROLLING_DIALOG = "ApuSettingsDialog("

# Прокручиваемые контейнеры, которым нужна конечная высота.
SCROLLABLE = re.compile(r"\b(verticalScroll\s*\(|LazyColumn\s*\(|LazyVerticalGrid\s*\()")

# Конечная высота задана выражением: heightIn(max = …), height(320.dp).
BOUNDED = re.compile(r"\b(heightIn|\.height)\s*\([^)]*\d")

# Конечная высота задана ПЕРЕМЕННОЙ: height(listHeight). Такая переменная
# объявляется в том же файле — её и ищем (см. height_variables).
BOUNDED_VAR = re.compile(r"(?:heightIn|\.height)\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,)]")

# Объявление переменной высоты: `val listHeight = (… ).dp`.
HEIGHT_VAR = re.compile(r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)[^=]*=\s*(.+)")

# Сколько строк выше смотреть, прежде чем решить «ограничения нет».
LOOKBACK = 8

# Сколько строк ниже смотреть. Высота часто стоит отдельной строкой в цепочке
# модификаторов уже ПОСЛЕ вызова: `LazyColumn(` → `modifier = Modifier` →
# `.heightIn(max = …)`. Именно такие случаи сначала дали ложные срабатывания.
LOOKAHEAD = 10


def kotlin_files():
    yield from sorted(UI.rglob("*.kt"))


def dialog_ranges(lines: list[str]) -> list[tuple[int, int]]:
    """Диапазоны (начало, конец) вокруг каждого вызова SCROLLING_DIALOG.

    Границы — от строки вызова до конца сбалансированных скобок вызова.
    Если скобки не сходятся (необычная разметка), берём окно в 400 строк:
    лучше лишний раз проверить, чем пропустить падение.
    """
    ranges = []
    for index, line in enumerate(lines):
        if SCROLLING_DIALOG not in line:
            continue
        depth = 0
        opened = False
        end = min(index + 400, len(lines))
        for cursor in range(index, end):
            depth += lines[cursor].count("(") - lines[cursor].count(")")
            if "(" in lines[cursor]:
                opened = True
            if opened and depth <= 0:
                end = cursor + 1
                break
        ranges.append((index, end))
    return ranges


def height_variables(lines: list[str]) -> set[str]:
    """Имена val-переменных, значение которых — размер в dp.

    Нужны для `LazyColumn(Modifier.height(listHeight))`: имя переменной само
    по себе не доказывает конечность, поэтому проверяем её объявление.
    """
    names = set()
    for line in lines:
        match = HEIGHT_VAR.search(line)
        if match and (".dp" in match.group(2) or "heightIn" in match.group(2)):
            names.add(match.group(1))
    return names


def find_violations(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    heights = height_variables(lines)
    ranges = dialog_ranges(lines)
    if not ranges:
        return []
    problems = []
    seen = set()
    for start, end in ranges:
        for cursor in range(start, end):
            match = SCROLLABLE.search(lines[cursor])
            if not match:
                continue
            if cursor in seen:
                continue
            # Конечная высота могла быть объявлена выше ИЛИ ниже вызова —
            # в цепочке модификаторов это обычное дело.
            window = lines[max(0, cursor - LOOKBACK) : cursor + LOOKAHEAD + 1]
            if any(BOUNDED.search(row) for row in window):
                continue
            if any(
                (m := BOUNDED_VAR.search(row)) and m.group(1) in heights
                for row in window
            ):
                continue
            seen.add(cursor)
            problems.append(
                f"{path.relative_to(ROOT)}:{cursor + 1}: прокрутка внутри "
                f"{SCROLLING_DIALOG.rstrip('(')} — измеряется с бесконечной "
                "высотой и роняет Compose. Прокручивает сам диалог; здесь "
                "оставьте Column (или задайте heightIn(max = …))."
            )
    return problems


def main(argv: list[str]) -> int:
    if argv:
        print("использование: check-dialog-scroll.py", file=sys.stderr)
        return 2
    problems = []
    for path in kotlin_files():
        problems.extend(find_violations(path))
    if problems:
        print("найдена прокрутка внутри прокручивающегося диалога:")
        for problem in problems:
            print("  -", problem)
        return 1
    print("в порядке: вложенной прокрутки в ApuSettingsDialog нет")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
