#!/usr/bin/env python3
"""Offline tests of the protected Firebase project comparison."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import unittest
from unittest import mock

PATH = Path(__file__).with_name("compare-push-firebase-projects.py")
spec = importlib.util.spec_from_file_location("compare_push_firebase_projects", PATH)
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)

SENTINEL = "SYNTHETIC_PRIVATE_VALUE_MUST_NOT_APPEAR"
REFERENCE_PROJECT = "reference-project-123"
OTHER_CLIENT_PROJECT = "other-client-project-456"
OTHER_SERVICE_PROJECT = "other-server-project-789"


def client(project=REFERENCE_PROJECT):
    return json.dumps({
        "project_info": {"project_id": project, "project_number": "123456789"},
        "client": [{
            "client_info": {"mobilesdk_app_id": "1:123456789:android:abcdef12",
                            "android_client_info": {"package_name": "com.vladimir.messenger"}},
            "api_key": [{"current_key": "synthetic-client-key"}],
        }],
    })


def service(project=REFERENCE_PROJECT):
    return json.dumps({"type": "service_account", "project_id": project,
                       "private_key": SENTINEL, "client_email": "synthetic@example.invalid"})


def environment():
    return {
        "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": comparison.REPO,
        "GITHUB_REF": "refs/heads/" + comparison.BRANCH,
        comparison.CLIENT_NAME: client(), comparison.REFERENCE_NAME: client(),
        comparison.SERVICE_NAME: service(),
    }


class ProjectComparisonTests(unittest.TestCase):
    def run_diagnostic(self, env):
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
             mock.patch("socket.socket", side_effect=AssertionError("unexpected network")) as network, \
             mock.patch("subprocess.run", side_effect=AssertionError("unexpected subprocess")) as subprocess_run, \
             mock.patch("builtins.open", side_effect=AssertionError("unexpected file access")) as file_open, \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = comparison.main()
            for name in comparison.SECRET_NAMES:
                self.assertNotIn(name, os.environ)
            network.assert_not_called()
            subprocess_run.assert_not_called()
            file_open.assert_not_called()
        output = stdout.getvalue() + stderr.getvalue()
        for secret in (SENTINEL, REFERENCE_PROJECT, OTHER_CLIENT_PROJECT, OTHER_SERVICE_PROJECT,
                       "synthetic-client-key", "synthetic@example.invalid"):
            self.assertNotIn(secret, output)
        return status, stdout.getvalue(), stderr.getvalue()

    def test_all_four_combinations_report_only_fixed_comparison(self):
        for client_match in (False, True):
            for service_match in (False, True):
                with self.subTest(client_match=client_match, service_match=service_match):
                    env = environment()
                    env[comparison.CLIENT_NAME] = client(REFERENCE_PROJECT if client_match else OTHER_CLIENT_PROJECT)
                    env[comparison.SERVICE_NAME] = service(REFERENCE_PROJECT if service_match else OTHER_SERVICE_PROJECT)
                    status, stdout, stderr = self.run_diagnostic(env)
                    self.assertEqual(0, status)
                    self.assertEqual("::notice title=Firebase project comparison::" +
                                     comparison.RESULT_MESSAGES[(client_match, service_match)] + "\n" +
                                     "Diagnostic only: no key authentication, Cloudflare or Google requests were performed.\n", stdout)
                    self.assertEqual("", stderr)

    def test_missing_values_have_distinct_fixed_errors(self):
        for name, code in ((comparison.REFERENCE_NAME, "reference_missing"),
                           (comparison.CLIENT_NAME, "client_missing"),
                           (comparison.SERVICE_NAME, "service_missing")):
            with self.subTest(name=name):
                env = environment()
                env[name] = ""
                status, stdout, stderr = self.run_diagnostic(env)
                self.assertEqual(1, status)
                self.assertEqual("::error title=Firebase project comparison::" + comparison.ERROR_MESSAGES[code] + "\n", stdout)
                self.assertEqual("", stderr)

    def test_service_account_cannot_be_used_as_reference(self):
        env = environment()
        env[comparison.REFERENCE_NAME] = service()
        status, stdout, _ = self.run_diagnostic(env)
        self.assertEqual(1, status)
        self.assertIn(comparison.ERROR_MESSAGES["reference_invalid"], stdout)

    def test_bad_client_inputs_are_rejected_without_values(self):
        for name, code in ((comparison.REFERENCE_NAME, "reference_invalid"),
                           (comparison.CLIENT_NAME, "client_invalid")):
            for raw in ("{}", client().replace("com.vladimir.messenger", "other.package"),
                        '{"project_info":{},"project_info":{}}', "x" * 65_537):
                with self.subTest(name=name, kind=raw[:4]):
                    env = environment()
                    env[name] = raw
                    status, stdout, _ = self.run_diagnostic(env)
                    self.assertEqual(1, status)
                    self.assertIn(comparison.ERROR_MESSAGES[code], stdout)

    def test_malformed_duplicate_oversized_and_wrong_type_service_rejected(self):
        for raw in ("{}", "{bad}", "[]", '"not-an-object"',
                    '{"type":"service_account","project_id":"first-project","project_id":"second-project"}',
                    '{"type":"service_account","project_id":"../../escape"}',
                    "x" * (comparison.MAX_SERVICE_BYTES + 1)):
            with self.subTest(kind=raw[:15]):
                env = environment()
                env[comparison.SERVICE_NAME] = raw
                status, stdout, _ = self.run_diagnostic(env)
                self.assertEqual(1, status)
                self.assertIn(comparison.ERROR_MESSAGES["service_invalid"], stdout)

    def test_no_actual_private_key_validation_or_google_authentication(self):
        # A fabricated private key is accepted only for comparing the project;
        # the result explicitly cannot certify a key or its permissions.
        status, stdout, _ = self.run_diagnostic(environment())
        self.assertEqual(0, status)
        self.assertIn("no key authentication, Cloudflare or Google requests", stdout)

    def test_wrong_invocation_is_rejected_and_inputs_removed(self):
        for name in ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_REF"):
            with self.subTest(name=name):
                env = environment()
                env[name] = "wrong"
                status, stdout, _ = self.run_diagnostic(env)
                self.assertEqual(1, status)
                self.assertIn(comparison.ERROR_MESSAGES["invocation"], stdout)

    def test_debug_modes_fail_closed_before_parsing(self):
        for name, value in (("RUNNER_DEBUG", "1"), ("APU_GITHUB_STEP_DEBUG", "TRUE"),
                            ("APU_GITHUB_RUNNER_DEBUG", "true")):
            with self.subTest(name=name), mock.patch.object(comparison, "compare") as compare:
                env = environment()
                env[name] = value
                status, stdout, _ = self.run_diagnostic(env)
                self.assertEqual(1, status)
                self.assertIn(comparison.ERROR_MESSAGES["debug"], stdout)
                compare.assert_not_called()

    def test_unexpected_exceptions_and_invalid_results_never_leak(self):
        for patch_value in (RuntimeError(SENTINEL), comparison.ComparisonError([SENTINEL]),
                            comparison.ComparisonError(None), [SENTINEL]):
            with self.subTest(kind=type(patch_value).__name__):
                patcher = (mock.patch.object(comparison, "compare", side_effect=patch_value)
                           if isinstance(patch_value, Exception)
                           else mock.patch.object(comparison, "compare", return_value=patch_value))
                with patcher:
                    status, stdout, _ = self.run_diagnostic(environment())
                self.assertEqual(1, status)
                self.assertIn(comparison.ERROR_MESSAGES["unavailable"], stdout)

    def test_unpaired_surrogate_is_redacted(self):
        values = {comparison.CLIENT_NAME: client(), comparison.REFERENCE_NAME: client(),
                  comparison.SERVICE_NAME: "\ud800"}
        with self.assertRaises(comparison.ComparisonError) as error:
            comparison.compare(values)
        self.assertEqual("service_invalid", error.exception.code)

    def test_workflow_uses_explicit_marker_and_protected_environment(self):
        workflow = PATH.parents[2] / ".github/workflows/firebase-project-comparison.yml"
        text = workflow.read_text(encoding="utf-8")
        self.assertIn("[push-project-comparison]", text)
        self.assertNotIn("[push-infra-preflight]", "[push-project-comparison]")
        self.assertIn("environment: apu-push-staging", text)
        self.assertIn("needs: protection-check", text)
        self.assertEqual(2, text.count("run: python3 scripts/ci/push-infra-preflight.py --check-protection"))
        self.assertEqual(3, text.count("persist-credentials: false"))
        self.assertLess(text.rindex("--check-protection"), text.index("run: python3 scripts/ci/compare-push-firebase-projects.py"))
        for name in comparison.SECRET_NAMES:
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", text)
        self.assertNotIn("secrets.APU_PUSH_CLOUDFLARE_TOKEN", text)
        self.assertNotIn("--check-secrets", text)
        self.assertNotIn("pull_request:", text)
        self.assertNotIn("pull_request_target:", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
