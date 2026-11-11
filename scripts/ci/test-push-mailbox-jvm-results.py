#!/usr/bin/env python3
"""Exercise the mailbox JVM report gate against synthetic Gradle reports."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "mailbox_results", Path(__file__).with_name("check-push-mailbox-jvm-results.py")
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class MailboxResultsTest(unittest.TestCase):
    def all_cases(self):
        return [(classname, name, "") for classname, names in checker.EXPECTED.items() for name in names]

    def run_report(self, cases, malformed=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = "<testsuite>" + "".join(
                f'<testcase classname="{classname}" name="{name}">{inside}</testcase>'
                for classname, name, inside in cases
            ) + "</testsuite>"
            (root / "results.xml").write_text(xml, encoding="utf-8")
            if malformed:
                (root / "broken.xml").write_text("<testsuite>", encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                return checker.check(root)

    def test_every_case_must_run(self):
        self.assertEqual(0, self.run_report(self.all_cases()))
        self.assertEqual(1, self.run_report(self.all_cases()[1:]))
        for classname in checker.EXPECTED:
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[0] != classname]))
        self.assertEqual(1, self.run_report([]))

    def test_skipped_failed_and_errored_cases_fail(self):
        for tag in ("skipped", "failure", "error"):
            cases = self.all_cases()
            classname, name, _ = cases[0]
            cases[0] = (classname, name, f"<{tag}/>")
            self.assertEqual(1, self.run_report(cases))

    def test_duplicate_or_invalid_report_fails(self):
        cases = self.all_cases()
        self.assertEqual(1, self.run_report(cases + cases[:1]))
        self.assertEqual(1, self.run_report(cases, malformed=True))


if __name__ == "__main__":
    unittest.main(verbosity=2)
