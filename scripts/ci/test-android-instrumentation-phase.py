#!/usr/bin/env python3
"""Raw am-instrument report parsing must fail closed, including zero-exit failures."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

CI = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('instrumentation_phase', CI / 'check-android-instrumentation-phase.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
METHOD = 'disposableProfileSnapshotsTheOriginalPermission'


def event(code, classname=checker.CLASS, method=METHOD, total='1', current='1'):
    return (f'INSTRUMENTATION_STATUS: class={classname}\n'
            'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
            f'INSTRUMENTATION_STATUS: numtests={total}\n'
            f'INSTRUMENTATION_STATUS: current={current}\n'
            f'INSTRUMENTATION_STATUS: test={method}\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n')


def report(first=None, second=None, footer='INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'):
    return (event(1) if first is None else first) + (event(0) if second is None else second) + footer


class InstrumentationPhaseTest(unittest.TestCase):
    def check(self, text, expected, status=0, method=METHOD):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log, output = root / 'device.log', root / 'results' / 'case.xml'
            log.write_text(text, encoding='utf-8')
            output.parent.mkdir()
            output.write_text('stale passing report', encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()):
                result = checker.check(log, status, method, output)
            self.assertEqual(expected, result)
            if expected == 0:
                cases = list(ET.parse(output).getroot().iter('testcase'))
                self.assertEqual([(checker.CLASS, method)], [(x.get('classname'), x.get('name')) for x in cases])
            else:
                self.assertFalse(output.exists(), 'a failed invocation must not retain a passing report')

    def test_one_exact_pass_is_required(self):
        self.check(report(), 0)
        self.check(report().replace('\n', '\r\n'), 0)
        self.check(report(), 1, status=1)
        self.check(report(), 1, method='unknown')

    def test_skipped_failed_and_assumption_cases_are_not_passes(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code):
                self.check(report(second=event(code)), 1)

    def test_missing_duplicate_unexpected_or_malformed_test_fails(self):
        for content in (report(first=''), report(second=''),
                        report(first=event(1) + event(1)),
                        report(second=event(0, method='other')),
                        report(second=event(0, classname='other.Test')),
                        report(second=event(0, total='2')),
                        report(second=event(0, current='2')),
                        report(second=event(0).replace('id=AndroidJUnitRunner', 'id=other')),
                        report(second=event(0).replace('test=' + METHOD, 'test=' + METHOD + '\nINSTRUMENTATION_STATUS: test=duplicate'))):
            self.check(content, 1)

    def test_missing_bad_or_out_of_order_final_result_fails(self):
        for footer in ('', 'INSTRUMENTATION_CODE: -1\n', 'OK (1 test)\nINSTRUMENTATION_CODE: 0\n',
                       'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n',
                       'FAILURES!!!\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n',
                       'INSTRUMENTATION_RESULT: shortMsg=Process crashed\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'):
            self.check(report(footer=footer), 1)
        self.check('INSTRUMENTATION_CODE: -1\n' + event(1) + event(0) + 'OK (1 test)\n', 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
