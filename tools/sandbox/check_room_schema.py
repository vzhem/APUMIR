#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверка @Entity-сущностей Room с SQL внутри миграций.

Зачем: KSP проверяет согласованность сущностей и DAO на этапе компиляции, но SQL
миграций он не видит. Room сверяет получившуюся схему со сущностями только при
открытии базы на устройстве и при расхождении бросает "Migration didn't properly
handle: <table>". До этого инструмента такие расхождения ловились только прогоном
на телефоне.

Сравниваются: состав столбцов, типы, NOT NULL, первичные ключи, ВНЕШНИЕ КЛЮЧИ
(таблица, столбцы, ON UPDATE / ON DELETE) и индексы.

Запуск:
    python3 tools/sandbox/check_room_schema.py android-app/app/src/main/java/.../AppDatabase.kt \
        android-app/app/src/main/java/.../entity
Код возврата 0 - расхождений нет, 1 - есть.
"""

import re
import sys
from pathlib import Path

TYPE_MAP = {
    'String': 'TEXT',
    'Int': 'INTEGER',
    'Long': 'INTEGER',
    'Boolean': 'INTEGER',
    'Short': 'INTEGER',
    'Byte': 'INTEGER',
    'Double': 'REAL',
    'Float': 'REAL',
    'ByteArray': 'BLOB',
}


def strip_kdoc_and_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def join_literals(src):
    """Склеивает соседние строковые литералы Kotlin: "a" + "b" -> "ab"."""
    return re.sub(r'"\s*\+\s*\n?\s*"', '', src)


def parse_entities(entity_dir):
    """Возвращает {tableName: schema} и {className: tableName}."""
    tables = {}
    class_to_table = {}
    for path in sorted(Path(entity_dir).glob('*.kt')):
        raw = path.read_text(encoding='utf-8')
        src = strip_kdoc_and_comments(raw)
        m = re.search(r'@Entity\s*\((.*?)\)\s*\n\s*(?:data\s+)?class\s+(\w+)', src, re.S)
        if not m:
            continue
        head, cls = m.group(1), m.group(2)
        name = re.search(r'tableName\s*=\s*"(\w+)"', head)
        table = name.group(1) if name else cls
        class_to_table[cls] = table

        pk = []
        explicit = re.search(r'primaryKeys\s*=\s*\[(.*?)\]', head, re.S)
        if explicit:
            pk = re.findall(r'"(\w+)"', explicit.group(1))

        fks = []
        for fk in re.finditer(r'ForeignKey\s*\((.*?)\)\s*(?:,|\])', head, re.S):
            body = fk.group(1)
            ent = re.search(r'entity\s*=\s*(\w+)::class', body)
            parent = re.findall(r'"(\w+)"', (re.search(r'parentColumns\s*=\s*\[(.*?)\]', body, re.S) or
                                             _empty()).group(1) if re.search(r'parentColumns\s*=\s*\[(.*?)\]', body, re.S) else '')
            child = re.findall(r'"(\w+)"', (re.search(r'childColumns\s*=\s*\[(.*?)\]', body, re.S) or
                                            _empty()).group(1) if re.search(r'childColumns\s*=\s*\[(.*?)\]', body, re.S) else '')
            on_delete = 'NO ACTION'
            on_update = 'NO ACTION'
            if 'ForeignKey.CASCADE' in body:
                on_delete = 'CASCADE'
            if re.search(r'onUpdate\s*=\s*ForeignKey\.CASCADE', body):
                on_update = 'CASCADE'
            if re.search(r'onDelete\s*=\s*ForeignKey\.SET_NULL', body):
                on_delete = 'SET NULL'
            fks.append({
                'ref': ent.group(1) if ent else '?',
                'parent': parent,
                'child': child,
                'onDelete': on_delete,
                'onUpdate': on_update,
            })

        indices = []
        idx = re.search(r'indices\s*=\s*\[(.*?)\]', head, re.S)
        if idx:
            for one in re.finditer(r'Index\s*\((.*?)\)', idx.group(1), re.S):
                cols = re.findall(r'"(\w+)"', one.group(1))
                unique = 'unique = true' in one.group(1)
                indices.append({'table': table, 'cols': cols, 'unique': unique,
                                'name': 'index_%s_%s' % (table, '_'.join(cols))})

        body_src = src[m.end():]
        cols = {}
        order = []
        pending_default = None
        for line in body_src.split('\n'):
            dm = re.search(r'@ColumnInfo\s*\(\s*defaultValue\s*=\s*"([^"]*)"', line)
            if dm:
                pending_default = dm.group(1)
            pm = re.search(r'\bval\s+(\w+)\s*:\s*([\w.]+)(\?)?', line)
            if not pm:
                continue
            col, ktype, nullable = pm.group(1), pm.group(2).split('.')[-1], pm.group(3) == '?'
            if ktype not in TYPE_MAP:
                continue
            is_pk = '@PrimaryKey' in line
            if is_pk and not explicit:
                pk.append(col)
            cols[col] = {
                'type': TYPE_MAP[ktype],
                'notNull': (not nullable) or is_pk,
                'default': pending_default,
            }
            order.append(col)
            pending_default = None
            if line.rstrip().endswith(')') and ')' in line and 'class' not in line:
                pass
            if re.match(r'^\s*\)\s*$', line):
                break

        tables[table] = {'columns': cols, 'order': order, 'pk': pk, 'fks': fks,
                         'indices': indices, 'file': path.name}
    for t in tables.values():
        for fk in t['fks']:
            fk['refTable'] = class_to_table.get(fk['ref'], fk['ref'])
    return tables, class_to_table


class _Empty:
    group = staticmethod(lambda i: '')


def _empty():
    return _Empty()


def parse_migration(db_file, migration_name):
    src = Path(db_file).read_text(encoding='utf-8')
    i = src.index('val %s' % migration_name)
    seg = src[i:]
    seg = seg[:seg.index('\n        }\n')] if '\n        }\n' in seg else seg
    seg = join_literals(seg)
    seg = seg.replace('"""', '"')

    tables = {}
    for m in re.finditer(r'CREATE TABLE(?: IF NOT EXISTS)? `(\w+)`\s*\((.*?)\)\s*"', seg, re.S):
        name, body = m.group(1), m.group(2)
        cols, pk, fks = {}, [], []
        depth = 0
        parts, cur = [], ''
        for ch in body:
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            if ch == ',' and depth == 0:
                parts.append(cur)
                cur = ''
            else:
                cur += ch
        parts.append(cur)
        for part in parts:
            p = ' '.join(part.split())
            if not p:
                continue
            if p.upper().startswith('PRIMARY KEY'):
                pk = re.findall(r'`(\w+)`', p)
                continue
            if p.upper().startswith('FOREIGN KEY'):
                child = re.findall(r'`(\w+)`', p.split('REFERENCES')[0])
                ref = re.search(r'REFERENCES\s+`(\w+)`\s*\((.*?)\)', p)
                parent = re.findall(r'`(\w+)`', ref.group(2)) if ref else []
                on_del = re.search(r'ON DELETE (\w+(?: \w+)?)', p.upper())
                on_upd = re.search(r'ON UPDATE (\w+(?: \w+)?)', p.upper())
                fks.append({
                    'refTable': ref.group(1) if ref else '?',
                    'parent': parent,
                    'child': child,
                    'onDelete': on_del.group(1) if on_del else 'NO ACTION',
                    'onUpdate': on_upd.group(1) if on_upd else 'NO ACTION',
                })
                continue
            cm = re.match(r'`(\w+)`\s+(\w+)(.*)$', p)
            if cm:
                cols[cm.group(1)] = {
                    'type': cm.group(2).upper(),
                    'notNull': 'NOT NULL' in cm.group(3).upper(),
                    'default': (re.search(r"DEFAULT\s+'?([^'\s]*)'?", cm.group(3), re.I).group(1)
                                if re.search(r'DEFAULT', cm.group(3), re.I) else None),
                }
        tables[name] = {'columns': cols, 'pk': pk, 'fks': fks}

    indices = []
    for m in re.finditer(r'CREATE\s+(UNIQUE\s+)?INDEX(?: IF NOT EXISTS)? `(\w+)` ON `(\w+)`\s*\((.*?)\)', seg):
        indices.append({'name': m.group(2), 'table': m.group(3),
                        'cols': re.findall(r'`(\w+)`', m.group(4)),
                        'unique': bool(m.group(1))})

    alters = {}
    for m in re.finditer(r'ALTER TABLE `(\w+)` ADD COLUMN `(\w+)`\s+([^"]*)', seg):
        table, col, rest = m.group(1), m.group(2), m.group(3)
        dm = re.search(r'DEFAULT\s+(\S+)', rest, re.I)
        default = dm.group(1).strip("'") if dm else None
        alters.setdefault(table, {})[col] = {
            'type': rest.split()[0].upper(),
            'notNull': 'NOT NULL' in rest.upper(),
            'default': default,
        }
    return tables, indices, alters


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    db_file, entity_dir, migration = sys.argv[1], sys.argv[2], sys.argv[3]
    entities, _ = parse_entities(entity_dir)
    mig_tables, mig_indices, mig_alters = parse_migration(db_file, migration)

    problems = 0
    print('миграция %s: таблиц %d, индексов %d, таблиц с ALTER %d' %
          (migration, len(mig_tables), len(mig_indices), len(mig_alters)))
    for name in sorted(mig_tables):
        if name not in entities:
            print('  %s: нет такой @Entity (возможно, таблица создана раньше)' % name)
            continue
        exp, got = entities[name], mig_tables[name]
        errs = []

        for col in exp['order']:
            e = exp['columns'][col]
            g = got['columns'].get(col)
            if g is None:
                errs.append('нет столбца %s' % col)
                continue
            if g['type'] != e['type']:
                errs.append('%s: тип %s, сущность ждёт %s' % (col, g['type'], e['type']))
            if g['notNull'] != e['notNull']:
                errs.append('%s: NOT NULL=%s, сущность ждёт %s' % (col, g['notNull'], e['notNull']))
        for col in got['columns']:
            if col not in exp['columns']:
                errs.append('лишний столбец %s' % col)

        if list(got['pk']) != list(exp['pk']):
            errs.append('PRIMARY KEY %s, сущность ждёт %s' % (got['pk'], exp['pk']))

        def fk_key(f):
            return (tuple(f['child']), f['refTable'], tuple(f['parent']), f['onDelete'], f['onUpdate'])

        exp_fk = {fk_key(f) for f in exp['fks']}
        got_fk = {fk_key(f) for f in got['fks']}
        for missing in sorted(exp_fk - got_fk):
            errs.append('НЕТ ВНЕШНЕГО КЛЮЧА %s -> %s%s ON DELETE %s' %
                        (list(missing[0]), missing[1], list(missing[2]), missing[3]))
        for extra in sorted(got_fk - exp_fk):
            errs.append('лишний внешний ключ %s' % (list(extra),))

        exp_idx = {(i['name'], tuple(i['cols']), i['unique']) for i in exp['indices']}
        got_idx = {(i['name'], tuple(i['cols']), i['unique']) for i in mig_indices if i['table'] == name}
        for missing in sorted(exp_idx - got_idx):
            errs.append('нет индекса %s по %s' % (missing[0], list(missing[1])))

        if errs:
            problems += len(errs)
            print('  %s: РАСХОЖДЕНИЙ %d' % (name, len(errs)))
            for e in errs:
                print('      - %s' % e)
        else:
            print('  %s: совпадает со сущностью' % name)
    # ALTER TABLE ADD COLUMN: таблица существовала до миграции, поэтому полный
    # состав столбцов здесь неизвестен. Сравниваются только добавленные столбцы.
    for table in sorted(mig_alters):
        if table in mig_tables:
            continue
        exp = entities.get(table)
        if exp is None:
            print('  %s: ALTER, но нет такой @Entity' % table)
            problems += 1
            continue
        errs = []
        for col, got in mig_alters[table].items():
            e = exp['columns'].get(col)
            if e is None:
                errs.append('ALTER добавляет %s, которого нет в сущности' % col)
                continue
            if got['type'] != e['type']:
                errs.append('%s: тип %s, сущность ждёт %s' % (col, got['type'], e['type']))
            if got['notNull'] != e['notNull']:
                errs.append('%s: NOT NULL=%s, сущность ждёт %s' % (col, got['notNull'], e['notNull']))
            if e['default'] is not None and got['default'] != e['default']:
                errs.append('%s: DEFAULT %s, сущность объявила %s' % (col, got['default'], e['default']))
        if errs:
            problems += len(errs)
            print('  %s (ALTER): РАСХОЖДЕНИЙ %d' % (table, len(errs)))
            for e in errs:
                print('      - %s' % e)
        else:
            untouched = [c for c in exp['order'] if c not in mig_alters[table]]
            print('  %s (ALTER): добавленные столбцы совпадают; '
                  'без изменений остались %d (%s)' % (table, len(untouched), ', '.join(untouched)))

    print('ИТОГО расхождений: %d' % problems)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
