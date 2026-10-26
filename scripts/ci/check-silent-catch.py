#!/usr/bin/env python3
"""Контракт обработки ошибок: `catch` не молчит.

Почему это важно. Аудит 2026-10-26 (п. 4.2) нашёл 434 `catch (e: Exception)` и
35 полностью пустых `catch`. Ловить `Exception` бывает нужно (сетевой сбой не
должен ронять сервис), но пока причина не попадает ни в лог, ни в счётчик,
реальный баг (`NullPointerException`, `IllegalStateException`) выглядит как
«ничего не произошло»: сообщение исчезло, и следов нет.

Правила:
  A. Пустой `catch` без единого комментария — нарушение всегда, без baseline.
     Если глотать действительно безопасно (`close()`, `stop()`, освобождение
     ресурса), это пишется комментарием в теле — тогда видно, что это решение,
     а не забытая заготовка.
  B. `catch (…: Exception)` / `Throwable`, в теле которого нет ни логирования,
     ни счётчика диагностики, ни повторного `throw`, — считается по файлам и
     ограничивается baseline'ом (храповик): число может только уменьшаться.

Запуск:
    python3 scripts/ci/check-silent-catch.py            # проверить
    python3 scripts/ci/check-silent-catch.py --write    # опустить baseline
Код возврата: 0 — в порядке, 1 — нарушение, 2 — ошибка вызова.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
BASELINE = ROOT / "scripts/ci/silent-catch-baseline.json"

# Признаки того, что ошибка не потеряна: логирование в logcat, счётчик
# диагностики или собственный хелпер файла (`diag(...)` в каналах связи).
REPORTING = re.compile(
    r"\bLog\.|\bLogger\.|OperationalDiagnostics|TransferDiagnostics|RuntimeDiagnostics"
    r"|DiagnosticPhase|\brecordWarning\(|\brecordFailure\(|printStackTrace|\bdiag\("
)
THROW = re.compile(r"\bthrow\b")
CATCH = re.compile(r"\bcatch\s*\(")
BROAD = re.compile(r"catch\s*\([^)]*:\s*(?:Exception|Throwable)\b")


def mask(text):
    """Комментарии и строковые литералы → пробелы (длины и переводы строк сохранены).

    Нужно, чтобы скобки внутри строк не ломали разбор, а комментарий в теле
    `catch` можно было распознать отдельно от кода.
    """
    out = list(text)
    i = 0
    length = len(text)
    while i < length:
        two = text[i:i + 2]
        if two == "//":
            end = text.find("\n", i)
            end = length if end < 0 else end
            for j in range(i, end):
                out[j] = " "
            i = end
            continue
        if two == "/*":
            end = text.find("*/", i + 2)
            end = length if end < 0 else end + 2
            for j in range(i, end):
                if out[j] != "\n":
                    out[j] = " "
            i = end
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = length if end < 0 else end + 3
            for j in range(i, end):
                if out[j] != "\n":
                    out[j] = " "
            i = end
            continue
        if text[i] == '"':
            j = i + 1
            while j < length:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    break
                j += 1
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


def body_bounds(masked, open_brace):
    """Индекс закрывающей скобки для блока, начинающегося с `{` в open_brace."""
    depth = 0
    for i in range(open_brace, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def inspect(path):
    """(пустые catch, широкие catch без отчёта) для файла."""
    text = path.read_text(encoding="utf-8")
    masked = mask(text)
    empty = []
    silent = []
    for match in CATCH.finditer(masked):
        brace = masked.find("{", match.end())
        if brace < 0:
            continue
        close = body_bounds(masked, brace)
        if close < 0:
            continue
        raw_body = text[brace + 1:close]
        masked_body = masked[brace + 1:close]
        has_comment = bool(re.search(r"//|/\*", raw_body))
        has_code = bool(masked_body.strip())
        line = line_of(text, match.start())

        if not has_code and not has_comment:
            empty.append(line)
            continue

        head = masked[match.start():brace]
        if not BROAD.search(head):
            continue
        if REPORTING.search(raw_body):
            continue
        if THROW.search(masked_body):
            continue
        silent.append(line)
    return empty, silent


def scan():
    empty_total = []
    silent_per_file = {}
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        empty, silent = inspect(path)
        rel = path.relative_to(ROOT).as_posix()
        for line in empty:
            empty_total.append(f"{rel}:{line}")
        if silent:
            silent_per_file[rel] = len(silent)
    return empty_total, silent_per_file


def load_baseline():
    if not BASELINE.is_file():
        return {}
    return json.loads(BASELINE.read_text(encoding="utf-8")).get("allowed", {})


def write_baseline(silent_per_file):
    payload = {
        "_comment": (
            "Разрешённое число широких catch (Exception/Throwable) без логирования "
            "и без счётчика. Уменьшать можно и нужно; увеличение — только осознанно."
        ),
        "allowed": dict(sorted(silent_per_file.items())),
    }
    BASELINE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check():
    allowed = load_baseline()
    empty_total, silent = scan()
    problems = []

    for place in empty_total:
        problems.append(
            f"{place}: пустой catch без комментария — либо логируйте причину, "
            "либо напишите в теле, почему глотать безопасно (аудит п. 4.2)"
        )

    for name, count in sorted(silent.items()):
        limit = allowed.get(name)
        if limit is None:
            problems.append(f"{name}: {count} широких catch без отчёта — файл не в baseline")
        elif count > limit:
            problems.append(f"{name}: {count} > разрешённых {limit}")

    if problems:
        print("молчащие catch:")
        for problem in problems:
            print("  -", problem)
        return 1

    total = sum(silent.values())
    print(f"в порядке: пустых catch без комментария — 0; "
          f"широких catch без отчёта — {total} в {len(silent)} файлах (baseline)")
    for name, count in sorted(silent.items()):
        if count < allowed.get(name, count):
            print(f"  подсказка: {name}: {count} < {allowed[name]} — можно опустить (--write)")
    return 0


def main(argv):
    if "--write" in argv:
        _, silent = scan()
        write_baseline(silent)
        print(f"baseline записан: {BASELINE.relative_to(ROOT)}")
        return 0
    if argv:
        print("использование: check-silent-catch.py [--write]")
        return 2
    return check()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
