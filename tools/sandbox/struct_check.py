#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
struct_check.py — структурный лексер-проверщик для песочницы, где нет ни
cargo, ни JDK/Gradle. Языки: Kotlin, Rust, PowerShell.

Зачем: docs/AI_HANDOFF.md прямо запрещает «грубый brace-чекер по кавычкам» —
он врёт на апострофах в KDoc. Здесь полноценный лексер: строки, raw-строки,
символьные литералы, вложенные блочные комментарии, шаблоны строк Kotlin
`${...}` и PowerShell-интерполяция `$(...)`, here-строки `@"..."@`.

Для .ps1 дополнительно проверяется ASCII: по журналу (запись 2026-08-24)
вставка многострочного блока в PS 5.1 падала из-за мусорной строки, поэтому
скрипты держат ASCII-only. Не-ASCII в комментариях — предупреждение,
не-ASCII в коде или строках — ошибка.

Чего инструмент НЕ делает: не проверяет типы, не компилирует, не заменяет
гейт. Ограничение по Rust: `'a;` (lifetime там, где нужен символьный литерал)
он ошибкой не считает — это задача компилятора, а не проверка скобок.
Зелёный прогон здесь НЕ РАВЕН зелёному `cargo test` / `:app:testDebugUnitTest`.

Запуск:
    python3 tools/sandbox/struct_check.py <файл_или_каталог> [...]
    python3 tools/sandbox/struct_check.py --self-test
    python3 tools/sandbox/struct_check.py --ascii=strict scripts\new.ps1
Код возврата: 0 — чисто, 1 — найдены расхождения, 2 — ошибка вызова.
"""

from __future__ import annotations

import os
import sys

OPEN = {"{": "}", "(": ")", "[": "]"}
CLOSE = {"}": "{", ")": "(", "]": "["}
KIND_NAME = {"{": "braces", "(": "parens", "[": "brackets"}
IDENT = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
LEX_MODES = ("string", "raw", "char", "comment", "sq")


class Frame:
    """Элемент стека: открытая скобка или лексический режим."""

    __slots__ = ("kind", "line", "quote")

    def __init__(self, kind, line, quote=""):
        self.kind = kind  # '{' '(' '[' | string | raw | sq | char | comment
        self.line = line
        self.quote = quote


# ═══════════════════════════════════════════════════════════════════════════
#  Kotlin и Rust — общий лексер
# ═══════════════════════════════════════════════════════════════════════════
def _lex(src, lang):
    stack = []
    errors = []
    counts = {"braces": 0, "parens": 0, "brackets": 0,
              "strings": 0, "raw_strings": 0, "comments": 0}
    i = 0
    n = len(src)
    line = 1
    block_depth = 0  # /* */ вложенные и в Kotlin, и в Rust

    def cur_mode():
        return stack[-1].kind if stack else "code"

    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        mode = cur_mode()

        if c == "\n":
            line += 1
            i += 1
            continue

        # ── строка в двойных кавычках ──────────────────────────────────────
        if mode == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                stack.pop()
                counts["strings"] += 1
                i += 1
                continue
            if lang == "kotlin" and c == "$" and nxt == "{":
                stack.append(Frame("{", line))  # шаблон ${ ... }
                i += 2
                continue
            i += 1
            continue

        # ── raw-строка ─────────────────────────────────────────────────────
        if mode == "raw":
            if lang == "kotlin":
                if src.startswith('"""', i):
                    stack.pop()
                    counts["raw_strings"] += 1
                    i += 3
                    continue
                if c == "$" and nxt == "{":
                    stack.append(Frame("{", line))
                    i += 2
                    continue
                i += 1
                continue
            term = '"' + "#" * int(stack[-1].quote)  # rust: r#"..."#
            if src.startswith(term, i):
                stack.pop()
                counts["raw_strings"] += 1
                i += len(term)
                continue
            i += 1
            continue

        # ── символьный литерал ─────────────────────────────────────────────
        if mode == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                stack.pop()
                i += 1
                continue
            i += 1
            continue

        # ── блочный комментарий ────────────────────────────────────────────
        if mode == "comment":
            if src.startswith("/*", i):
                block_depth += 1
                i += 2
                continue
            if src.startswith("*/", i):
                block_depth -= 1
                counts["comments"] += 1
                if block_depth == 0:
                    stack.pop()
                i += 2
                continue
            i += 1
            continue

        # ── режим кода ─────────────────────────────────────────────────────
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
            continue

        if src.startswith("/*", i):
            block_depth = 1
            stack.append(Frame("comment", line))
            i += 2
            continue

        if lang == "rust":  # r"..." r#"..."# b"..." br"..."
            j = i
            if src[j] == "b":
                j += 1
            if j < n and src[j] == "r":
                j += 1
                hashes = 0
                while j < n and src[j] == "#":
                    hashes += 1
                    j += 1
                if j < n and src[j] == '"':
                    stack.append(Frame("raw", line, str(hashes)))
                    i = j + 1
                    continue

        if c == '"':
            if lang == "kotlin" and src.startswith('"""', i):
                stack.append(Frame("raw", line))
                i += 3
                continue
            stack.append(Frame("string", line))
            i += 1
            continue

        if lang == "kotlin" and c == "`":  # идентификаторы в обратных кавычках
            i += 1
            while i < n and src[i] != "`":
                if src[i] == "\n":
                    line += 1
                i += 1
            i += 1
            continue

        # Символьный литерал vs lifetime в Rust — главная ловушка.
        #   '\''      — escape, закрывается последней кавычкой;
        #   'a' / '}' — ровно один символ и закрывающая кавычка;
        #   'a        — lifetime/метка, скобок не несёт.
        if c == "'":
            if lang == "rust":
                j = i + 1
                if j < n and src[j] == "\\":
                    k = j
                    while k < n:
                        if src[k] == "\\":
                            k += 2
                        elif src[k] == "'":
                            break
                        else:
                            k += 1
                    if k < n and src[k] == "'":
                        i = k + 1
                        continue
                    stack.append(Frame("char", line))
                    i = k
                    continue
                if j < n and src[j] != "'" and j + 1 < n and src[j + 1] == "'":
                    i = j + 2
                    continue
                k = j
                while k < n and src[k] in IDENT:
                    k += 1
                i = k if k > j else i + 1
                continue
            stack.append(Frame("char", line))
            i += 1
            continue

        if c in OPEN:
            stack.append(Frame(c, line))
            i += 1
            continue

        if c in CLOSE:
            if not stack:
                errors.append("строка %d: закрывающая '%s' без открывающей" % (line, c))
                i += 1
                continue
            top = stack[-1]
            if top.kind in LEX_MODES:
                errors.append("строка %d: '%s' внутри незакрытого лексического "
                              "элемента (открыт на строке %d)" % (line, c, top.line))
                i += 1
                continue
            if OPEN[top.kind] != c:
                errors.append("строка %d: ожидалось '%s' (открыто на строке %d), "
                              "встречено '%s'" % (line, OPEN[top.kind], top.line, c))
                stack.pop()
                i += 1
                continue
            stack.pop()
            counts[KIND_NAME[top.kind]] += 1
            i += 1
            continue

        i += 1

    for fr in stack:
        if fr.kind in ("string", "raw", "char", "sq"):
            errors.append("строка %d: незакрытый литерал (%s)" % (fr.line, fr.kind))
        elif fr.kind == "comment":
            errors.append("строка %d: незакрытый блочный комментарий" % fr.line)
        else:
            errors.append("строка %d: незакрытая '%s'" % (fr.line, fr.kind))

    return errors, counts, None


# ═══════════════════════════════════════════════════════════════════════════
#  PowerShell
# ═══════════════════════════════════════════════════════════════════════════
def _lex_ps(src):
    stack = []
    errors = []
    counts = {"braces": 0, "parens": 0, "brackets": 0,
              "strings": 0, "raw_strings": 0, "comments": 0}
    in_comment = bytearray(len(src))  # 1 = символ относится к комментарию
    i = 0
    n = len(src)
    line = 1

    def cur_mode():
        return stack[-1].kind if stack else "code"

    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        mode = cur_mode()

        if c == "\n":
            line += 1
            i += 1
            continue

        # ── одинарная строка: '' = кавычка, интерполяции нет ───────────────
        if mode == "sq":
            if c == "'" and nxt == "'":
                i += 2
                continue
            if c == "'":
                stack.pop()
                counts["strings"] += 1
                i += 1
                continue
            i += 1
            continue

        # ── двойная строка: ` = escape, $( ) и ${ } = вкрапления кода ──────
        if mode == "string":
            if c == "`":
                i += 2
                continue
            if c == '"':
                stack.pop()
                counts["strings"] += 1
                i += 1
                continue
            if c == "$" and nxt and nxt in "({":
                stack.append(Frame(nxt, line))
                i += 2
                continue
            i += 1
            continue

        # ── here-строка: @" ... "@ и @' ... '@, закрывающая в начале строки ─
        if mode == "raw":
            term = stack[-1].quote
            if src.startswith(term, i) and (i == 0 or src[i - 1] == "\n"):
                stack.pop()
                counts["raw_strings"] += 1
                i += len(term)
                continue
            i += 1
            continue

        # ── блочный комментарий <# ... #> (не вложенный) ───────────────────
        if mode == "comment":
            in_comment[i] = 1
            if src.startswith("#>", i):
                in_comment[i + 1] = 1
                counts["comments"] += 1
                stack.pop()
                i += 2
                continue
            i += 1
            continue

        # ── режим кода ─────────────────────────────────────────────────────
        if src.startswith("<#", i):
            stack.append(Frame("comment", line))
            in_comment[i] = 1
            in_comment[i + 1] = 1
            i += 2
            continue

        if c == "#":
            while i < n and src[i] != "\n":
                in_comment[i] = 1
                i += 1
            counts["comments"] += 1
            continue

        if c == "@" and nxt in ('"', "'"):
            # here-строка, если сразу после кавычки конец строки
            k = i + 2
            if k < n and src[k] == "\r":
                k += 1
            if k < n and src[k] == "\n":
                term = ('"@' if nxt == '"' else "'@")
                stack.append(Frame("raw", line, term))
                i += 2
                continue

        if c == "'":
            stack.append(Frame("sq", line))
            i += 1
            continue

        if c == '"':
            stack.append(Frame("string", line))
            i += 1
            continue

        if c in OPEN:
            stack.append(Frame(c, line))
            i += 1
            continue

        if c in CLOSE:
            if not stack:
                errors.append("строка %d: закрывающая '%s' без открывающей" % (line, c))
                i += 1
                continue
            top = stack[-1]
            if top.kind in LEX_MODES:
                errors.append("строка %d: '%s' внутри незакрытого лексического "
                              "элемента (открыт на строке %d)" % (line, c, top.line))
                i += 1
                continue
            if OPEN[top.kind] != c:
                errors.append("строка %d: ожидалось '%s' (открыто на строке %d), "
                              "встречено '%s'" % (line, OPEN[top.kind], top.line, c))
                stack.pop()
                i += 1
                continue
            stack.pop()
            counts[KIND_NAME[top.kind]] += 1
            i += 1
            continue

        i += 1

    for fr in stack:
        if fr.kind in ("string", "sq"):
            errors.append("строка %d: незакрытая строка" % fr.line)
        elif fr.kind == "raw":
            errors.append("строка %d: незакрытая here-строка (нет %s в начале строки)"
                          % (fr.line, fr.quote))
        elif fr.kind == "comment":
            errors.append("строка %d: незакрытый комментарий <#" % fr.line)
        else:
            errors.append("строка %d: незакрытая '%s'" % (fr.line, fr.kind))

    # ── ASCII-анализ: отдельно комментарии и код ───────────────────────────
    code_lines = []
    comment_lines = []
    lines = src.split("\n")
    offset = 0
    for ln, text in enumerate(lines, start=1):
        if any(ord(ch) > 127 for ch in text):
            non_comment = any(ord(text[k]) > 127
                              and not in_comment[offset + k]
                              for k in range(len(text))
                              if offset + k < len(in_comment))
            (code_lines if non_comment else comment_lines).append(ln)
        offset += len(text) + 1

    ascii_info = {"code": code_lines, "comments": comment_lines}
    return errors, counts, ascii_info


# ═══════════════════════════════════════════════════════════════════════════
#  Диспетчер
# ═══════════════════════════════════════════════════════════════════════════
def check_file(path):
    ext = os.path.splitext(path)[1].lower()
    lang = {".kt": "kotlin", ".kts": "kotlin", ".rs": "rust",
            ".ps1": "powershell", ".psm1": "powershell"}.get(ext)
    if lang is None:
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        src = fh.read()
    if lang == "powershell":
        errors, counts, ascii_info = _lex_ps(src)
    else:
        errors, counts, ascii_info = _lex(src, lang)
    return lang, errors, counts, ascii_info


def walk(root):
    if os.path.isfile(root):
        yield root
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in (".git", "build", "target", ".gradle", ".kotlin")]
        for fn in sorted(filenames):
            yield os.path.join(dirpath, fn)


# ═══════════════════════════════════════════════════════════════════════════
#  Самотест: доказывает, что лексер не «всегда зелёный»
# ═══════════════════════════════════════════════════════════════════════════
SELFTEST = [
    ("kdoc-apostrophe", "kotlin",
     "/** Don't use it. It's fine. */\nclass A {\n  val s = \"a{b\"\n}\n", False),
    ("nested-block-comment", "kotlin",
     "/* outer /* inner */ still comment */\nclass A {}\n", False),
    ("template-braces", "kotlin",
     "val s = \"x ${ if (a) { 1 } else { 2 } } y\"\nclass A {}\n", False),
    ("nested-string-in-template", "kotlin",
     "val s = \"${ mapOf(\"k\" to 1).size }\"\nclass A {}\n", False),
    ("raw-string", "kotlin",
     "val s = \"\"\"a } { b\"\"\"\nclass A {}\n", False),
    ("backtick-ident", "kotlin",
     "class A { fun `my test`() { } }\n", False),
    ("unclosed-brace", "kotlin",
     "class A {\n  fun f() {\n}\n", True),
    ("unclosed-string", "kotlin",
     "class A { val s = \"abc }\n", True),
    ("unclosed-template", "kotlin",
     "class A { val s = \"${ x }\"\n", True),
    ("stray-close", "kotlin",
     "class A { } }\n", True),
    ("rust-lifetimes", "rust",
     "fn f<'a, 'b>(x: &'a str) -> &'b str { x }\n", False),
    ("rust-char-vs-lifetime", "rust",
     "fn f(x: &str) -> char { let c = '}'; let d = '\\''; c }\n", False),
    ("rust-raw-string", "rust",
     "fn f() { let s = r#\"a } { b\"#; let t = r\"}\"; }\n", False),
    ("rust-byte-raw", "rust",
     "fn f() { let s = br#\"}\"#; }\n", False),
    ("rust-nested-comment", "rust",
     "/* a /* b */ c */\nfn f() {}\n", False),
    ("rust-unclosed-fn", "rust",
     "fn f() {\n  if true {\n}\n", True),
    ("rust-unclosed-raw", "rust",
     "fn f() { let s = r#\"abc; }\n", True),
    ("rust-stray-paren", "rust",
     "fn f() { }\n)\n", True),
    ("rust-char-is-close-brace", "rust",
     "fn f() -> char { let c = '}'; let d = '{'; c }\n", False),
    ("rust-escaped-quote-char", "rust",
     "fn f() -> char { let q = '\\''; q }\n", False),
    ("rust-lifetime-generic", "rust",
     "struct S<'a> { r: &'a str }\nimpl<'a, 'b> S<'a> { fn g(&self) -> &'b str where 'a: 'b { self.r } }\n",
     False),
]

SELFTEST_PS = [
    ("ps-hash-comment-brace", "# this has a } brace and ' quote\nfunction F { }\n", False),
    ("ps-block-comment", "<# multi\n line } #>\nfunction F { }\n", False),
    ("ps-single-quote", "$a = 'it''s a } test'\nfunction F { }\n", False),
    ("ps-double-interpolation",
     "$a = \"count $( if ($x) { 1 } else { 2 } ) end\"\nfunction F { }\n", False),
    ("ps-backtick-escape", "$a = \"quote `\" inside\"\nfunction F { }\n", False),
    ("ps-here-string", "$a = @\"\n text with } and \" quote\n\"@\nfunction F { }\n", False),
    ("ps-here-string-literal", "$a = @'\n text with } and ' quote\n'@\nfunction F { }\n", False),
    ("ps-hash-in-string", "$a = \"not # a comment }\"\nfunction F { }\n", False),
    ("ps-unclosed-block", "function F {\n  if ($x) {\n}\n", True),
    ("ps-unclosed-string", "$a = \"abc }\nfunction F { }\n", True),
    ("ps-unclosed-here", "$a = @\"\n text\nfunction F { }\n", True),
    ("ps-stray-close", "function F { }\n}\n", True),
]


def self_test():
    bad = 0
    total = 0
    for name, lang, src, should_fail in SELFTEST:
        errors, _, _ = _lex(src, lang)
        got = bool(errors)
        total += 1
        ok = got == should_fail
        if not ok:
            bad += 1
        print("%-6s %-28s ожидали_ошибку=%-5s получили=%-5s %s"
              % ("OK" if ok else "ПРОВАЛ", name, should_fail, got,
                 errors[0] if errors else "-"))
    for name, src, should_fail in SELFTEST_PS:
        errors, _, _ = _lex_ps(src)
        got = bool(errors)
        total += 1
        ok = got == should_fail
        if not ok:
            bad += 1
        print("%-6s %-28s ожидали_ошибку=%-5s получили=%-5s %s"
              % ("OK" if ok else "ПРОВАЛ", name, should_fail, got,
                 errors[0] if errors else "-"))

    # ASCII-классификатор тоже проверяем
    ascii_cases = [
        ("# кириллица в комментарии\n$x = 1\n", 0, 1),
        ("$x = 'строка'\n", 1, 0),
        ("$x = 1 # хвост\n$y = 'код'\n", 1, 1),
    ]
    for src, want_code, want_comments in ascii_cases:
        _, _, info = _lex_ps(src)
        got_code = len(info["code"])
        got_comments = len(info["comments"])
        total += 1
        ok = got_code == want_code and got_comments == want_comments
        if not ok:
            bad += 1
        print("%-6s %-28s ожидали код=%d комм=%d, получили код=%d комм=%d"
              % ("OK" if ok else "ПРОВАЛ", "ps-ascii-classifier",
                 want_code, want_comments, got_code, got_comments))

    print()
    print("самотест: %d/%d пройдено" % (total - bad, total))
    return 1 if bad else 0


def main(argv):
    raw = list(argv[1:])
    if not raw:
        print(__doc__)
        return 2
    if raw[0] == "--self-test":
        return self_test()

    # --ascii=strict  — не-ASCII в коде/строках считается ошибкой (для нового
    #                   скрипта, который пойдёт на Windows владельца);
    # --ascii=warn    — только предупреждение (по умолчанию, чтобы архивные
    #                   скрипты прошлых релизов не рвали общий прогон);
    # --ascii=off     — не проверять.
    ascii_mode = "warn"
    args = []
    for a in raw:
        if a.startswith("--ascii="):
            ascii_mode = a.split("=", 1)[1]
            if ascii_mode not in ("strict", "warn", "off"):
                print("неверное значение --ascii: %s" % ascii_mode)
                return 2
        else:
            args.append(a)
    if not args:
        print("не указано, что проверять")
        return 2

    checked = 0
    problems = 0
    warns = 0
    for root in args:
        for path in walk(root):
            res = check_file(path)
            if res is None:
                continue
            lang, errors, counts, ascii_info = res
            checked += 1
            if errors:
                problems += 1
                print("ОШИБКА  %s  [%s]" % (path, lang))
                for e in errors[:10]:
                    print("        %s" % e)
                if len(errors) > 10:
                    print("        ... и ещё %d" % (len(errors) - 10))
            if ascii_info and ascii_mode != "off":
                if ascii_info["code"]:
                    tag = "ОШИБКА" if ascii_mode == "strict" else "ВНИМАНИЕ"
                    if ascii_mode == "strict":
                        problems += 1
                    else:
                        warns += 1
                    print("%s %s [ascii] не-ASCII в коде/строках, строки: %s"
                          % (tag, path, ascii_info["code"][:12]))
                if ascii_info["comments"]:
                    warns += 1
                    print("ВНИМАНИЕ %s [ascii] не-ASCII в комментариях, строки: %s"
                         % (path, ascii_info["comments"][:12]))

    print()
    print("проверено файлов: %d, с ошибками: %d, предупреждений: %d (ascii=%s)"
          % (checked, problems, warns, ascii_mode))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
