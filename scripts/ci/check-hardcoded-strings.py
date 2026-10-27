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

Вторая часть контракта — сами `res/values*/strings.xml`:
  * одинаковое имя дважды ломает сборку (`mergeReleaseResources`: Found item
    String/x more than one time) — это ошибка, а не предупреждение;
  * ключи, которые есть в `values`, но отсутствуют в `values-en`, при
    английской локали остаются русскими — их число хранится в baseline
    (`missing_en`) и может только уменьшаться;
  * подстановки `%1$d`/`%2$s` в переводе обязаны совпадать с оригиналом:
    лишние аргументы `getString` молча игнорирует, то есть часть данных
    просто исчезает из фразы.

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
RES_ROOT = ROOT / "android-app/app/src/main/res"
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


STRING_NAME = re.compile(r'<string\s+name="([A-Za-z0-9_]+)"[^>]*>(.*?)</string>', re.S)
PLACEHOLDER = re.compile(r"%(\d+)\$[a-zA-Z]")


def string_entries(path):
    """Пары (имя, текст) строковых ресурсов в файле."""
    return STRING_NAME.findall(path.read_text(encoding="utf-8"))


def resource_names(path):
    """Имена строковых ресурсов в файле (в порядке файла)."""
    return [name for name, _ in string_entries(path)]


def placeholder_problems():
    """Подстановки `%1$s` в переводе должны совпадать с русским оригиналом.

    Если в переводе меньше подстановок, пользователь не видит часть данных
    (а лишние аргументы `getString` молча игнорирует), если больше или они
    перепутаны — падает форматирование. И то, и другое ловится только на
    устройстве, поэтому проверяем на CI.
    """
    ru_path = RES_ROOT / "values/strings.xml"
    en_path = RES_ROOT / "values-en/strings.xml"
    if not ru_path.is_file() or not en_path.is_file():
        return []
    ru = dict(string_entries(ru_path))
    en = dict(string_entries(en_path))
    problems = []
    for name, text in sorted(en.items()):
        if name not in ru:
            continue
        want = PLACEHOLDER.findall(ru[name])
        got = PLACEHOLDER.findall(text)
        if sorted(want) != sorted(got):
            problems.append(f"values-en/strings.xml: {name}: подстановки {got or 'нет'} вместо "
                            f"{want or 'нет'} — перевод должен повторять русский оригинал.")
    return problems


def resource_problems():
    """Проблемы самих res/values*: дубликаты имён и отставание values-en.

    Дубликат ломает сборку (`mergeReleaseResources`: Found item String/x more
    than one time), причём только на CI — поэтому ловится здесь. Пропуски в
    values-en не ломают сборку, но при английской локали строка остаётся
    русской; их число — храповик `missing_en` в baseline.
    """
    problems = []
    duplicates = {}
    for values in sorted(RES_ROOT.glob("values*/strings.xml")):
        seen = {}
        for name in resource_names(values):
            seen[name] = seen.get(name, 0) + 1
        repeated = sorted(name for name, count in seen.items() if count > 1)
        if repeated:
            problems.append(f"{values.relative_to(ROOT).as_posix()}: имя объявлено дважды — "
                            + ", ".join(repeated) + ". Оставьте одно объявление.")
        duplicates[values.parent.name] = set(seen)
    if "values" in duplicates and "values-en" in duplicates:
        missing = duplicates["values"] - duplicates["values-en"]
    else:
        missing = set()
    return problems, missing


def load_baseline():
    if not BASELINE.is_file():
        return {}, None, {}
    payload = json.loads(BASELINE.read_text(encoding="utf-8"))
    return (payload.get("allowed", {}),
            payload.get("missing_en"),
            payload.get("data", {}))


def write_baseline(allowed, missing_en=None, data_notes=None):
    payload = {
        "_comment": (
            "Разрешённое число русских строковых литералов в файлах ui/**. "
            "Уменьшать можно и нужно; увеличение — только осознанно, с планом перевода."
        ),
        "allowed": dict(sorted(allowed.items())),
    }
    if missing_en is not None:
        payload["missing_en"] = missing_en
    if data_notes:
        payload["data"] = data_notes
    BASELINE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check():
    allowed, allowed_missing_en, data_notes = load_baseline()
    actual = counts()
    problems, missing_en = resource_problems()
    problems += placeholder_problems()
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

    # Файлы-данные: объяснение живёт рядом с числом, иначе следующая сессия
    # честно попытается «перевести» поисковый индекс и сломает поиск.
    for name, why in sorted(data_notes.items()):
        if name in actual:
            improved.append(f"{name}: это данные, не текст интерфейса — {why}")

    if allowed_missing_en is not None and len(missing_en) > allowed_missing_en:
        problems.append(
            f"values-en: без перевода {len(missing_en)} строк (разрешено {allowed_missing_en}). "
            "Новый ключ добавляйте сразу в values/strings.xml и values-en/strings.xml."
        )
    elif allowed_missing_en is not None and len(missing_en) < allowed_missing_en:
        improved.append(f"values-en: без перевода {len(missing_en)} < {allowed_missing_en} — "
                        "можно опустить (--write)")

    if problems:
        print("контракт локализации нарушен:")
        for problem in problems:
            print("  -", problem)
        return 1

    total = sum(actual.values())
    print(f"в порядке: {total} русских строк в {len(actual)} файлах, все в пределах baseline; "
          f"values-en без перевода — {len(missing_en)}")
    for note in improved:
        print("  подсказка:", note)
    return 0


def main(argv):
    if "--write" in argv:
        _, missing_en, data_notes = load_baseline()
        _, missing_en_now = resource_problems()
        write_baseline(counts(), len(missing_en_now), data_notes)
        print(f"baseline записан: {BASELINE.relative_to(ROOT)}")
        return 0
    if argv:
        print("использование: check-hardcoded-strings.py [--write]")
        return 2
    return check()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
