#!/usr/bin/env python3
"""Синтаксис Kotlin без компилятора: разбор грамматикой tree-sitter-kotlin.

Зачем. В песочнице нет ни Kotlin-, ни Java-компилятора, а Kotlin собирается
только в CI — и то лишь на сборке тега для Android. Опечатка в правке Kotlin
доходила до зелёного PR-прогона, который Kotlin вообще не компилирует, и
всплывала уже на теге. Этот разбор ловит класс «файл не разбирается»:
незакрытая скобка, оборванный `when`, лишнее слово в объявлении.

Что проверяется. Узлы `ERROR` и `is_missing` в дереве разбора — ровно то, на
чём падает компилятор синтаксисом. Типы, имена и вызовы здесь не проверяются
(для имён есть `check_unresolved_symbols.py`).

Как собрать грамматику (один раз на песочницу; /tmp не переживает перезапуск):

    curl -sL https://codeload.github.com/fwcd/tree-sitter-kotlin/tar.gz/refs/heads/main \\
        | tar xz -C /tmp/tsk --strip-components=1
    cc -shared -fPIC -I/tmp/tsk/src /tmp/tsk/src/parser.c /tmp/tsk/src/scanner.c -o /tmp/kotlin.so

Грамматику берём из fwcd (ABI 14). Пакет `tree_sitter_kotlin` с PyPI для этой
задачи НЕ годится: он разбирает обычные файлы этого проекта с ошибками
(проверено 2026-10-15 — «ошибка» на `class X {`), поэтому по умолчанию грузим
ту самую .so, а путь можно переопределить через KOTLIN_GRAMMAR_SO.

Важно про шум. Грамматика не знает аннотированных функциональных типов
(`content: @Composable () -> Unit`) и показывает на них `ERROR` в файлах,
которые прекрасно собираются (14 файлов на всё дерево). Поэтому рабочий режим —
сравнение с эталоном: `--baseline HEAD` печатает только ТЕ ошибки разбора,
которых не было в коммите. Красный результат здесь означает «правка сломала
файл», а не «грамматика чего-то не умеет».

Запуск:
    pip install tree_sitter
    python3 tools/sandbox/kt_syntax.py --baseline HEAD <файлы или каталоги>
    python3 tools/sandbox/kt_syntax.py <файлы>            # без эталона: как есть
Код возврата 0 — новых ошибок разбора нет, 1 — есть.
"""
import ctypes
import os
import sys
from ctypes import c_char_p, c_void_p
from pathlib import Path

from tree_sitter import Language, Parser

NEWLINE = b"\n"
DEFAULT_GRAMMAR = "/tmp/kotlin.so"


def load_language():
    """Грамматика Kotlin: собранная .so (ABI 14) через PyCapsule.

    `Language(<int>)` в свежих tree-sitter падает сегфолтом, а устаревший
    `Language(path, name)` не разбирает дерево вовсе — рабочий путь один:
    обернуть указатель `tree_sitter_kotlin()` в PyCapsule и отдать его
    конструктору Language.
    """
    so_path = os.environ.get("KOTLIN_GRAMMAR_SO", DEFAULT_GRAMMAR)
    if not Path(so_path).is_file():
        raise SystemExit(
            f"нет грамматики {so_path}: соберите её (см. шапку файла) "
            "или укажите KOTLIN_GRAMMAR_SO"
        )
    lib = ctypes.cdll.LoadLibrary(so_path)
    lib.tree_sitter_kotlin.restype = c_void_p
    capsule_new = ctypes.pythonapi.PyCapsule_New
    capsule_new.restype = ctypes.py_object
    capsule_new.argtypes = [c_void_p, c_char_p, c_void_p]
    capsule = capsule_new(c_void_p(lib.tree_sitter_kotlin()), b"tree_sitter.Language", None)
    return Language(capsule)


def problems_of_bytes(parser: Parser, source: bytes) -> list[str]:
    """Строки с узлами ERROR/is_missing (пусто — файл разобрался)."""
    tree = parser.parse(source)
    found: list[str] = []
    stack = [tree.root_node]
    while stack:
        node = stack.pop()
        if node.type == "ERROR" or node.is_missing:
            line = source[: node.start_byte].count(NEWLINE) + 1
            found.append(f"{line}:{node.type}")
        stack.extend(node.children)
    return found


def baseline_bytes(path: Path, rev: str) -> bytes | None:
    """Содержимое файла в ревизии (например HEAD), либо None.

    Путь переводится в относительный от корня репозитория: `git show`
    принимает только пути вида `<rev>:app/src/...`.
    """
    import subprocess
    try:
        root = Path(subprocess.run(
            ["git", "rev-parse", "--show-toplevel"], cwd=path.parent,
            capture_output=True, text=True, check=True,
        ).stdout.strip())
    except Exception:
        return None
    try:
        rel = path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return None
    done = subprocess.run(
        ["git", "-C", str(root), "show", f"{rev}:{rel}"],
        capture_output=True,
    )
    return done.stdout if done.returncode == 0 else None


def main(argv: list[str]) -> int:
    args = argv[1:]
    baseline: str | None = None
    if args[:1] == ["--baseline"]:
        baseline = args[1] if len(args) > 1 else "HEAD"
        args = args[2:]
    roots = [Path(a) for a in args] or [Path("android-app/app/src")]
    parser = Parser(load_language())
    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.kt")) if root.is_dir() else [root])

    bad = 0
    for path in files:
        found = problems_of_bytes(parser, path.read_bytes())
        if baseline:
            known = baseline_bytes(path, baseline)
            if known is not None:
                stale = problems_of_bytes(parser, known)
                # Сравниваем по позиции, а не по количеству: сдвиг строки из-за
                # вставки выше не должен считаться новой ошибкой.
                found = [item for item in found if item not in stale]
        if found:
            bad += 1
            print(f"{path}: {', '.join(found[:5])}")
    scope = f"новых (эталон {baseline})" if baseline else "всего"
    print(f"--- файлов: {len(files)}, проблемных мест {scope}: {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
