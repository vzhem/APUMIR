#!/usr/bin/env python3
"""Offline checks for the protected, read-only push infrastructure preflight."""

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import unittest
import urllib.error
from unittest import mock

PATH = Path(__file__).with_name("push-infra-preflight.py")
spec = importlib.util.spec_from_file_location("push_infra_preflight", PATH)
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


def test_client(project="test-project-123"):
    return json.dumps({
        "project_info": {"project_id": project, "project_number": "123456789"},
        "client": [{
            "client_info": {"mobilesdk_app_id": "1:123456789:android:abcdef12", "android_client_info": {"package_name": "com.vladimir.messenger"}},
            "api_key": [{"current_key": "synthetic-client-key"}],
        }],
    })


def test_environment():
    return {
        "can_admins_bypass": False,
        "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        "protection_rules": [{"type": "required_reviewers", "prevent_self_review": False,
                              "reviewers": [{"type": "User", "reviewer": {"id": preflight.OWNER_ID}}]}],
    }


def test_policy():
    return {"total_count": 1, "branch_policies": [{"name": preflight.BRANCHES[0], "type": "branch"}]}


class CredentialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Generated only in memory for this run. Never check in a private key.
        result = subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
        )
        cls.key = result.stdout.decode("ascii")

    def config(self):
        service = {"type": "service_account", "project_id": "test-project-123",
                   "client_email": "push@test-project-123.iam.gserviceaccount.com", "private_key": self.key}
        return {
            preflight.CF_TOKEN_NAME: "synthetic_token_with_no_access",
            preflight.CF_ACCOUNT_NAME: "0123456789abcdef0123456789abcdef",
            preflight.FCM_ACCOUNT_NAME: json.dumps(service),
            preflight.CLIENT_CONFIG_NAME: test_client(),
        }

    def fails(self, values, code):
        with self.assertRaises(preflight.PreflightError) as error:
            preflight.credentials(values)
        self.assertEqual(error.exception.code, code)

    def test_generated_key_and_matching_configs_pass_without_retaining_fcm(self):
        config = preflight.credentials(self.config())
        self.assertEqual(config.account, "0123456789abcdef0123456789abcdef")
        self.assertNotIn(self.key, repr(config))
        self.assertNotIn("private_key", vars(config))
        self.assertNotIn("project_id", vars(config))
        self.assertTrue(preflight.rsa_key_valid(self.key))

    def test_required_values_missing(self):
        for name in self.config():
            with self.subTest(name=name):
                values = self.config()
                values[name] = ""
                self.fails(values, "config_missing")

    def test_cloudflare_account_and_token_are_strict(self):
        for name, value, code in ((preflight.CF_ACCOUNT_NAME, "../../escape", "cloudflare_account_invalid"),
                                  (preflight.CF_ACCOUNT_NAME, "0" * 31, "cloudflare_account_invalid"),
                                  (preflight.CF_TOKEN_NAME, "token\nsecret", "cloudflare_token_invalid"),
                                  (preflight.CF_TOKEN_NAME, "short", "cloudflare_token_invalid")):
            with self.subTest(name=name, value=value):
                values = self.config()
                values[name] = value
                self.fails(values, code)

    def test_current_and_legacy_cloudflare_token_shapes_pass_only_format_validation(self):
        # Synthetic shapes only; no checksum, provider authentication or permissions are tested.
        for token in ("cfut_" + "A" * 48, "cfat_" + "B" * 48, "C" * 40):
            with self.subTest(kind=token[:5]):
                values = self.config()
                values[preflight.CF_TOKEN_NAME] = token
                result = preflight.credentials(values, validate_key=lambda _key: True)
                self.assertEqual(token, result.token)
                self.assertNotIn(token, repr(result))

    def test_invalid_or_secret_android_client_rejected(self):
        for raw in ("{}", self.config()[preflight.FCM_ACCOUNT_NAME],
                    test_client().replace("com.vladimir.messenger", "another.app")):
            with self.subTest(case=raw[:12]):
                values = self.config()
                values[preflight.CLIENT_CONFIG_NAME] = raw
                self.fails(values, "client_invalid")

    def test_project_mismatch_rejected(self):
        values = self.config()
        values[preflight.CLIENT_CONFIG_NAME] = test_client("another-project")
        self.fails(values, "project_mismatch")

    def test_project_mismatch_stops_before_key_or_network_without_leaking_values(self):
        values = self.config()
        values[preflight.CLIENT_CONFIG_NAME] = test_client("another-project-456")
        env = {**values, "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO,
               "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0]}
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
             mock.patch.object(preflight, "check_cloudflare") as cloudflare, \
             mock.patch.object(preflight.subprocess, "run") as openssl, \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(1, preflight.main(["--check-secrets"]))
            cloudflare.assert_not_called()
            openssl.assert_not_called()
            for name in values:
                self.assertNotIn(name, os.environ)
        self.assertEqual(f"::error title=Push infrastructure preflight::{preflight.MESSAGES['project_mismatch']}\n",
                         stdout.getvalue())
        output = stdout.getvalue() + stderr.getvalue()
        for secret in ("another-project-456", "test-project-123", "synthetic_token_with_no_access",
                       "synthetic-client-key", self.key):
            self.assertNotIn(secret, output)

    def test_invalid_service_account_rejected(self):
        for field, value, code in (("type", "not_service_account", "fcm_json_invalid"),
                                   ("project_id", "../../x", "fcm_identity_invalid"),
                                   ("client_email", "wrong@other-project.iam.gserviceaccount.com", "fcm_identity_invalid"),
                                   ("client_email", "bad\nemail", "fcm_identity_invalid")):
            with self.subTest(field=field, value=value):
                values = self.config()
                service = json.loads(values[preflight.FCM_ACCOUNT_NAME])
                service[field] = value
                values[preflight.FCM_ACCOUNT_NAME] = json.dumps(service)
                self.fails(values, code)

    def test_duplicate_malformed_oversized_and_nonfinite_json_rejected(self):
        for raw in ('{"type":"service_account","type":"service_account"}', "[]", "{broken}",
                    '{"type": NaN}', "x" * 16_385, '"\\ud800"'):
            with self.subTest(case=raw[:40]):
                values = self.config()
                values[preflight.FCM_ACCOUNT_NAME] = raw
                self.fails(values, "fcm_json_invalid")

    def test_new_diagnostics_never_emit_values_or_contact_cloudflare(self):
        sentinel = "SECRET_SENTINEL_MUST_NOT_APPEAR!"
        bad_identity = json.loads(self.config()[preflight.FCM_ACCOUNT_NAME])
        bad_identity["project_id"] = sentinel
        cases = (
            (preflight.CF_TOKEN_NAME, sentinel, "cloudflare_token_invalid"),
            (preflight.CF_ACCOUNT_NAME, sentinel, "cloudflare_account_invalid"),
            (preflight.FCM_ACCOUNT_NAME, "{" + sentinel, "fcm_json_invalid"),
            (preflight.FCM_ACCOUNT_NAME, json.dumps(bad_identity), "fcm_identity_invalid"),
        )
        for name, value, code in cases:
            env = {**self.config(), name: value, "GITHUB_ACTIONS": "true",
                   "GITHUB_REPOSITORY": preflight.REPO, "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0]}
            stdout, stderr = io.StringIO(), io.StringIO()
            with self.subTest(code=code), mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(preflight, "check_cloudflare") as cloudflare, \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, preflight.main(["--check-secrets"]))
                cloudflare.assert_not_called()
            self.assertEqual(f"::error title=Push infrastructure preflight::{preflight.MESSAGES[code]}\n", stdout.getvalue())
            self.assertNotIn(sentinel, stdout.getvalue() + stderr.getvalue())
            self.assertNotIn(self.key, stdout.getvalue() + stderr.getvalue())

    def test_invalid_key_rejected(self):
        values = self.config()
        service = json.loads(values[preflight.FCM_ACCOUNT_NAME])
        # Well-shaped but non-RSA material must also fail the real openssl check.
        service["private_key"] = self.key.replace("A", "B")
        values[preflight.FCM_ACCOUNT_NAME] = json.dumps(service)
        self.fails(values, "key_invalid")
        service["private_key"] = "-----BEGIN PRIVATE KEY-----\ninvalid\n-----END PRIVATE KEY-----\n"
        values[preflight.FCM_ACCOUNT_NAME] = json.dumps(service)
        self.fails(values, "key_invalid")

    def test_unpaired_surrogate_cannot_escape_as_exception_details(self):
        with self.assertRaises(preflight.PreflightError) as error:
            preflight.parsed_json("\ud800", 100)
        self.assertEqual(error.exception.code, "config_invalid")


class EnvironmentTests(unittest.TestCase):
    def run_guard(self, environment, policies, first_status=200, second_status=200):
        calls = []

        def request(host, path, token):
            calls.append((host, path, token))
            return (first_status, environment) if len(calls) == 1 else (second_status, policies)

        preflight.check_environment("synthetic-github-token", request)
        return calls

    def test_only_exact_branch_owner_and_review_are_accepted(self):
        calls = self.run_guard(test_environment(), test_policy())
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(host == "api.github.com" for host, _, _ in calls))
        self.assertIn("/deployment-branch-policies?per_page=100", calls[1][1])

    def test_missing_or_loose_environment_fails_closed(self):
        valid = test_environment()
        variants = [None, {}, {**valid, "can_admins_bypass": True},
                    {**valid, "deployment_branch_policy": None},
                    {**valid, "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": False}},
                    {**valid, "protection_rules": []}]
        for change in ("self", "other_reviewer", "extra_reviewer"):
            env = copy.deepcopy(valid)
            rule = env["protection_rules"][0]
            if change == "self":
                rule["prevent_self_review"] = True
            elif change == "other_reviewer":
                rule["reviewers"][0]["reviewer"]["id"] += 1
            else:
                rule["reviewers"].append(rule["reviewers"][0])
            variants.append(env)
        for env in variants:
            with self.subTest(env=repr(env)[:100]), self.assertRaises(preflight.PreflightError) as error:
                self.run_guard(env, test_policy())
            self.assertEqual(error.exception.code, "environment")

    def test_wildcard_tag_extra_or_missing_branch_policy_fails_closed(self):
        variants = [None, {}, {"total_count": 0, "branch_policies": []},
                    {"total_count": 1, "branch_policies": [{"name": "arena/*", "type": "branch"}]},
                    {"total_count": 1, "branch_policies": [{"name": preflight.BRANCHES[0], "type": "tag"}]},
                    {"total_count": 2, "branch_policies": test_policy()["branch_policies"] * 2}]
        for policies in variants:
            with self.subTest(policies=policies), self.assertRaises(preflight.PreflightError) as error:
                self.run_guard(test_environment(), policies)
            self.assertEqual(error.exception.code, "environment")

    def test_one_rule_per_reviewed_branch_is_the_normal_state(self):
        # The environment gains one rule per branch allowed to start a deployment,
        # so two rules for the two branches of this line of work must pass, and
        # the current branch must be one of them.
        policies = {"total_count": 2, "branch_policies": [
            {"name": preflight.BRANCHES[0], "type": "branch"},
            {"name": preflight.BRANCHES[1], "type": "branch"},
        ]}
        self.run_guard(test_environment(), policies)
        # An extra rule for any other branch widens the environment: fail closed.
        widened = {"total_count": 3, "branch_policies": policies["branch_policies"] +
                   [{"name": "some-other-branch", "type": "branch"}]}
        with self.assertRaises(preflight.PreflightError) as error:
            self.run_guard(test_environment(), widened)
        self.assertEqual(error.exception.code, "environment")

    def test_api_errors_fail_closed(self):
        for first, second in ((404, 200), (403, 200), (200, 404), (200, 500)):
            with self.subTest(first=first, second=second), self.assertRaises(preflight.PreflightError):
                self.run_guard(test_environment(), test_policy(), first, second)


class CloudflareTests(unittest.TestCase):
    def setUp(self):
        self.config = preflight.CloudflareCredentials("0123456789abcdef0123456789abcdef", "synthetic_token_with_no_access")

    def request_sequence(self, responses):
        calls = []
        results = iter(responses)

        def request(host, path, token):
            calls.append((host, path, token))
            return next(results)

        return calls, request

    def test_account_owned_token_and_account_worker_read(self):
        calls, request = self.request_sequence([(200, {"success": True, "result": {"status": "active"}}),
                                                (200, {"success": True, "result": []})])
        preflight.check_cloudflare(self.config, request)
        self.assertEqual(len(calls), 2)
        self.assertTrue(calls[0][1].endswith("/tokens/verify"))
        self.assertIn("/workers/scripts?", calls[1][1])
        self.assertTrue(all(host == "api.cloudflare.com" for host, _, _ in calls))

    def test_user_owned_token_fallback(self):
        calls, request = self.request_sequence([(403, None), (200, {"success": True, "result": {"status": "active"}}),
                                                (200, {"success": True, "result": []})])
        preflight.check_cloudflare(self.config, request)
        self.assertEqual(calls[1][1], "/client/v4/user/tokens/verify")

    def test_failures_do_not_claim_deploy_permission(self):
        variants = [[(500, None)], [(200, {"success": True, "result": {"status": "disabled"}})],
                    [(200, {"success": False})], [(403, None), (401, None)],
                    [(200, {"success": True, "result": {"status": "active"}}), (403, None)],
                    [(200, {"success": True, "result": {"status": "active"}}), (200, {"success": True})]]
        for responses in variants:
            with self.subTest(responses=responses):
                calls, request = self.request_sequence(responses)
                with self.assertRaises(preflight.PreflightError) as error:
                    preflight.check_cloudflare(self.config, request)
                self.assertEqual(error.exception.code, "cloudflare")
                self.assertEqual(len(calls), len(responses))


class RedactionAndTransportTests(unittest.TestCase):
    def test_invocation_only_in_designated_actions_ref(self):
        # The current work line may use the same protected environment; an
        # unrelated branch still may not.
        self.assertEqual(["arena/1266946d-apumir", "arena/2d5de8a6-apumir"], list(preflight.BRANCHES))
        values = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO,
                  "GITHUB_REF": "refs/heads/arena/2d5de8a6-apumir"}
        preflight.invocation(values)
        values["GITHUB_REF"] = "refs/heads/arena/unknown-apumir"
        with self.assertRaises(preflight.PreflightError):
            preflight.invocation(values)
        values = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO, "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0]}
        preflight.invocation(values)
        for key in values:
            with self.subTest(key=key), self.assertRaises(preflight.PreflightError):
                preflight.invocation({**values, key: "other"})
        for key, value in (("RUNNER_DEBUG", "1"), ("APU_GITHUB_STEP_DEBUG", "TRUE"), ("APU_GITHUB_RUNNER_DEBUG", "true")):
            with self.subTest(key=key), self.assertRaises(preflight.PreflightError) as error:
                preflight.invocation({**values, key: value})
            self.assertEqual(error.exception.code, "debug")

    def test_fixed_host_and_redirect_refusal(self):
        with self.assertRaises(preflight.PreflightError):
            preflight.request_json("example.com", "/", "synthetic-token")
        self.assertIsNone(preflight.NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.com"))

    def test_http_error_body_is_not_read_or_printed(self):
        error = urllib.error.HTTPError("https://api.cloudflare.com/test", 403, "secret-in-error", {}, io.BytesIO(b"secret-in-body"))
        opener = mock.Mock()
        opener.open.side_effect = error
        with mock.patch.object(preflight.urllib.request, "build_opener", return_value=opener):
            status, body = preflight.request_json("api.cloudflare.com", "/test", "synthetic-token")
        self.assertEqual((status, body), (403, None))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.full_url, "https://api.cloudflare.com/test")
        self.assertTrue(error.fp is None or error.fp.closed)

    def test_unexpected_exception_never_prints_its_secret(self):
        sentinel = "SECRET_SENTINEL_MUST_NOT_APPEAR"
        values = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO,
                  "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0], "GH_TOKEN": sentinel}
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, values, clear=True), mock.patch.object(preflight, "check_environment", side_effect=RuntimeError(sentinel)), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = preflight.main(["--check-protection"])
            self.assertNotIn("GH_TOKEN", os.environ)
        self.assertEqual(result, 1)
        self.assertNotIn(sentinel, stdout.getvalue() + stderr.getvalue())

    def test_failure_annotations_are_fixed_and_never_include_exception_contents(self):
        sentinel = "SECRET_SENTINEL_MUST_NOT_APPEAR"
        values = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO,
                  "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0], "GH_TOKEN": sentinel}
        for code in [*preflight.MESSAGES, sentinel]:
            stdout, stderr = io.StringIO(), io.StringIO()
            with self.subTest(code=code), mock.patch.dict(os.environ, values, clear=True), \
                 mock.patch.object(preflight, "check_environment", side_effect=preflight.PreflightError(code)), \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, preflight.main(["--check-protection"]))
            message = preflight.MESSAGES.get(code, preflight.MESSAGES["unavailable"])
            self.assertEqual(f"::error title=Push infrastructure preflight::{message}\n", stdout.getvalue())
            self.assertNotIn(sentinel, stdout.getvalue() + stderr.getvalue())

    def test_secret_variables_popped_before_network(self):
        values = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": preflight.REPO, "GITHUB_REF": "refs/heads/" + preflight.BRANCHES[0],
                  preflight.CF_TOKEN_NAME: "secret", preflight.CF_ACCOUNT_NAME: "account", preflight.FCM_ACCOUNT_NAME: "private", preflight.CLIENT_CONFIG_NAME: "client"}

        captured = []

        def get_credentials(input_values):
            captured.append(input_values)
            return object()

        def check(_config):
            for name in (preflight.CF_TOKEN_NAME, preflight.CF_ACCOUNT_NAME, preflight.FCM_ACCOUNT_NAME, preflight.CLIENT_CONFIG_NAME):
                self.assertNotIn(name, os.environ)
            self.assertEqual(captured, [{}])

        stdout = io.StringIO()
        with mock.patch.dict(os.environ, values, clear=True), mock.patch.object(preflight, "credentials", side_effect=get_credentials), \
             mock.patch.object(preflight, "check_cloudflare", side_effect=check), contextlib.redirect_stdout(stdout):
            self.assertEqual(preflight.main(["--check-secrets"]), 0)
        self.assertIn("no Worker deployment", stdout.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
