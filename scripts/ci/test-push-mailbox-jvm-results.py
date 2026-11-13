#!/usr/bin/env python3
"""Exercise the mailbox and mirror JVM report gate with synthetic Gradle reports."""
import contextlib
import importlib.util
import io
import re
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

    def test_each_pending_mirror_case_is_required_and_cannot_be_skipped(self):
        mirror = {(classname, name) for classname, names in checker.MIRROR_PENDING_EXPECTED.items() for name in names}
        self.assertEqual(28, len(mirror))
        for missing in mirror:
            cases = [case for case in self.all_cases() if case[:2] != missing]
            self.assertEqual(1, self.run_report(cases))
        classname, name = next(iter(mirror))
        cases = [(*case[:2], "<skipped/>") if case[:2] == (classname, name) else case for case in self.all_cases()]
        self.assertEqual(1, self.run_report(cases))

    def test_each_mirror_outgoing_trust_case_is_required_and_cannot_be_skipped(self):
        for classname, names in ((checker.MIRROR_OUTGOING_FRAME_CLASS, checker.MIRROR_OUTGOING_FRAME_EXPECTED),
                                 (checker.MIRROR_OUTGOING_DISPATCH_CLASS, checker.MIRROR_OUTGOING_DISPATCH_EXPECTED)):
            self.assertEqual(6, len(names))
            for name in names:
                cases = [case for case in self.all_cases() if case[:2] != (classname, name)]
                self.assertEqual(1, self.run_report(cases))
                skipped = [(*case[:2], "<skipped/>") if case[:2] == (classname, name) else case for case in self.all_cases()]
                self.assertEqual(1, self.run_report(skipped))

    def test_push_source_cases_match_the_report_gate(self):
        tests = Path(__file__).resolve().parents[2] / "android-app/app/src/test/java/com/vladimir/messenger/data/push"
        for name, expected in (("PushWakeRunLoopTest", checker.RUN_LOOP_EXPECTED),
                               ("PushWakePeriodicRecoveryTest", checker.PERIODIC_EXPECTED),
                               ("PushWakeDrainTest", checker.DRAIN_EXPECTED),
                               ("PushWakeRecoveryScanTest", checker.RECOVERY_SCAN_EXPECTED),
                               *((name, checker.EXPECTED[checker.PREFIX + name]) for name in (
                                   "PushHttpClientTest", "PushWakeCryptoTest", "PushMailboxHttpClientTest",
                                   "PushMailboxHttpsTransportTest", "PushHttpLoopbackTlsTest", "PushMailboxDrainTest",
                                   "PushMailboxTextPolicyTest", "PushMailboxMessageSealerTest", "PushMailboxMessageOpenerTest",
                               ))):
            source = (tests / (name + ".kt")).read_text(encoding="utf-8")
            methods = re.findall(r"@Test\s+fun\s+([A-Za-z_]\w*)\s*\(", source)
            self.assertEqual(len(methods), len(set(methods)))
            self.assertEqual(expected, set(methods))

    def test_deletion_outbox_source_cases_are_all_required(self):
        source = (Path(__file__).resolve().parents[2] /
                  "android-app/app/src/test/java/com/vladimir/messenger/data/repository/DeletionOutboxTest.kt").read_text(encoding="utf-8")
        methods = re.findall(r"@Test\s+fun\s+([A-Za-z_]\w*)\s*\(", source)
        self.assertEqual(8, len(methods))
        self.assertEqual(checker.DELETION_OUTBOX_EXPECTED, set(methods))
        for name in checker.DELETION_OUTBOX_EXPECTED:
            key = (checker.DELETION_OUTBOX_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_filtered_android_compile_selects_every_required_class(self):
        script = Path(__file__).with_name("check-android-compile.sh").read_text(encoding="utf-8")
        selected = set(re.findall(r"--tests '\*(\w+)\*'", script))
        for classname in checker.EXPECTED:
            self.assertIn(classname.rsplit(".", 1)[-1], selected)

    def test_each_mailbox_text_case_is_required_and_cannot_be_skipped(self):
        for name, count in (("PushMailboxTextPolicyTest", 3), ("PushMailboxMessageSealerTest", 8),
                            ("PushMailboxMessageOpenerTest", 8)):
            classname = checker.PREFIX + name
            self.assertEqual(count, len(checker.EXPECTED[classname]))
            for method in checker.EXPECTED[classname]:
                key = (classname, method)
                self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
                skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
                self.assertEqual(1, self.run_report(skipped))

    def test_each_transport_and_mailbox_drain_case_is_required(self):
        for name, count in (("PushHttpClientTest", 8), ("PushMailboxHttpClientTest", 11),
                            ("PushMailboxHttpsTransportTest", 10), ("PushHttpLoopbackTlsTest", 5),
                            ("PushMailboxDrainTest", 16)):
            classname = checker.PREFIX + name
            self.assertEqual(count, len(checker.EXPECTED[classname]))
            for method in checker.EXPECTED[classname]:
                key = (classname, method)
                self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
                skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
                self.assertEqual(1, self.run_report(skipped))

    def test_each_recovery_scan_case_is_required_and_cannot_be_skipped(self):
        self.assertEqual(21, len(checker.RECOVERY_SCAN_EXPECTED))
        for name in checker.RECOVERY_SCAN_EXPECTED:
            key = (checker.RECOVERY_SCAN_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_each_wake_drain_case_is_required_and_cannot_be_skipped(self):
        self.assertEqual(19, len(checker.DRAIN_EXPECTED))
        for name in checker.DRAIN_EXPECTED:
            key = (checker.DRAIN_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_each_run_loop_case_is_required_and_cannot_be_skipped(self):
        self.assertEqual(12, len(checker.RUN_LOOP_EXPECTED))
        for name in checker.RUN_LOOP_EXPECTED:
            key = (checker.RUN_LOOP_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_each_periodic_recovery_case_is_required_and_cannot_be_skipped(self):
        self.assertEqual(13, len(checker.PERIODIC_EXPECTED))
        for name in checker.PERIODIC_EXPECTED:
            key = (checker.PERIODIC_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_each_reply_retry_case_is_required_and_cannot_be_skipped(self):
        self.assertEqual(16, len(checker.REPLY_OUTBOX_EXPECTED))
        for name in checker.REPLY_OUTBOX_EXPECTED:
            key = (checker.REPLY_OUTBOX_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_duplicate_or_invalid_report_fails(self):
        cases = self.all_cases()
        self.assertEqual(1, self.run_report(cases + cases[:1]))
        self.assertEqual(1, self.run_report(cases, malformed=True))


if __name__ == "__main__":
    unittest.main(verbosity=2)
