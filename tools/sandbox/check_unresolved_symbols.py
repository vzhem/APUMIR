#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_unresolved_symbols.py — где в Kotlin-файле вызывается имя, которого нет
ни в импортах, ни в том же пакете, ни в самом файле.

Зачем. В песочнице нет ни JDK, ни Gradle, а грабли записаны в журнале
(docs/AI_COLLABORATION_NOTES.md, выпуск v11.74.171): первые две сборки тега
упали на `:app:compileReleaseKotlin` из-за `ApuAvatar`/`ApuSettingsSectionHeader`
и необъявленного `ApuSettingsDangerColor` — то есть из-за ИМЁН, которых нигде
не было. Ошибку увидели только в логе прогона, которого из песочницы нет.
Инструмент ловит ровно этот класс до тега.

Что делает. Для каждого .kt файла:
  1. собирает импорты (простые имена и «как»-псевдонимы);
  2. собирает объявления всех файлов того же пакета (fun/class/object/val/
     interface/enum/typealias) — в Kotlin одноимённое из своего пакета
     используется без импорта;
  3. собирает объявления внутри самого файла на любом уровне вложенности;
  4. ищет обращения к имени с заглавной буквы («Имя(», «Имя.», «Имя<»), которое
     не стоит после точки (то есть не часть составного пути), и сверяет со
     списком разрешённых. Разрешено: импортировано, объявлено в своём пакете,
     объявлено в этом же файле или входит в AUTO_IMPORTED.

Не разрешается — печатается как ПОДОЗРИТЕЛЬНОЕ. Это не компилятор: типы,
сгенерированные KSP (например, `*_Factory` у Hilt), и имена, приходящие из
библиотек нестандартным путём, инструменту неизвестны. Ложное срабатывание
дешевле пропущенного: подозрительное имя проверяется глазами за секунду.

Чего инструмент НЕ делает: не проверяет типы, сигнатуры, видимость и не
компилирует. Зелёный прогон НЕ РАВЕН зелёной сборке тега.

Запуск:
    python3 tools/sandbox/check_unresolved_symbols.py <каталог_или_файл> [...]
    python3 tools/sandbox/check_unresolved_symbols.py --self-test
Код возврата: 0 — подозрительных имён нет, 1 — есть, 2 — ошибка вызова.
"""
import os
import re
import sys

# Имена, которые в Kotlin на JVM НЕ требуют импорта: kotlin.*,
# kotlin.annotation.*, kotlin.collections.*, kotlin.comparisons.*, kotlin.io.*,
# kotlin.ranges.*, kotlin.sequences.*, kotlin.text.*, kotlin.jvm.* и java.lang.*.
#
# ВАЖНО: сюда намеренно НЕ входят Compose/Material, Room, Dagger/Hilt,
# coroutines, JUnit и Android SDK. В Kotlin они импортируются так же строго,
# как и всё остальное, поэтому «Text» без импорта material3 - такая же ошибка,
# как несуществующий «ApuAvatar» (см. грабли v11.74.171). Если инструмент
# начнёт пропускать нужные имена из этих библиотек, он перестанет ловить то,
# ради чего написан.
AUTO_IMPORTED = {
    # kotlin.*
    'String', 'Int', 'Long', 'Short', 'Byte', 'Boolean', 'Char', 'Float', 'Double',
    'Any', 'Unit', 'Nothing', 'Number', 'Comparable', 'CharSequence', 'Enum',
    'Array', 'IntArray', 'LongArray', 'ShortArray', 'ByteArray', 'FloatArray',
    'DoubleArray', 'BooleanArray', 'CharArray', 'UInt', 'ULong', 'UShort', 'UByte',
    'Pair', 'Triple', 'Result', 'Lazy', 'Sequence', 'Iterator', 'Iterable',
    'List', 'MutableList', 'ArrayList', 'Set', 'MutableSet', 'HashSet',
    'LinkedHashSet', 'Map', 'MutableMap', 'HashMap', 'LinkedHashMap',
    'Collection', 'MutableCollection', 'StringBuilder', 'Regex', 'MatchResult',
    'Error', 'Exception', 'Throwable', 'RuntimeException', 'IllegalStateException',
    'IllegalArgumentException', 'IndexOutOfBoundsException', 'NumberFormatException',
    'UnsupportedOperationException', 'ArithmeticException', 'ClassCastException',
    'ConcurrentModificationException', 'NoSuchElementException', 'AssertionError',
    'CloneNotSupportedException', 'InterruptedException',
    'Deprecated', 'Suppress', 'OptIn', 'RequiresOptIn', 'Experimental',
    'Volatile', 'Transient', 'Synchronized', 'JvmStatic', 'JvmField', 'JvmName',
    'JvmOverloads', 'JvmSuppressWildcards', 'Throws', 'Strictfp',
    'Target', 'Retention', 'MustBeDocumented', 'Repeatable', 'AnnotationRetention',
    'AnnotationTarget', 'DslMarker', 'PublishedApi', 'UnsafeVariance',
    'ParameterName', 'ExtensionFunctionType', 'ContextFunctionTypeParams',
    'SinceKotlin', 'Suppress', 'UseExperimental', 'WasExperimental',
    # java.lang.* (то, что Kotlin подставляет сам)
    'Object', 'Class', 'Void', 'System', 'Math', 'Thread', 'Runnable', 'Override',
    'Integer', 'Character', 'Iterable', 'Comparable', 'StackTraceElement',
    'Cloneable', 'AutoCloseable', 'Process', 'ProcessBuilder', 'SecurityException',
    'IntRange', 'LongRange', 'CharRange', 'ClosedRange', 'ClosedFloatingPointRange',
    'Companion', 'Enum', 'Comparable',
    # kotlin.text.* / kotlin.collections.* (тоже без импорта)
    'Charsets', 'RegexOption', 'MatchNamedGroupCollection', 'MatchGroupCollection',
    'MatchGroup', 'ArrayDeque', 'Comparator',
}

# `*` обязателен в шаблоне: `import androidx.compose.foundation.layout.*` иначе
# не распознаётся вовсе, и каждый Compose-файл с таким импортом даёт дюжину строк.
IMPORT_RE = re.compile(r'^\s*import\s+([A-Za-z0-9_.]+(?:\.\*)?)(?:\s+as\s+([A-Za-z0-9_]+))?\s*$')
PACKAGE_RE = re.compile(r'^\s*package\s+([A-Za-z0-9_.]+)\s*$')
DECL_RE = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public|private|internal|protected|abstract|open|final|sealed|data|inner|value|annotation|enum|suspend|inline|expect|actual|const|external|operator|infix|vararg|override|tailrec|noinline|crossinline|lateinit|\s)*'
    r'(?:fun|class|interface|object|val|var|typealias|enum\s+class|annotation\s+class|data\s+class|sealed\s+class|abstract\s+class|open\s+class|value\s+class)\s+'
    r'(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)'
)
# Обращение к имени с заглавной буквы: вызов или доступ к члену.
USE_RE = re.compile(r'(?<![.\w$])([A-Z][A-Za-z0-9_]*)\s*(?=[.(<])')
# р251: два класса ошибок, которые USE_RE пропускает, а тег v11.74.175 на них
# упал в :app:compileReleaseKotlin (прогон 37305997197):
# 1) голая ALL_CAPS-константа как значение (`PENDING_REPLIES` без объявления) -
#    после имени нет `(`, и USE_RE молчит;
# 2) член-иконка без импорта (`Icons.AutoMirrored.Filled.Reply`) - имя стоит
#    после точки, и USE_RE молчит тоже.
# Константа: ЗАГЛАВНЫЕ с хотя бы одним подчёркиванием (типа PENDING_REPLIES);
# одиночные заглавные (дженерики `T`) и CamelCase-типы сюда не попадают.
CONST_USE_RE = re.compile(r'(?<![.\w$])([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\b')
ICONS_USE_RE = re.compile(
    r'\bIcons\.(AutoMirrored\.)?(?:Filled|Default|Outlined)\.([A-Z][A-Za-z0-9_]*)')
ICONS_WILDCARD_PREFIX = 'androidx.compose.material.icons'
# Константы, которые Kotlin разрешает через наследование от базовых классов
# Android (Service наследует ContextWrapper, Activity - ContextThemeWrapper):
# в subclass они пишутся голыми без импорта, и инструмент о наследовании не
# знает. Только реально существующие константы Service/Context - калибровка
# по живому коду (прогон р251: 7 подозрений, все из этого семейства).
INHERITED_ANDROID_CONSTS = {
    'START_STICKY', 'START_NOT_STICKY', 'START_REDELIVER_INTENT',
    'STOP_FOREGROUND_REMOVE', 'STOP_FOREGROUND_KEEP',
    'MODE_PRIVATE', 'MODE_APPEND',
    'POWER_SERVICE', 'WIFI_SERVICE', 'CONNECTIVITY_SERVICE', 'NOTIFICATION_SERVICE',
    'DOWNLOAD_SERVICE', 'CLIPBOARD_SERVICE', 'ALARM_SERVICE', 'AUDIO_SERVICE',
    'CAMERA_SERVICE', 'LOCATION_SERVICE', 'SENSOR_SERVICE', 'VIBRATOR_SERVICE',
    'WINDOW_SERVICE', 'ACTIVITY_SERVICE', 'TELEPHONY_SERVICE', 'INPUT_METHOD_SERVICE',
    'LAYOUT_INFLATER_SERVICE', 'STORAGE_SERVICE', 'USB_SERVICE', 'NFC_SERVICE',
}


def strip_noise(text):
    """Убрать строковые и символьные литералы и комментарии, чтобы не ловить
    имена внутри текста («${...}» интерполяция остаётся — это код)."""
    out = []
    i = 0
    n = len(text)
    state = 'code'
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state == 'code':
            if ch == '/' and nxt == '/':
                state = 'line'
                i += 2
                continue
            if ch == '/' and nxt == '*':
                state = 'block'
                i += 2
                continue
            if ch == '"':
                # Тройная кавычка: сырой текст, внутри ${...} - код.
                if text.startswith('"""', i):
                    state = 'raw'
                    out.append('"""')
                    i += 3
                    continue
                state = 'str'
                i += 1
                continue
            if ch == "'":
                state = 'chr'
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == 'line':
            if ch == '\n':
                state = 'code'
                out.append(ch)
            i += 1
        elif state == 'block':
            if ch == '*' and nxt == '/':
                state = 'code'
                i += 2
                continue
            if ch == '\n':
                out.append(ch)
            i += 1
        elif state == 'str':
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                state = 'code'
            elif ch == '$' and nxt == '{':
                # Интерполяция — код внутри строки.
                depth = 1
                j = i + 2
                inner = []
                while j < n and depth:
                    if text[j] == '{':
                        depth += 1
                    elif text[j] == '}':
                        depth -= 1
                        if not depth:
                            break
                    inner.append(text[j])
                    j += 1
                out.append(''.join(inner))
                i = j + 1
                continue
            i += 1
        elif state == 'raw':
            if text.startswith('"""', i):
                state = 'code'
                out.append('"""')
                i += 3
                continue
            if ch == '$' and nxt == '{':
                depth = 1
                j = i + 2
                inner = []
                while j < n and depth:
                    if text[j] == '{':
                        depth += 1
                    elif text[j] == '}':
                        depth -= 1
                        if not depth:
                            break
                    inner.append(text[j])
                    j += 1
                out.append(''.join(inner))
                i = j + 1
                continue
            i += 1
        elif state == 'chr':
            if ch == '\\':
                i += 2
                continue
            if ch == "'":
                state = 'code'
            i += 1
    return ''.join(out)


ENUM_RE = re.compile(r'\benum\s+class\s+([A-Za-z_][A-Za-z0-9_]*)')
ENTRY_RE = re.compile(r'([A-Z][A-Za-z0-9_]*)')


def enum_entries(text, start):
    """Записи `enum class`: от первой `{` до `;` или до закрывающей скобки.

    Без этого каждое обращение к записи внутри своего же файла (`LIGHT`,
    `OFFER`, `Personal`) выглядело бы как неизвестное имя.
    """
    brace = text.find('{', start)
    if brace < 0:
        return set()
    depth = 0
    i = brace
    body = []
    while i < len(text):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                break
        elif ch == ';' and depth == 1:
            break
        body.append(ch)
        i += 1
    return set(ENTRY_RE.findall(''.join(body)))


def declarations(text):
    """Все имена, объявленные в файле (на любом уровне вложенности)."""
    names = set()
    for line in text.split('\n'):
        m = DECL_RE.match(line)
        if m:
            names.add(m.group(1))
    for m in ENUM_RE.finditer(text):
        names.add(m.group(1))
        names |= enum_entries(text, m.end())
    # Композиционные локальные объявления вида `val x by remember { ... }`
    # инструментом не нужны: они со строчной буквы.
    return names


def imports_of(text):
    """Простые имена из импортов и пакеты, импортированные целиком (`foo.bar.*`)."""
    simple = set()
    wildcards = set()
    for line in text.split('\n'):
        m = IMPORT_RE.match(line)
        if not m:
            continue
        path, alias = m.group(1), m.group(2)
        if alias is None and path.endswith('.*'):
            wildcards.add(path[:-2])
            continue
        simple.add(alias if alias else path.rsplit('.', 1)[-1])
    return simple, wildcards


def universe_root(path):
    """Корень, из которого берётся состав пакетов.

    Проверять один файл бессмысленно в отрыве от его пакета: в Kotlin
    одноимённое из своего пакета используется без импорта. Поэтому даже для
    одного файла состав пакетов собираем со всего `.../src/main/java`.
    """
    if os.path.isfile(path):
        path = os.path.dirname(os.path.abspath(path))
    else:
        path = os.path.abspath(path)
    parts = path.split(os.sep)
    for i in range(len(parts) - 1, -1, -1):
        if parts[i] == 'java':
            found = os.sep.join(parts[:i + 1])
            # Тесты (`src/test/java`, `src/androidTest/java`) обращаются к
            # классам `src/main/java` в тех же пакетах без импорта, поэтому
            # основной источник входит в состав пакетов вместе с ними.
            if i >= 2 and parts[i - 1] in ('test', 'androidTest'):
                main = os.sep.join(parts[:i - 1] + ['main', 'java'])
                if os.path.isdir(main):
                    return [main, found]
            return [found]
    return [path]


def collect(root_dirs):
    """Пакет -> множество объявленных имён; плюс список проверяемых файлов."""
    files = []
    universes = set()
    for root in root_dirs:
        universes.update(universe_root(root))
        if os.path.isfile(root):
            if root.endswith('.kt'):
                files.append(root)
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in
                           {'build', '.gradle', 'generated', 'test', 'androidTest'}]
            for name in sorted(filenames):
                if name.endswith('.kt'):
                    files.append(os.path.join(dirpath, name))
    packages = {}
    universe_files = []
    for root in sorted(universes):
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in
                           {'build', '.gradle', 'generated', 'test', 'androidTest'}]
            for name in filenames:
                if name.endswith('.kt'):
                    universe_files.append(os.path.join(dirpath, name))
    for path in universe_files:
        try:
            raw = open(path, encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        pkg = None
        for line in raw.split('\n')[:20]:
            m = PACKAGE_RE.match(line)
            if m:
                pkg = m.group(1)
                break
        if pkg is None:
            continue
        packages.setdefault(pkg, set()).update(declarations(strip_noise(raw)))
    return files, packages


def check_file(path, packages, project_names):
    raw = open(path, encoding='utf-8', errors='replace').read()
    clean = strip_noise(raw)
    pkg = None
    for line in raw.split('\n')[:20]:
        m = PACKAGE_RE.match(line)
        if m:
            pkg = m.group(1)
            break
    imported, wildcards = imports_of(raw)
    allowed = set(imported)
    allowed |= packages.get(pkg, set())
    allowed |= declarations(clean)
    allowed |= AUTO_IMPORTED
    # Пакеты, импортированные целиком и не принадлежащие проекту: перечислить
    # их состав из песочницы нельзя.
    external_wildcards = {w for w in wildcards if not w.startswith('com.vladimir.messenger')}
    suspects = []
    seen = set()
    for m in USE_RE.finditer(clean):
        name = m.group(1)
        if name in allowed or name in seen:
            continue
        if name not in project_names and external_wildcards:
            # Имя из чужой библиотеки, а файл импортирует её целиком: ни
            # разрешить, ни опровергнуть отсюда нельзя. Молчим — иначе каждый
            # Compose-файл с `layout.*` давал бы дюжину строк шума.
            seen.add(name)
            continue
        # Составной путь: `com.vladimir...` — имя до точки маленькое, сюда не
        # попадает вовсе. Остаются только «голые» обращения.
        seen.add(name)
        line_no = clean.count('\n', 0, m.start()) + 1
        suspects.append((name, line_no))
    # р251, класс 1: голая константа без объявления/импорта.
    for m in CONST_USE_RE.finditer(clean):
        name = m.group(1)
        if name in INHERITED_ANDROID_CONSTS:
            continue
        if name in allowed or name in seen:
            continue
        if name not in project_names and external_wildcards:
            continue
        seen.add(name)
        suspects.append((name, clean.count('\n', 0, m.start()) + 1))
    # р251, класс 2: иконка Material без импорта своего пакета.
    icons_wild = any(w == ICONS_WILDCARD_PREFIX or w.startswith(ICONS_WILDCARD_PREFIX + '.')
                     for w in wildcards)
    for m in ICONS_USE_RE.finditer(clean):
        name = m.group(2)
        if name in imported or name in seen or icons_wild:
            continue
        seen.add(name)
        suspects.append((name, clean.count('\n', 0, m.start()) + 1))
    return suspects


def self_test():
    sample = '''package a.b
import androidx.compose.material3.Text
import androidx.compose.material3.Text as T
import com.x.Y
val Z = 1
fun f() {
    Text("ok"); T("ok"); Y.go(); Z.go(); Unknown.go()
    val s = "Text Unknown Inside"
    val c = MISSING_CONST + 1
    val ic = Icons.AutoMirrored.Filled.MissingIcon
    val t = "${Text} and Unknown"
    /* Text Unknown block */
    // Text Unknown line
    val raw = """
    Text Unknown raw
    """
}
'''
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'S.kt')
        open(p, 'w', encoding='utf-8').write(sample)
        files, packages = collect([p])
        universe = set().union(*packages.values()) if packages else set()
        got = dict(check_file(p, packages, universe))
    # Unknown встречается в коде (строка 7) и в интерполяции; в литералах и
    # комментариях инструмент его видеть не должен.
    assert 'Unknown' in got, got
    assert got['Unknown'] == 7, got
    assert 'Y' not in got and 'Text' not in got and 'T' not in got and 'Z' not in got, got
    # р251: голая константа и иконка без импорта ловятся; строка c - 9, ic - 10.
    assert got.get('MISSING_CONST') == 9, got
    assert got.get('MissingIcon') == 10, got
    print('самопроверка: OK (литералы/комментарии не считаются, импорты и '
          'объявления разрешаются, константы и иконки без импорта ловятся)')
    return 0


def main(argv):
    if not argv or argv[0] in ('-h', '--help'):
        print(__doc__)
        return 2
    if argv[0] == '--self-test':
        return self_test()
    files, packages = collect(argv)
    if not files:
        print('не найдено ни одного .kt файла')
        return 2
    total = 0
    universe = set()
    for names in packages.values():
        universe |= names
    for path in sorted(files):
        suspects = check_file(path, packages, universe)
        if not suspects:
            continue
        total += len(suspects)
        print('ПОДОЗРИТЕЛЬНО  %s' % path)
        for name, line_no in sorted(suspects, key=lambda item: item[1]):
            print('        строка %-5d %s' % (line_no, name))
    print('проверено файлов: %d, подозрительных имён: %d' % (len(files), total))
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
