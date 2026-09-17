#!/usr/bin/env python3
"""Проверка файлов воркфлоу на грабли, которые ловит GitHub Actions.

Полноценный YAML-парсер здесь не нужен: нужны ровно те ошибки, которые
GitHub показывает как «This run likely failed because of a workflow file
issue» - то есть без логов и без имени воркфлоу.

Что проверяем:
  * табуляцию в отступе (в YAML запрещена);
  * двоеточие с пробелом внутри значения без кавычек - самое частое: строка
    вида `- name: uniffi: сборка моста` превращает значение во вложенное
    отображение, и файл не разбирается целиком;
  * повтор ключа верхнего уровня (например два `jobs:`);
  * значение, оканчивающееся двоеточием.

Запуск: python3 scripts/ci/check-workflow-yaml.py <файл> [<файл> ...]
Код возврата 1, если есть проблемы.
"""
import re
import sys

BLOCK_OK = ("|", ">", "|-", ">-", "|+", ">+", "[]", "{}")


def check(path: str):
    problems = []
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as error:
        return [(0, f"файл не читается: {error}", "")]
    if text.startswith("\ufeff"):
        problems.append((1, "BOM в начале файла", ""))
    top_keys = {}
    for num, line in enumerate(text.split("\n"), 1):
        if "\t" in line:
            problems.append((num, "табуляция в отступе", line))
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.match(r"^(\s*)(?:-\s+)?([A-Za-z0-9_.\-]+):\s*(.*)$", line)
        if not match:
            continue
        indent, key, value = match.group(1), match.group(2), match.group(3).rstrip()
        if not indent:
            if key in top_keys:
                problems.append(
                    (num, f"ключ верхнего уровня {key!r} повторён (был в строке {top_keys[key]})", line)
                )
            top_keys[key] = num
        if value and value[0] not in "\"'":
            if value in BLOCK_OK or value.startswith("${{"):
                continue
            if value.endswith(":") or ": " in value:
                problems.append(
                    (num, "двоеточие с пробелом внутри значения без кавычек", line)
                )
    return problems


def main(argv):
    rc = 0
    for path in argv[1:]:
        problems = check(path)
        if problems:
            rc = 1
            print(f"=== {path}: {len(problems)} проблем(ы) ===")
            for num, why, line in problems:
                print(f"  строка {num}: {why}")
                if line:
                    print(f"    {line}")
        else:
            print(f"=== {path}: чисто ===")
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
