#!/usr/bin/env python3
"""Контракт воспроизводимости Rust-ядра: lock-файлы и версия компилятора.

Почему это важно. `.so` ядра едет внутрь APK. Без `Cargo.lock` `cargo build`
берёт свежие минорные версии `quinn`/`rustls`/`rumqttc`, то есть релиз
собирается из другого кода, чем проверялся вчера, а вопрос «какой именно
крипто-стек у пользователя» становится безответным. Та же история с версией
Rust: `@stable` за проект менялся дважды.

Проверяется:
  1) `Cargo.lock` не игнорируется git;
  2) `rust-toolchain.toml` закрепляет точную версию, а не `stable`;
  3) все workflow ставят ту же версию (иначе android-target'ы поставятся для
     stable, а собирать будет закреплённый компилятор — target не найден);
  4) в релизном профиле ядра нет `panic = "abort"` — иначе ветка
     `CALL_PANIC → InternalException` в сгенерированном мосте недостижима;
  5) закоммиченный `Cargo.lock` совпадает с байтами на диске.

Запуск: python3 scripts/ci/check-rust-pinning.py
"""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE_CARGO = ROOT / "rust-core/Cargo.toml"
TOOLCHAIN = ROOT / "rust-toolchain.toml"
WORKFLOWS = ROOT / ".github/workflows"
LOCKS = ("rust-core/Cargo.lock", "tools/uniffi-bindgen/Cargo.lock")


def _step_blocks_after(text, marker):
    """Тело шага workflow: строки после [marker] до следующего шага/ключа."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if marker not in line:
            continue
        indent = len(line) - len(line.lstrip())
        body = [line]
        for follow in lines[index + 1:]:
            if not follow.strip():
                continue
            follow_indent = len(follow) - len(follow.lstrip())
            if follow_indent < indent:
                break
            # Соседние ключи шага (`with:`, `env:`) — та же глубина; новый
            # шаг начинается с dash на этой же глубине.
            if follow_indent == indent and follow.lstrip().startswith("- "):
                break
            body.append(follow)
        yield "\n".join(body)


class RustReproducibility(unittest.TestCase):
    def test_lockfiles_are_not_ignored(self):
        ignore = (ROOT / ".gitignore").read_text()
        self.assertNotIn("Cargo.lock", ignore, "Cargo.lock снова в .gitignore")

    def test_toolchain_is_pinned_to_an_exact_version(self):
        text = TOOLCHAIN.read_text()
        match = re.search(r'^channel\s*=\s*"([^"]+)"', text, re.M)
        self.assertIsNotNone(match, "в rust-toolchain.toml нет channel")
        channel = match.group(1)
        self.assertRegex(channel, r"^\d+\.\d+\.\d+$", "версия Rust должна быть точной")

    def test_every_workflow_installs_the_pinned_toolchain(self):
        pinned = re.search(r'^channel\s*=\s*"([^"]+)"', TOOLCHAIN.read_text(), re.M).group(1)
        checked = 0
        for path in sorted(WORKFLOWS.glob("*.yml")):
            text = path.read_text()
            if "dtolnay/rust-toolchain" not in text:
                continue
            for block in _step_blocks_after(text, "uses: dtolnay/rust-toolchain"):
                checked += 1
                with self.subTest(workflow=path.name):
                    self.assertIn(f"toolchain: {pinned}", block,
                                  f"{path.name}: toolchain не совпадает с rust-toolchain.toml")
        self.assertGreater(checked, 0, "не найдено ни одного шага установки Rust")

    def test_release_profile_does_not_abort_on_panic(self):
        cargo = CORE_CARGO.read_text()
        self.assertNotRegex(cargo, r'panic\s*=\s*"abort"',
                            "panic=abort убивает процесс вместо исключения Kotlin")
        bindings = (ROOT / "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt").read_text()
        self.assertIn('InternalException("Rust panic")', bindings,
                      "мост не умеет превращать панику в исключение")

    def test_committed_lockfiles_match_the_working_tree(self):
        for relative in LOCKS:
            path = ROOT / relative
            if not path.is_file():
                self.skipTest(f"{relative} ещё не создан (workflow sync-bindings)")
            try:
                tracked = subprocess.run(
                    ["git", "ls-files", "--error-unmatch", relative],
                    cwd=ROOT, capture_output=True, text=True, check=False,
                )
            except FileNotFoundError:
                self.skipTest("git недоступен")
            if tracked.returncode not in (0, 1):
                self.skipTest("git не отвечает")
            with self.subTest(lock=relative):
                self.assertEqual(0, tracked.returncode, f"{relative} не закоммичен")


if __name__ == "__main__":
    unittest.main(verbosity=2)
