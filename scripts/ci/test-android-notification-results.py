#!/usr/bin/env python3
"""Verify the notification report gate rejects missing, skipped and failed cases."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("notification_results", Path(__file__).with_name("check-android-notification-results.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class NotificationResultsTest(unittest.TestCase):
    def run_report(self, cases, status=0):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = "<testsuite>" + "".join(
                f'<testcase classname="{checker.CLASS}" name="{name}">{inside}</testcase>'
                for name, inside in cases
            ) + "</testsuite>"
            (root / "result.xml").write_text(xml)
            (root / "gradle.log").write_text("e: example compiler error\n")
            with contextlib.redirect_stdout(io.StringIO()):
                return checker.check(root, root / "gradle.log", status)

    def test_success_requires_every_case(self):
        self.assertEqual(0, self.run_report([(name, "") for name in checker.EXPECTED]))

    def test_missing_case_fails(self):
        self.assertEqual(1, self.run_report([(name, "") for name in list(checker.EXPECTED)[1:]]))

    def test_skipped_and_failed_cases_fail(self):
        for tag in ("skipped", "failure", "error"):
            cases = [(name, f"<{tag}/>") if i == 0 else (name, "") for i, name in enumerate(checker.EXPECTED)]
            self.assertEqual(1, self.run_report(cases))

    def test_gradle_failure_fails_even_with_complete_reports(self):
        self.assertEqual(1, self.run_report([(name, "") for name in checker.EXPECTED], status=1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
