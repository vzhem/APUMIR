#!/usr/bin/env python3
"""Список файлов в check-chat-history.sh не должен отставать от кода.

Зачем. `scripts/ci/check-chat-history.sh` компилирует ВЫБОРКУ файлов приложения
на чистом JVM, без Android SDK. Список поддерживается руками, поэтому любое
новое обращение (например, `GroupWire` начал звать `GroupSos`, а тот —
`SosMessage`) превращает гейт в «unresolved reference» на каждом PR,
затрагивающем `rust-core/**` или `scripts/ci/**`. На это ушло три прогона CI:
в логах видно только хвост вывода, а причина — забытая строка в списке.

Проверка читает сам скрипт, собирает из него набор компилируемых файлов и
ищет в них ссылки на классы/объекты проекта, которых в наборе нет.

Запуск: python3 scripts/ci/check-chat-history-harness.py
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/check-chat-history.sh"
MAIN = ROOT / "android-app/app/src/main/java"


def expand(token, values, depth=0):
    """Раскрыть $VAR в токене: список компиляции состоит из "$VAR/file.kt"."""
    if depth > 8:
        return token
    expanded = re.sub(r"\$([A-Z_0-9]+)", lambda m: values.get(m.group(1), m.group(0)), token)
    if expanded == token:
        return expanded
    return expand(expanded, values, depth + 1)


def compiled_files():
    """Файлы, которые реально уходят в kotlinc (плюс сгенерированные заглушки)."""
    text = SCRIPT.read_text()
    values = {
        match.group(1): match.group(3).replace('"', "")
        for match in re.finditer(r"^([A-Z_0-9]+)=(\"?)(.*?)\2$", text, re.M)
    }
    # ROOT и BASE заданы в скрипте через command substitution, поэтому берём их
    # значения по смыслу, а не из текста.
    values.update({"ROOT": ".", "BASE": "android-app/app/src"})
    files = set()
    for match in re.finditer(r'"([^"]*\.kt)"', text):
        resolved = expand(match.group(1), values)
        if "$" in resolved:
            continue
        path = Path(resolved)
        if not path.is_absolute():
            files.add(str(path))
    return {ROOT / path for path in files if (ROOT / path).exists()}


def declarations():
    """FQN -> файл для верхнеуровневых object/class/interface."""
    found = {}
    for path in MAIN.rglob("*.kt"):
        text = path.read_text(errors="replace")
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not package:
            continue
        for match in re.finditer(
            r"^(?:@\w+\s+)?(?:public |internal |private |abstract |open |sealed |data |enum |annotation )*"
            r"(?:object|class|interface)\s+([A-Za-z_]\w*)",
            text,
            re.M,
        ):
            found.setdefault(package.group(1) + "." + match.group(1), path)
    return found


class HistoryHarnessIsComplete(unittest.TestCase):
    def test_every_referenced_project_type_is_compiled(self):
        files = compiled_files()
        self.assertGreater(len(files), 40, "не удалось разобрать список компиляции")
        resolvable = declarations()
        missing = set()
        for path in sorted(files):
            text = path.read_text(errors="replace")
            for match in re.finditer(r"\bcom\.vladimir\.messenger(?:\.[a-z_]\w*)*\.[A-Z]\w*", text):
                fqn = match.group(0)
                target = resolvable.get(fqn)
                if target is not None and target not in files:
                    missing.add(f"{fqn} ({target.relative_to(ROOT)})")
        self.assertEqual(
            set(),
            missing,
            "в check-chat-history.sh не хватает файлов — иначе kotlinc скажет "
            "'unresolved reference': " + "; ".join(sorted(missing)),
        )

    def test_generated_stub_is_not_a_silent_substitute(self):
        """Заглушка палитры генерируется из прод-файла, а не пишется руками."""
        text = SCRIPT.read_text()
        self.assertIn("STUB_DIR", text)
        self.assertIn("TextColorPalette.kt", text)
        palette = (MAIN / "com/vladimir/messenger/data/textcolor/TextColorPalette.kt").read_text()
        names = re.findall(r'Choice\(\s*(DEFAULT_ID|"[^"]*")\s*,', palette)
        self.assertGreaterEqual(len(names), 5, "не разобрать палитру прод-файла")
        self.assertIn("re.findall", text, "заглушка должна читать прод-палитру")


if __name__ == "__main__":
    unittest.main(verbosity=2)
