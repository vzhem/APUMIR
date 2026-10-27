#!/usr/bin/env python3
"""Контракт размера и доставки APK (аудит 2026-10-26, P1-4).

Почему это проверка, а не комментарий в документации. Размер APK — первое,
что видит человек на странице релиза, а состав файла задаётся в двух местах,
которые легко разъехались бы молча:

  * `android-app/app/build.gradle.kts` — какие ABI вообще упаковываются;
  * воркфлоу релиза — какой файл публикуется;
  * `service/UpdateChecker.kt` — какое имя файла ищет установленное
    приложение, чтобы обновиться.

Если в APK вернуть эмуляторную ABI, файл вырастет примерно на треть, и никто
этого не заметит до релиза. Если переименовать публикуемый файл, приложение
перестанет находить обновление — тоже только в бою. Поэтому:

  1. релиз собирается только для ARM (x86_64 — лишь по флагу -Papu.emulatorAbi);
  2. ABI-сплиты и AAB не включаются: приложение раздаётся файлом и роем
     APK-раздачи, где в пакете нет ABI (см. docs/AUDIT_2026-10-26.md, P1-4);
  3. имя APK в воркфлоу релиза совпадает с тем, что ищет UpdateChecker.

Запуск: python3 scripts/ci/check-release-abi.py
Код возврата: 0 — в порядке, 1 — нарушение, 2 — не нашёл файлы.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "android-app/app/build.gradle.kts"
UPDATER = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/service/UpdateChecker.kt"
WORKFLOWS = [
    ROOT / ".github/workflows/build-release.yml",
    ROOT / "scripts/ci/build-release.yml",
]

ARM_ABIS = '"arm64-v8a", "armeabi-v7a"'


def release_block(text):
    """Тело `buildTypes { release { ... } }` — без вложенных скобок наружу."""
    start = text.find("buildTypes {")
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i]
    return text[start:]


def check_gradle(problems):
    if not GRADLE.is_file():
        problems.append(f"{GRADLE}: файл не найден")
        return
    text = GRADLE.read_text(encoding="utf-8")
    if f"val armAbis = listOf({ARM_ABIS})" not in text:
        problems.append(
            "build.gradle.kts: нет `val armAbis = listOf(\"arm64-v8a\", \"armeabi-v7a\")` — "
            "релизный набор ABI должен быть ровно две ARM-копии."
        )
    if 'abiFilters += if (emulatorAbi) armAbis + "x86_64" else armAbis' not in text:
        problems.append(
            "build.gradle.kts: релизные abiFilters должны быть "
            "`if (emulatorAbi) armAbis + \"x86_64\" else armAbis` — "
            "эмуляторная ABI входит в APK только по флагу -Papu.emulatorAbi."
        )
    default_config = text[text.find("defaultConfig {"): text.find("buildTypes {")]
    if "abiFilters" in default_config:
        problems.append(
            "build.gradle.kts: abiFilters вернулись в defaultConfig — их значение "
            "перекроет релизный набор для всех типов сборки (см. P1-4)."
        )
    # Сплиты и бандл: приложение раздаётся файлом с GitHub и роем APK-раздачи,
    # где в пакете нет ABI. Отдельный APK на ABI сосед с другой ABI не поставит.
    for forbidden, why in (
        ("splits {", "ABI-сплиты: в пакете роя нет ABI (ApkSeederModels.kt), "
                     "сосед с другой ABI скачает неставимый APK"),
        ("bundle {", "AAB: приложение не публикуется в Google Play, "
                     "установить .aab с сайта нельзя"),
    ):
        if forbidden in text.replace("// ", ""):
            problems.append(f"build.gradle.kts: включены {forbidden.strip()} — {why}.")


def check_updater_names(problems):
    if not UPDATER.is_file():
        problems.append(f"{UPDATER}: файл не найден")
        return set()
    text = UPDATER.read_text(encoding="utf-8")
    names = set()
    canonical = re.search(r'canonicalName = "([^"]+)"', text)
    if canonical:
        names.add(canonical.group(1).replace("$latestVersion", "%s"))
    for literal in re.findall(r'name\.equals\("([^"]+\.apk)"', text, re.I):
        names.add(literal)
    if not names:
        problems.append("UpdateChecker.kt: не нашёл имён APK-файлов, которые ищет приложение")
    return names


def published_files(text):
    """Имена файлов из шага публикации релиза.

    `files:` в action-gh-release пишут двумя способами: строкой через запятую
    и блочным списком (`files: |` + пути с отступом). Разбираем оба, иначе
    проверка молча сравнивает пустое множество и «находит» нарушение там, где
    его нет.
    """
    lines = text.split("\n")
    for index, line in enumerate(lines):
        match = re.match(r"\s*files:\s*(.*)$", line)
        if not match:
            continue
        inline = match.group(1).strip()
        if inline and inline not in ("|", ">", "|-", ">-"):
            return {Path(part.strip()).name for part in inline.split(",") if part.strip()}
        if inline.startswith("|") or inline.startswith(">"):
            base = len(line) - len(line.lstrip())
            found = set()
            for follow in lines[index + 1:]:
                if not follow.strip():
                    continue
                indent = len(follow) - len(follow.lstrip())
                if indent <= base:
                    break
                found.add(Path(follow.strip()).name)
            return found
    return set()


def check_workflow(problems, updater_names):
    workflow = next((w for w in WORKFLOWS if w.is_file()), None)
    if workflow is None:
        problems.append("воркфлоу релиза не найден (ни .github/, ни scripts/ci/)")
        return
    text = workflow.read_text(encoding="utf-8")
    published = published_files(text)
    if not published:
        problems.append(f"{workflow.relative_to(ROOT)}: нет шага публикации `files:` "
                        "(или он пуст)")
        return
    if not published:
        problems.append(f"{workflow.relative_to(ROOT)}: `files:` пуст")
        return
    # Имя из воркфлоу либо ищется приложением напрямую, либо публикуется под
    # именем APU-<версия>.apk, которое UpdateChecker тоже знает (шаблон с %s).
    known = {name for name in updater_names if "%s" not in name}
    templates = [name for name in updater_names if "%s" in name]
    ok = False
    for name in published:
        if name in known:
            ok = True
        for template in templates:
            # `%s` — это версия (APU-v11.74.453.apk), а не что попало:
            # шаблон `APU-%s.apk` не должен «прощать» файл APU-app-release.apk.
            pattern = ("^" + re.escape(template).replace(re.escape("%s"), r"v?\d+(?:\.\d+)+")
                       + "$")
            if re.match(pattern, name, re.I):
                ok = True
    if not ok:
        problems.append(
            f"{workflow.relative_to(ROOT)}: публикуется {sorted(published)}, а приложение ищет "
            f"{sorted(updater_names)} — обновление перестанет находиться. "
            "Поправьте одно из двух в одном коммите."
        )


def main():
    problems = []
    check_gradle(problems)
    names = check_updater_names(problems)
    check_workflow(problems, names)
    if problems:
        print("контракт APK нарушен:")
        for problem in problems:
            print("  -", problem)
        return 1
    print("в порядке: в релиз идут только ARM-ABI, сплитов и AAB нет, "
          "имя публикуемого APK совпадает с тем, что ищет приложение")
    return 0


if __name__ == "__main__":
    sys.exit(main())
