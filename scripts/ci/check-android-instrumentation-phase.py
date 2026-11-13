#!/usr/bin/env python3
"""Verify one raw AndroidJUnitRunner invocation and emit one checked JUnit case.

`am instrument` may exit zero even when an assertion failed, was skipped, the
process crashed, or no test was selected. Never infer a pass from adb alone.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = 'com.vladimir.messenger.service.NotificationPermissionRecoveryInstrumentedTest'
METHODS = {
    'disposableProfileSnapshotsTheOriginalPermission',
    'permissionIsGrantedBeforeRevocation',
    'revokedPermissionLeavesTheCommittedNotificationPending',
    'grantingPermissionLetsTheReopenedPendingRowPostOnce',
}


def check(log: Path, status: int, method: str, output: Path) -> int:
    # A previous successful phase must never stand in for this invocation.
    output.unlink(missing_ok=True)
    if method not in METHODS:
        print('::error::Unknown Android permission-test phase')
        return 1
    text = log.read_text(encoding='utf-8', errors='replace') if log.is_file() else ''
    events = []
    current = {}
    final = []
    errors = []
    for line in text.splitlines():
        if final and line.startswith(('INSTRUMENTATION_STATUS: ', 'INSTRUMENTATION_STATUS_CODE: ')):
            errors.append('test status appeared after the final result')
        if line.startswith('INSTRUMENTATION_STATUS: '):
            item = line[len('INSTRUMENTATION_STATUS: '):]
            key, sep, value = item.partition('=')
            if not sep or not key or key in current:
                errors.append('malformed or duplicate instrumentation status')
            else:
                current[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            try:
                code = int(line.split(': ', 1)[1])
                events.append((code, current))
                current = {}
            except ValueError:
                errors.append('invalid instrumentation status code')
        elif line.startswith('INSTRUMENTATION_CODE: '):
            final.append(line.split(': ', 1)[1])
        elif line.startswith(('INSTRUMENTATION_FAILED:', 'INSTRUMENTATION_ABORTED:', 'INSTRUMENTATION_RESULT: shortMsg=')):
            errors.append('instrumentation failed or crashed')
    if status != 0:
        errors.append(f'adb exited {status}')
    if current:
        errors.append('incomplete instrumentation status')
    if final != ['-1']:
        errors.append('missing or invalid final instrumentation result')
    if len(events) != 2 or [code for code, _ in events] != [1, 0]:
        errors.append('expected exactly one started and successfully finished test')
    for _, event in events:
        if (event.get('class') != CLASS or event.get('test') != method or
                event.get('id') != 'AndroidJUnitRunner' or event.get('numtests') != '1' or event.get('current') != '1'):
            errors.append('wrong test, runner, or test count')
    if not re.search(r'^OK \(1 test\)\s*$', text, re.MULTILINE) or 'FAILURES!!!' in text:
        errors.append('JUnit did not report exactly one successful test')
    if errors:
        detail = ' | '.join(dict.fromkeys(errors))
        # Include the most relevant failure tail in Actions annotations without
        # ever treating its absence as a successful test.
        tail = ' | '.join(text.splitlines()[-18:])[:1400]
        message = f'{method}: {detail}. {tail}'.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::error::{message}')
        return 1
    output.parent.mkdir(parents=True, exist_ok=True)
    suite = ET.Element('testsuite', {'name': CLASS, 'tests': '1', 'failures': '0', 'errors': '0', 'skipped': '0'})
    ET.SubElement(suite, 'testcase', {'classname': CLASS, 'name': method})
    ET.ElementTree(suite).write(output, encoding='utf-8', xml_declaration=True)
    print(f'::notice::Verified Android instrumentation phase: {method}')
    return 0


def main() -> int:
    if len(sys.argv) != 5:
        print('usage: check-android-instrumentation-phase.py LOG ADB_STATUS METHOD OUTPUT_XML', file=sys.stderr)
        return 2
    try:
        return check(Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3], Path(sys.argv[4]))
    except (OSError, ValueError) as error:
        print(f'::error::Could not verify Android instrumentation output: {type(error).__name__}')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
