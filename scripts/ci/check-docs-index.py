#!/usr/bin/env python3
"""Контракт карты документации: `docs/README.md` не отстаёт от каталога.

Почему это важно. В `docs/` около шести сотен файлов, из них 61 — не выпуски.
Без карты новый человек (и следующий ИИ) открывает первый попавшийся
`*_HANDOFF.md` и читает там «текущий релиз v11.74.206», хотя текущий давно
другой. Так уже случалось: несколько документов одновременно называли себя
точкой входа, и каждый указывал на свой старый выпуск.

Проверяется:
  1) каждый `docs/*.md` (кроме архива выпусков `RELEASE_NOTES_*` и
     `RELEASE_AUDIT_*`) упомянут ссылкой в `docs/README.md`;
  2) каждая ссылка `docs/README.md` на файл в `docs/` ведёт на существующий
     файл (мёртвая ссылка в карте хуже отсутствия карты);
  3) карта называет трекер источником состояния — иначе она сама станет ещё
     одним «документом о состоянии», которые расходятся;
  4) пути выпусков не переименованы: `build-release.yml` берёт описание из
     `docs/RELEASE_NOTES_<тег>.md`, поэтому файлы выпусков должны лежать
     именно в корне `docs/`.

Запуск: python3 scripts/ci/check-docs-index.py
Код возврата: 0 — карта в порядке, 1 — расхождения, 2 — нет самой карты.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
INDEX = DOCS / "README.md"
BUILD_RELEASE = ROOT / ".github/workflows/build-release.yml"

# Архив выпусков: по файлу на версию, в карте не перечисляется.
ARCHIVE_PREFIXES = ("RELEASE_NOTES_", "RELEASE_AUDIT_")

LINK_RE = re.compile(r"\]\(([A-Za-z0-9_.\-]+\.md)\)")


def documented_files():
    return {
        path.name
        for path in DOCS.glob("*.md")
        if path.name != "README.md" and not path.name.startswith(ARCHIVE_PREFIXES)
    }


def linked_files(text):
    return set(LINK_RE.findall(text))


def check_returncode():
    if not INDEX.is_file():
        print("нет docs/README.md — карта документации обязательна")
        return 2

    text = INDEX.read_text(encoding="utf-8")
    on_disk = documented_files()
    linked = linked_files(text)
    problems = []

    for name in sorted(on_disk - linked):
        problems.append(f"docs/{name} есть в каталоге, но не упомянут в docs/README.md")

    for name in sorted(linked - on_disk):
        # README.md ссылается сам на себя — это допустимо.
        if name == "README.md":
            continue
        problems.append(f"docs/README.md ссылается на docs/{name}, которого нет")

    if "APP_DELIVERY_TRACKER.md" not in linked:
        problems.append("docs/README.md не ссылается на трекер состояния APP_DELIVERY_TRACKER.md")

    if not BUILD_RELEASE.is_file():
        problems.append("нет .github/workflows/build-release.yml — нечего сверять с путём описания выпуска")
    else:
        workflow = BUILD_RELEASE.read_text(encoding="utf-8")
        if "docs/RELEASE_NOTES_${{ steps.get_version.outputs.VERSION }}.md" not in workflow:
            problems.append(
                "build-release.yml больше не берёт описание из docs/RELEASE_NOTES_<тег>.md: "
                "обновите карту docs/README.md, если путь выпусков изменился"
            )
        for sub in ("releases", "archive"):
            if (DOCS / sub).is_dir():
                problems.append(
                    f"docs/{sub}/ существует: файлы выпусков должны лежать в корне docs/, "
                    "иначе публикация не найдёт описание"
                )

    if problems:
        print("карта документации расходится с каталогом:")
        for problem in problems:
            print("  -", problem)
        return 1

    print(f"docs/README.md в порядке: {len(on_disk)} документов, все упомянуты и существуют")
    return 0


class DocsIndexContractTest(unittest.TestCase):
    """Самопроверка парсера: без неё скрипт может «проходить» на пустом наборе."""

    def test_links_are_extracted_from_table_cells(self):
        text = "| [`A.md`](A.md) | x |\n| [`B.md`](B.md) | y |\n"
        self.assertEqual(linked_files(text), {"A.md", "B.md"})

    def test_external_links_and_anchors_are_ignored(self):
        text = "[a](https://example.org/x.md) [b](#section) [c](sub/C.md)\n"
        self.assertEqual(linked_files(text), set())

    def test_archive_names_are_not_required_in_map(self):
        self.assertTrue("RELEASE_NOTES_v1.0.md".startswith(ARCHIVE_PREFIXES))
        self.assertFalse("ADDRESS_BOOK.md".startswith(ARCHIVE_PREFIXES))


def main(argv):
    if "--self-test" in argv:
        # argv очищается: иначе unittest пытается разобрать `--self-test` как
        # свой аргумент и падает на usage вместо прогона тестов.
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(DocsIndexContractTest)
        result = unittest.TextTestRunner(verbosity=2, stream=sys.stdout).run(suite)
        return 0 if result.wasSuccessful() else 1
    return check_returncode()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
