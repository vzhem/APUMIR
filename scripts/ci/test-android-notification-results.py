#!/usr/bin/env python3
"""Verify the notification report gate rejects missing, skipped and failed cases."""
import contextlib
import importlib.util
import io
import re
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
                f'<testcase classname="{classname}" name="{name}">{inside}</testcase>'
                for classname, name, inside in cases
            ) + "</testsuite>"
            (root / "result.xml").write_text(xml)
            (root / "gradle.log").write_text("e: example compiler error\n")
            with contextlib.redirect_stdout(io.StringIO()):
                return checker.check(root, root / "gradle.log", status)

    def all_cases(self):
        return [(classname, name, "") for classname, names in checker.EXPECTED.items() for name in names]

    def test_success_requires_every_case(self):
        self.assertEqual(0, self.run_report(self.all_cases()))

    def test_missing_case_fails(self):
        self.assertEqual(1, self.run_report(self.all_cases()[1:]))
        missing_incoming = [case for case in self.all_cases() if case[0] != checker.INCOMING_CLASS]
        self.assertEqual(1, self.run_report(missing_incoming))
        missing_reply = [case for case in self.all_cases() if case[0] != checker.DIRECT_REPLY_CLASS]
        self.assertEqual(1, self.run_report(missing_reply))
        missing_reply_ffi = [case for case in self.all_cases() if case[0] != checker.DIRECT_REPLY_FFI_CLASS]
        self.assertEqual(1, self.run_report(missing_reply_ffi))
        missing_scoped_ack = [case for case in self.all_cases() if case[0] != checker.SCOPED_ACK_CLASS]
        self.assertEqual(1, self.run_report(missing_scoped_ack))
        missing_outbox = [case for case in self.all_cases() if case[0] != checker.OUTBOX_CLASS]
        self.assertEqual(1, self.run_report(missing_outbox))
        missing_reply_outbox = [case for case in self.all_cases() if case[0] != checker.REPLY_OUTBOX_CLASS]
        self.assertEqual(1, self.run_report(missing_reply_outbox))
        missing_mirror = [case for case in self.all_cases() if case[0] != checker.MIRROR_OUTGOING_CLASS]
        self.assertEqual(1, self.run_report(missing_mirror))
        missing_pending = [case for case in self.all_cases() if case[0] != checker.MIRROR_PENDING_CLASS]
        self.assertEqual(1, self.run_report(missing_pending))
        missing_push = [case for case in self.all_cases() if case[0] != checker.PUSH_CLASS]
        self.assertEqual(1, self.run_report(missing_push))
        missing_registration = [case for case in self.all_cases() if case[0] != checker.REGISTRATION_CLASS]
        self.assertEqual(1, self.run_report(missing_registration))
        missing_integration = [case for case in self.all_cases() if case[0] != checker.PUSH_INTEGRATION_CLASS]
        self.assertEqual(1, self.run_report(missing_integration))
        missing_mailbox_drain = [case for case in self.all_cases() if case[0] != checker.MAILBOX_DRAIN_CLASS]
        self.assertEqual(1, self.run_report(missing_mailbox_drain))
        missing_mailbox_outbox = [case for case in self.all_cases() if case[0] != checker.MAILBOX_OUTBOX_CLASS]
        self.assertEqual(1, self.run_report(missing_mailbox_outbox))
        missing_mailbox_outbox_ffi = [case for case in self.all_cases() if case[0] != checker.MAILBOX_OUTBOX_FFI_CLASS]
        self.assertEqual(1, self.run_report(missing_mailbox_outbox_ffi))
        for classname in (checker.PUSH_INBOX_CLASS, checker.PUSH_PERIODIC_CLASS,
                          checker.PUSH_DRAIN_CLASS, checker.PUSH_WORK_CLASS):
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[0] != classname]))
        missing_firebase = [case for case in self.all_cases() if case[0] != checker.FIREBASE_CLASS]
        self.assertEqual(1, self.run_report(missing_firebase))
        missing_lifecycle = [case for case in self.all_cases() if case[0] != checker.CORE_LIFECYCLE_CLASS]
        self.assertEqual(1, self.run_report(missing_lifecycle))
        missing_heartbeat = [case for case in self.all_cases() if case[0] != checker.HEARTBEAT_CLASS]
        self.assertEqual(1, self.run_report(missing_heartbeat))

    def test_skipped_and_failed_cases_fail(self):
        for tag in ("skipped", "failure", "error"):
            cases = [(classname, name, f"<{tag}/>") if i == 0 else (classname, name, "")
                     for i, (classname, name, _) in enumerate(self.all_cases())]
            self.assertEqual(1, self.run_report(cases))

    def test_push_periodic_and_workmanager_source_cases_match_the_report_gate(self):
        tests = Path(__file__).resolve().parents[2] / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/push"
        for name, classname in (("PushWakePeriodicRecoveryInstrumentedTest", checker.PUSH_PERIODIC_CLASS),
                                ("PushWakeWorkManagerInstrumentedTest", checker.PUSH_WORK_CLASS)):
            source = (tests / (name + ".kt")).read_text(encoding="utf-8")
            methods = re.findall(r"@Test\s+fun\s+([A-Za-z_]\w*)\s*\(", source)
            self.assertEqual(len(methods), len(set(methods)))
            self.assertEqual(checker.EXPECTED[classname], set(methods))

    def test_every_periodic_recovery_sqlite_case_is_required(self):
        names = checker.EXPECTED[checker.PUSH_PERIODIC_CLASS]
        self.assertEqual(11, len(names))
        for name in names:
            key = (checker.PUSH_PERIODIC_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_every_push_workmanager_case_is_required(self):
        names = checker.EXPECTED[checker.PUSH_WORK_CLASS]
        self.assertEqual(5, len(names))
        for name in names:
            key = (checker.PUSH_WORK_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_every_reply_retry_room_case_is_required(self):
        names = checker.EXPECTED[checker.REPLY_OUTBOX_CLASS]
        self.assertEqual(8, len(names))
        for name in names:
            key = (checker.REPLY_OUTBOX_CLASS, name)
            self.assertEqual(1, self.run_report([case for case in self.all_cases() if case[:2] != key]))
            skipped = [(*case[:2], "<skipped/>") if case[:2] == key else case for case in self.all_cases()]
            self.assertEqual(1, self.run_report(skipped))

    def test_gradle_failure_fails_even_with_complete_reports(self):
        self.assertEqual(1, self.run_report(self.all_cases(), status=1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
