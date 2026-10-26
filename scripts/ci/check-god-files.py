#!/usr/bin/env python3
"""Контракт размера файлов: god-файлы могут уменьшаться, но не расти.

Почему это важно. Аудит 2026-10-26 (п. 4.1) нашёл двадцать файлов больше
1200 строк; самый большой — `GroupRepository.kt` на 6979 строк. Переписать их
разом нельзя: это ядро доставки сообщений и обновлений, и «большой рефакторинг
без компилятора» — это гарантированный простой. Рабочее правило из аудита —
**резать по швам при каждом касании**, — но правило без проверки живёт
неделю: именно так эти файлы и выросли.

Здесь правило превращается в храповик: у каждого известного большого файла
есть потолок (его текущий размер). Потолок можно только опускать. Любой рост
— красный CI с просьбой вынести код в новый файл. Новый файл больше
порога тоже не появляется: это тот же god-файл, только свежий.

Порог осознанно не «сделать красиво»: файл на 1300 строк читается нормально,
если у него один шов. Цель — не спрятать проблему, а не дать ей вырасти.

Запуск:
    python3 scripts/ci/check-god-files.py            # проверить
    python3 scripts/ci/check-god-files.py --write    # опустить потолки до факта
Код возврата: 0 — в порядке, 1 — нарушение, 2 — ошибка вызова.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "android-app/app/src/main"
BASELINE = ROOT / "scripts/ci/god-files-baseline.json"

# Файл этого размера и больше — уже «god»: его правки читаются тяжело, а
# конфликты при слиянии почти неизбежны.
THRESHOLD_LINES = 1200

# Сгенерированный мост uniffi: размер задаёт генератор, а не мы.
IGNORED_PARTS = ("/uniffi/",)


def kotlin_files():
    for path in SOURCE_ROOT.rglob("*.kt"):
        posix = path.as_posix()
        if any(part in posix for part in IGNORED_PARTS):
            continue
        yield path


def line_counts():
    counts = {}
    for path in kotlin_files():
        with path.open("rb") as handle:
            counts[path.relative_to(ROOT).as_posix()] = sum(1 for _ in handle)
    return counts


def big_files(counts):
    return {name: size for name, size in counts.items() if size >= THRESHOLD_LINES}


def load_baseline():
    if not BASELINE.is_file():
        return {}
    return json.loads(BASELINE.read_text(encoding="utf-8")).get("ceilings", {})


def write_baseline(ceilings):
    payload = {
        "_comment": (
            "Потолок строк для больших файлов. Опускать можно и нужно; поднимать — "
            "только вместе с объяснением в PR, почему код нельзя было вынести."
        ),
        "threshold": THRESHOLD_LINES,
        "ceilings": dict(sorted(ceilings.items())),
    }
    BASELINE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check():
    ceilings = load_baseline()
    actual = big_files(line_counts())
    problems = []
    tightened = []

    for name, size in sorted(actual.items()):
        ceiling = ceilings.get(name)
        if ceiling is None:
            problems.append(
                f"{name}: {size} строк — новый файл выше порога {THRESHOLD_LINES}. "
                "Вынесите часть кода в отдельный файл по шву (аудит 2026-10-26, п. 4.1)."
            )
        elif size > ceiling:
            problems.append(
                f"{name}: {size} строк > потолка {ceiling}. Файл вырос: новый код — "
                "в новый файл рядом, а не в god-файл."
            )
        elif size < ceiling:
            tightened.append(f"{name}: {size} < потолка {ceiling} — можно опустить (--write)")

    for name in sorted(set(ceilings) - set(actual)):
        tightened.append(f"{name}: больше не превышает порог — потолок можно убрать (--write)")

    if problems:
        print("размер файлов выше потолка:")
        for problem in problems:
            print("  -", problem)
        return 1

    print(f"в порядке: файлов выше {THRESHOLD_LINES} строк — {len(actual)}, все в потолке")
    for note in tightened:
        print("  подсказка:", note)
    return 0


def main(argv):
    if "--write" in argv:
        write_baseline(big_files(line_counts()))
        print(f"потолки записаны: {BASELINE.relative_to(ROOT)}")
        return 0
    if argv:
        print("использование: check-god-files.py [--write]")
        return 2
    return check()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
