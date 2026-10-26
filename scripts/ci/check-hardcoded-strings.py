#!/usr/bin/env python3
"""Контракт локализации: в `ui/**` не появляется новых русских строк «в коде».

Почему это важно. В приложении есть `values-en`, но часть текста интерфейса
написана прямо в Kotlin: аудит 2026-10-26 (п. 4.4) насчитал 359 таких строк в
45 файлах. При английской локали они остаются русскими, а при русской —
перевод не может их поправить, не залезая в код.

Перевести всё сразу нельзя: часть «строк» — это данные (названия эмодзи,
подписи в каталоге), а не текст интерфейса. Поэтому здесь тот же приём, что и
для god-файлов, — храповик: у каждого файла есть разрешённое число русских
литералов (текущее). Оно может уменьшаться (`--write`), увеличиваться — нет.
Новый файл с русскими строками появляется только через правку baseline, то
есть осознанно.

Строки-пунктуация (`·`, `—`) и однобуквенные не считаются: это разделители,
а не текст.

Запуск:
    python3 scripts/ci/check-hardcoded-strings.py            # проверить
    python3 scripts/ci/check-hardcoded-strings.py --write    # опустить счётчики
Код возврата: 0 — в порядке, 1 — больше строк, чем разрешено, 2 — ошибка вызова.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui"
BASELINE = ROOT / "scripts/ci/hardcoded-strings-baseline.json"

CYRILLIC = re.compile(r"[А-Яа-яЁё]")
# Строковый литерал Kotlin: обычный или тройной кавычки.
STRING = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"', re.S)


def strip_comments(text):
    """Убрать // и /* */ комментарии, не трогая строки в кавычках."""
    out = []
    i = 0
    length = len(text)
    while i < length:
        ch = text[i]
        if ch == '"':
            match = STRING.match(text, i)
            if match:
                out.append(match.group(0))
                i = match.end()
                continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = length if end < 0 else end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = length if end < 0 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def cyrillic_literals(text):
    """Текстовые литералы с кириллицей: без пунктуации и одиночных символов."""
    found = []
    for match in STRING.finditer(strip_comments(text)):
        literal = match.group(0)
        if not CYRILLIC.search(literal):
            continue
        content = literal.strip('"')
        if len(content) < 2:
            continue
        found.append(content)
    return found


def counts():
    result = {}
    for path in UI_ROOT.rglob("*.kt"):
        literals = cyrillic_literals(path.read_text(encoding="utf-8"))
        if literals:
            result[path.relative_to(ROOT).as_posix()] = len(literals)
    return result


def load_baseline():
    if not BASELINE.is_file():
        return {}
    return json.loads(BASELINE.read_text(encoding="utf-8")).get("allowed", {})


def write_baseline(allowed):
    payload = {
        "_comment": (
            "Разрешённое число русских строковых литералов в файлах ui/**. "
            "Уменьшать можно и нужно; увеличение — только осознанно, с планом перевода."
        ),
        "allowed": dict(sorted(allowed.items())),
    }
    BASELINE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check():
    allowed = load_baseline()
    actual = counts()
    problems = []
    improved = []

    for name, count in sorted(actual.items()):
        limit = allowed.get(name)
        if limit is None:
            problems.append(f"{name}: {count} русских строк в коде — файл не в baseline. "
                            "Вынесите текст в res/values/strings.xml.")
        elif count > limit:
            problems.append(f"{name}: {count} > разрешённых {limit}. Новый текст — в strings.xml "
                            "(и values-en), а не в Kotlin.")
        elif count < limit:
            improved.append(f"{name}: {count} < {limit} — можно опустить (--write)")

    for name in sorted(set(allowed) - set(actual)):
        improved.append(f"{name}: русских строк больше нет — запись можно убрать (--write)")

    if problems:
        print("в ui/** появились новые русские строки:")
        for problem in problems:
            print("  -", problem)
        return 1

    total = sum(actual.values())
    print(f"в порядке: {total} русских строк в {len(actual)} файлах, все в пределах baseline")
    for note in improved:
        print("  подсказка:", note)
    return 0


def main(argv):
    if "--write" in argv:
        write_baseline(counts())
        print(f"baseline записан: {BASELINE.relative_to(ROOT)}")
        return 0
    if argv:
        print("использование: check-hardcoded-strings.py [--write]")
        return 2
    return check()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
