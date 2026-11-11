#!/usr/bin/env python3
"""Offline tests: the previous-job diagnostic emits only fixed categories."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


diagnostic = load("push_diagnostic", "diagnose-push-preflight.py")
preflight = load("push_preflight", "push-infra-preflight.py")


def line(message):
    # The single-job logs API returns raw timestamped lines, not gh run's
    # job/step-prefixed display format.
    return f"2026-11-09T00:00:00.1234567Z {message}\n"


class DiagnosticTests(unittest.TestCase):
    def valid_env(self):
        return {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": diagnostic.REPOSITORY,
                "GITHUB_REF": diagnostic.REF, "GITHUB_EVENT_NAME": "push", "GH_TOKEN": "fake-token"}

    def test_messages_match_the_preflight_and_each_has_one_fixed_category(self):
        self.assertEqual(preflight.MESSAGES, diagnostic.ERRORS)
        self.assertEqual(len(set(diagnostic.ERRORS.values())), len(diagnostic.ERRORS))
        for code, message in diagnostic.ERRORS.items():
            with self.subTest(code=code):
                self.assertEqual(code, diagnostic.classify(line(message)))
                self.assertEqual(code, diagnostic.classify(line(message) * 2))
                self.assertEqual(code, diagnostic.classify(line("\x1b[31m" + message + "\x1b[0m")))

    def test_unknown_malformed_or_conflicting_lines_are_not_guessed(self):
        message = diagnostic.ERRORS["config_missing"]
        self.assertIsNone(diagnostic.classify(message))
        self.assertIsNone(diagnostic.classify(line("unrelated " + message)))
        self.assertIsNone(diagnostic.classify(line(message + " SECRET_SENTINEL")))
        self.assertIsNone(diagnostic.classify(line(message) + line(diagnostic.ERRORS["cloudflare"])))
        self.assertIsNone(diagnostic.classify(None))

    def test_success_emits_only_category_not_log_or_private_details(self):
        secret = "SECRET_SENTINEL_MUST_NOT_APPEAR"
        fake_log = (line("unrelated " + secret) + line(diagnostic.ERRORS["config_missing"])).encode()
        out = io.StringIO()
        with mock.patch.dict(os.environ, self.valid_env(), clear=True), \
             mock.patch.object(diagnostic, "read_job_log", return_value=fake_log), \
             contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            self.assertEqual(0, diagnostic.main())
        self.assertIn("reason=config_missing", out.getvalue())
        self.assertNotIn(secret, out.getvalue())
        self.assertNotIn(diagnostic.ERRORS["config_missing"], out.getvalue())

    def test_only_fixed_job_is_requested_with_a_minimal_environment(self):
        values = {**self.valid_env(), "HOME": "/home/runner", "GH_DEBUG": "api",
                  "HTTPS_PROXY": "SECRET_SENTINEL", "OTHER_CREDENTIAL": "SECRET_SENTINEL"}
        with mock.patch.dict(os.environ, values, clear=True), \
             mock.patch.object(diagnostic, "capture_bounded", return_value=b"fake log") as read:
            self.assertEqual(b"fake log", diagnostic.read_job_log())
        command, env = read.call_args.args
        self.assertEqual(["gh", "api", f"repos/{diagnostic.REPOSITORY}/actions/jobs/{diagnostic.JOB_ID}/logs"], command)
        self.assertEqual("fake-token", env["GH_TOKEN"])
        self.assertNotIn("GH_DEBUG", env)
        self.assertNotIn("HTTPS_PROXY", env)
        self.assertNotIn("OTHER_CREDENTIAL", env)

    def test_missing_context_stops_before_reading_any_log(self):
        for key in self.valid_env():
            env = self.valid_env()
            env.pop(key)
            with self.subTest(key=key), mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(diagnostic, "read_job_log") as read, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, diagnostic.main())
                read.assert_not_called()
        env = {**self.valid_env(), "RUNNER_DEBUG": "1"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(diagnostic, "read_job_log") as read, \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, diagnostic.main())
            read.assert_not_called()

    def test_real_child_output_is_bounded_and_stderr_is_discarded(self):
        code = "import sys; sys.stderr.write('SECRET_SENTINEL'); sys.stdout.write('hello')"
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            result = diagnostic.capture_bounded([sys.executable, "-c", code], {}, limit=16, timeout=3)
        self.assertEqual(b"hello", result)
        self.assertEqual("", out.getvalue())
        code = "import sys, time; sys.stdout.write('x'*10000); sys.stdout.flush(); time.sleep(5)"
        with self.assertRaises(diagnostic.LogReadFailure):
            diagnostic.capture_bounded([sys.executable, "-c", code], {}, limit=64, timeout=3)

    def test_real_child_timeout_and_exit_failure_are_reported_without_contents(self):
        with self.assertRaises(diagnostic.LogReadFailure):
            diagnostic.capture_bounded([sys.executable, "-c", "import time; time.sleep(5)"], {}, timeout=0.1)
        with self.assertRaises(diagnostic.LogReadFailure) as error:
            diagnostic.capture_bounded([sys.executable, "-c", "print('SECRET_SENTINEL'); exit(1)"], {}, timeout=3)
        self.assertNotIn("SECRET_SENTINEL", str(error.exception))

    def test_failed_or_ambiguous_logs_never_emit_contents(self):
        sentinel = "SECRET_SENTINEL_MUST_NOT_APPEAR"
        cases = [diagnostic.LogReadFailure(sentinel), OSError(sentinel),
                 subprocess.TimeoutExpired(sentinel, 45),
                 (line(sentinel) + line(diagnostic.ERRORS["config_missing"]) +
                  line(diagnostic.ERRORS["cloudflare"])).encode()]
        for result in cases:
            out = io.StringIO()
            mock_result = {"side_effect": result} if isinstance(result, Exception) else {"return_value": result}
            with self.subTest(result=type(result).__name__), mock.patch.dict(os.environ, self.valid_env(), clear=True), \
                 mock.patch.object(diagnostic, "read_job_log", **mock_result), \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                self.assertEqual(1, diagnostic.main())
            self.assertNotIn(sentinel, out.getvalue())
            self.assertNotIn("reason=", out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
