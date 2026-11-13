#!/usr/bin/env python3
"""Offline tests for the protected subdomain diagnostic; no real credentials."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import unittest
import urllib.error
import urllib.request
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("diagnose_push_subdomain", HERE / "diagnose-push-subdomain.py")
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

ACCOUNT = "a" * 32
READ = "synthetic_read_token_123456789"
DEPLOY = "synthetic_deploy_token_123456789"
SHA = "b" * 40
ACCOUNT_PATH = "/client/v4/accounts/" + ACCOUNT


def values():
    return {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "vzhem/APUMIR",
            "GITHUB_REF": "refs/heads/arena/1266946d-apumir", "GITHUB_SHA": SHA,
            d.ACCOUNT: ACCOUNT, d.READ_TOKEN: READ, d.DEPLOY_TOKEN: DEPLOY}


def valid_response(path):
    if path.endswith("/tokens/verify"):
        return {"success": True, "result": {"status": "active"}}
    if path.endswith("/workers/scripts?per_page=1&page=1"):
        return {"success": True, "result": [{"id": "private-worker-name"}]}
    if path.endswith("/workers/subdomain"):
        return {"success": True, "result": {"subdomain": "private-subdomain"}}
    raise AssertionError("unexpected endpoint")


class InputTests(unittest.TestCase):
    def test_exact_context_and_distinct_well_formed_secrets(self):
        self.assertEqual(d.validate_inputs(values()), (SHA, ACCOUNT, READ, DEPLOY))

    def test_invalid_context_debug_or_secret_stops_before_network(self):
        changes = ({"GITHUB_ACTIONS": "false"}, {"GITHUB_REPOSITORY": "other/repo"},
                   {"GITHUB_REF": "refs/heads/main"}, {"GITHUB_SHA": "short"},
                   {"RUNNER_DEBUG": "1"}, {"APU_GITHUB_STEP_DEBUG": "true"},
                   {"APU_GITHUB_RUNNER_DEBUG": "TRUE"}, {d.ACCOUNT: "../escape"},
                   {d.READ_TOKEN: ""}, {d.DEPLOY_TOKEN: "short"},
                   {d.DEPLOY_TOKEN: READ})
        for change in changes:
            stdout, stderr = io.StringIO(), io.StringIO()
            with self.subTest(change=list(change)), mock.patch.dict(os.environ, {**values(), **change}, clear=True), \
                 mock.patch.object(d, "check_checkout") as checkout, mock.patch.object(d, "collect") as collect, \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(d.main(), 1)
                checkout.assert_not_called()
                collect.assert_not_called()
                for name in (d.ACCOUNT, d.READ_TOKEN, d.DEPLOY_TOKEN):
                    self.assertNotIn(name, os.environ)
            output = stdout.getvalue() + stderr.getvalue()
            for secret in (ACCOUNT, READ, DEPLOY):
                self.assertNotIn(secret, output)

    def test_same_tokens_are_reported_only_as_equality_and_not_used(self):
        with self.assertRaises(d.DiagnosticError) as error:
            d.validate_inputs({**values(), d.DEPLOY_TOKEN: READ})
        self.assertEqual(error.exception.code, "same_token")
        self.assertNotIn(READ, d.MESSAGES["same_token"])

    def test_checkout_uses_a_clean_git_environment_and_fails_closed(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            self.assertEqual(kwargs["cwd"], d.ROOT)
            for secret in (d.ACCOUNT, d.READ_TOKEN, d.DEPLOY_TOKEN, "GH_TOKEN"):
                self.assertNotIn(secret, kwargs["env"])
            self.assertEqual(kwargs["env"]["GIT_CONFIG_NOSYSTEM"], "1")
            self.assertEqual(kwargs["env"]["GIT_CONFIG_GLOBAL"], os.devnull)
            self.assertIn("core.fsmonitor=false", args)
            return subprocess.CompletedProcess(args, 0, stdout=(SHA + "\n").encode() if "rev-parse" in args else b"")
        with mock.patch.object(d.subprocess, "run", side_effect=run):
            d.check_checkout(SHA)
        self.assertEqual(len(calls), 2)
        def changed(args, **_kwargs):
            return subprocess.CompletedProcess(args, 0, stdout=(SHA + "\n").encode() if "rev-parse" in args else b" M file")
        with mock.patch.object(d.subprocess, "run", side_effect=changed), self.assertRaises(d.DiagnosticError):
            d.check_checkout(SHA)
        def wrong_head(args, **_kwargs):
            return subprocess.CompletedProcess(args, 0, stdout=("c" * 40 + "\n").encode() if "rev-parse" in args else b"")
        with mock.patch.object(d.subprocess, "run", side_effect=wrong_head), self.assertRaises(d.DiagnosticError):
            d.check_checkout(SHA)


class ProviderTests(unittest.TestCase):
    def test_fixed_get_endpoints_are_called_once_per_distinct_token(self):
        calls = []
        def request(host, path, token):
            calls.append((host, path, token))
            return 200, valid_response(path)
        self.assertEqual(d.collect(ACCOUNT, READ, DEPLOY, request),
                         {"READ": ("OK_ACCOUNT", "OK", "OK"), "DEPLOY": ("OK_ACCOUNT", "OK", "OK")})
        expected = []
        for token in (READ, DEPLOY):
            for path in (ACCOUNT_PATH + "/tokens/verify", ACCOUNT_PATH + "/workers/scripts?per_page=1&page=1",
                         ACCOUNT_PATH + "/workers/subdomain"):
                expected.append(("api.cloudflare.com", path, token))
        self.assertEqual(calls, expected)

    def test_account_verify_auth_or_not_found_falls_back_to_user_verify(self):
        for initial in (401, 403, 404):
            calls = []
            def request(_host, path, _token):
                calls.append(path)
                return (initial, None) if len(calls) == 1 else (200, valid_response(path))
            with self.subTest(initial=initial):
                self.assertEqual(d.verify_status(request, ACCOUNT_PATH, READ), "OK_USER")
                self.assertEqual(calls, [ACCOUNT_PATH + "/tokens/verify", "/client/v4/user/tokens/verify"])

    def test_other_verify_failures_do_not_try_another_kind(self):
        for status in (400, 429, 503, 200):
            calls = []
            def request(_host, path, _token):
                calls.append(path)
                return status, {"success": False}
            with self.subTest(status=status):
                self.assertNotIn(d.verify_status(request, ACCOUNT_PATH, READ), ("OK_ACCOUNT", "OK_USER"))
                self.assertEqual(calls, [ACCOUNT_PATH + "/tokens/verify"])

    def test_known_inactive_and_malformed_verify_results(self):
        for token_status, expected in (("active", "OK_ACCOUNT"), ("disabled", "NOT_ACTIVE"),
                                       ("expired", "NOT_ACTIVE"), ("unknown", "UNEXPECTED_RESPONSE"),
                                       (None, "UNEXPECTED_RESPONSE")):
            request = lambda *_: (200, {"success": True, "result": {"status": token_status}})
            with self.subTest(token_status=token_status):
                self.assertEqual(d.verify_status(request, ACCOUNT_PATH, READ), expected)

    def test_http_categories_are_fixed_and_do_not_overclaim(self):
        mapping = {400: "HTTP_400", 401: "HTTP_401", 403: "HTTP_403", 404: "HTTP_404", 429: "HTTP_429",
                   302: "HTTP_REDIRECT_BLOCKED", 418: "HTTP_OTHER_4XX", 503: "HTTP_5XX", 204: "HTTP_UNEXPECTED"}
        for status, category in mapping.items():
            with self.subTest(status=status):
                self.assertEqual(d.subdomain_status(lambda *_: (status, {"secret": READ}), ACCOUNT_PATH, READ), category)
                self.assertEqual(d.list_status(lambda *_: (status, {"secret": READ}), ACCOUNT_PATH, READ), category)

    def test_error_body_yields_only_whitelisted_numeric_codes(self):
        def payload(errors):
            return json.dumps({"errors": errors, "secret": READ, "message": DEPLOY}).encode()
        cases = [(b"", "CF_NONE"), (b"not json", "CF_UNAVAILABLE"),
                 (b'{}', "CF_NONE"), (payload([]), "CF_NONE"),
                 (payload([{"code": 10000, "message": DEPLOY}]), "CF_10000"),
                 (payload([{"code": 9109, "message": READ}]), "CF_9109"),
                 (payload([{"code": 999999, "message": READ}]), "CF_OTHER"),
                 (payload([{"code": True}]), "CF_NONE"),
                 (payload([{"code": 10000}, {"code": 9109}]), "CF_MULTIPLE"),
                 (b'{"errors":[],"errors":[]}', "CF_UNAVAILABLE"),
                 (b'\xff', "CF_UNAVAILABLE"), (b'x' * (d.MAX_ERROR_BYTES + 1), "CF_UNAVAILABLE")]
        for raw, expected in cases:
            with self.subTest(expected=expected):
                category = d.error_code_category(raw)
                self.assertEqual(category, expected)
                self.assertIn(category, d.SAFE_CF_CATEGORIES)
                self.assertNotIn(READ, category)
                self.assertNotIn(DEPLOY, category)

    def test_http_error_is_bounded_closed_and_sanitized(self):
        class LimitedErrorBody(io.BytesIO):
            def read(self, maximum=-1):
                if maximum != d.MAX_ERROR_BYTES + 1:
                    raise AssertionError("error response limit missing")
                return super().read(maximum)
        body = LimitedErrorBody(json.dumps({"errors": [{"code": 10000, "message": DEPLOY}],
                                            "secret": READ}).encode())
        class Opener:
            def open(self, request, timeout):
                self_url = request.full_url
                if request.get_method() != "GET" or request.get_header("Authorization") != "Bearer " + DEPLOY:
                    raise AssertionError("wrong transport")
                raise urllib.error.HTTPError(self_url, 401, DEPLOY, {}, body)
        output = io.StringIO()
        with mock.patch.object(d.urllib.request, "build_opener", return_value=Opener()), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            status, safe_body = d.diagnostic_request("api.cloudflare.com", ACCOUNT_PATH + "/workers/subdomain", DEPLOY)
        self.assertEqual((status, safe_body), (401, {"_safe_code": "CF_10000"}))
        self.assertTrue(body.closed)
        self.assertEqual(d.http_category(status, safe_body), "HTTP_401+CF_10000")
        for secret in (ACCOUNT, READ, DEPLOY):
            self.assertNotIn(secret, output.getvalue() + repr(safe_body))

    def test_unrecognized_or_injected_error_category_never_leaves_the_process(self):
        for fake in (READ, "CF_123456789", "CF_10000\n::notice::" + DEPLOY, 10000, None):
            with self.subTest(kind=type(fake).__name__):
                category = d.http_category(401, {"_safe_code": fake})
                self.assertEqual(category, "HTTP_401")
                self.assertNotIn(READ, category)
                self.assertNotIn(DEPLOY, category)

    def test_transport_rejects_untrusted_host_or_path_before_opening(self):
        with mock.patch.object(d.urllib.request, "build_opener") as opener:
            with self.assertRaises(d.preflight.PreflightError):
                d.diagnostic_request("example.com", ACCOUNT_PATH + "/workers/subdomain", READ)
            with self.assertRaises(d.preflight.PreflightError):
                d.diagnostic_request("api.cloudflare.com", ACCOUNT_PATH + "/workers/workers", READ)
            opener.assert_not_called()

    def test_subdomain_response_categories_never_return_its_value(self):
        cases = [(None, "UNEXPECTED_RESPONSE"), ({}, "UNEXPECTED_RESPONSE"),
                 ({"success": False, "result": {"subdomain": "valid"}}, "UNEXPECTED_RESPONSE"),
                 ({"success": True}, "EMPTY_RESULT"), ({"success": True, "result": None}, "EMPTY_RESULT"),
                 ({"success": True, "result": {}}, "SUBDOMAIN_UNSET_IN_RESPONSE"),
                 ({"success": True, "result": {"subdomain": ""}}, "SUBDOMAIN_UNSET_IN_RESPONSE"),
                 ({"success": True, "result": {"subdomain": "bad\n::notice::" + READ}}, "INVALID_SUBDOMAIN_SHAPE"),
                 ({"success": True, "result": {"subdomain": "a" * 64}}, "INVALID_SUBDOMAIN_SHAPE"),
                 ({"success": True, "result": {"subdomain": "-bad"}}, "INVALID_SUBDOMAIN_SHAPE"),
                 ({"success": True, "result": {"subdomain": "UPPER"}}, "INVALID_SUBDOMAIN_SHAPE"),
                 ({"success": True, "result": {"subdomain": "a"}}, "OK"),
                 ({"success": True, "result": {"subdomain": "a-b"}}, "OK")]
        for body, expected in cases:
            with self.subTest(body=repr(body)[:30]):
                status = d.subdomain_status(lambda *_: (200, body), ACCOUNT_PATH, READ)
                self.assertEqual(status, expected)
                self.assertNotIn(READ, status)

    def test_list_requires_a_successful_list_not_any_200(self):
        for body in (None, {}, {"success": False, "result": []}, {"success": True, "result": {}},
                     {"success": True, "result": None}):
            with self.subTest(body=body):
                self.assertEqual(d.list_status(lambda *_: (200, body), ACCOUNT_PATH, READ), "UNEXPECTED_RESPONSE")
        self.assertEqual(d.list_status(lambda *_: (200, {"success": True, "result": []}), ACCOUNT_PATH, READ), "OK")

    def test_network_or_parser_failure_is_a_generic_unavailable_category(self):
        def fail(*_):
            raise d.preflight.PreflightError("unavailable")
        self.assertEqual(d.collect(ACCOUNT, READ, DEPLOY, fail),
                         {"READ": ("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"),
                          "DEPLOY": ("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE")})

    def test_internal_read_rejects_every_non_allowlisted_path_before_request(self):
        bad = ["/client/v4/accounts/" + ACCOUNT + "/workers/workers",
               ACCOUNT_PATH + "/workers/scripts", ACCOUNT_PATH + "/workers/scripts?per_page=2&page=1",
               ACCOUNT_PATH + "/workers/subdomain?x=1", "/client/v4/accounts/../workers/subdomain",
               "/client/v4/user/tokens/verify/extra", "https://attacker.example/"]
        request = mock.Mock()
        for path in bad:
            with self.subTest(path=path), self.assertRaises(d.DiagnosticError):
                d.read(request, path, READ)
        with self.assertRaises(d.DiagnosticError):
            d.read(request, ACCOUNT_PATH + "/workers/subdomain", "malformed\ntoken")
        request.assert_not_called()

    def test_real_read_adapter_constructs_only_gets_without_proxy_or_redirect(self):
        seen = []
        class Response:
            status = 200
            def __init__(self, path):
                self.path = path
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                return False
            def read(self, maximum):
                body = json.dumps(valid_response(self.path)).encode()
                self_max = d.preflight.MAX_BODY + 1
                if maximum != self_max:
                    raise AssertionError("response limit missing")
                return body
        class Opener:
            def open(self, request, timeout):
                self_url = request.full_url
                self_method = request.get_method()
                if not self_url.startswith("https://api.cloudflare.com/") or self_method != "GET" or timeout != 10:
                    raise AssertionError("unsafe request")
                if request.get_header("Authorization") not in ("Bearer " + READ, "Bearer " + DEPLOY):
                    raise AssertionError("wrong authentication scheme")
                path = self_url.removeprefix("https://api.cloudflare.com")
                seen.append((self_method, path))
                return Response(path)
        def build(*handlers):
            if len(handlers) != 2 or not isinstance(handlers[0], urllib.request.ProxyHandler) or handlers[0].proxies != {}:
                raise AssertionError("proxy not disabled")
            if not isinstance(handlers[1], d.preflight.NoRedirect):
                raise AssertionError("redirect not disabled")
            return Opener()
        with mock.patch.object(d.preflight.urllib.request, "build_opener", side_effect=build):
            result = d.collect(ACCOUNT, READ, DEPLOY)
        self.assertEqual(result, {"READ": ("OK_ACCOUNT", "OK", "OK"), "DEPLOY": ("OK_ACCOUNT", "OK", "OK")})
        self.assertEqual(len(seen), 6)
        self.assertTrue(all(method == "GET" for method, _ in seen))


class MainTests(unittest.TestCase):
    def test_success_is_redacted_and_does_not_claim_write_permission(self):
        output = io.StringIO()
        def checkout(sha):
            self.assertEqual(sha, SHA)
            for name in (d.ACCOUNT, d.READ_TOKEN, d.DEPLOY_TOKEN):
                self.assertNotIn(name, os.environ)
        def collect(account, read_token, deploy_token):
            self.assertEqual((account, read_token, deploy_token), (ACCOUNT, READ, DEPLOY))
            return {"READ": ("OK_USER", "OK", "OK"), "DEPLOY": ("OK_ACCOUNT", "OK", "OK")}
        with mock.patch.dict(os.environ, values(), clear=True), mock.patch.object(d, "check_checkout", side_effect=checkout), \
             mock.patch.object(d, "collect", side_effect=collect), contextlib.redirect_stdout(output):
            self.assertEqual(d.main(), 0)
        rendered = output.getvalue()
        self.assertIn("READ: verify=OK_USER, list=OK, subdomain=OK", rendered)
        self.assertIn("DEPLOY: verify=OK_ACCOUNT, list=OK, subdomain=OK", rendered)
        self.assertIn("Full token permissions and deployment are not verified", rendered)
        for secret in (ACCOUNT, READ, DEPLOY, "private-worker-name", "private-subdomain"):
            self.assertNotIn(secret, rendered)

    def test_probe_failures_are_reported_with_categories_not_provider_text(self):
        output = io.StringIO()
        results = {"READ": ("OK_USER", "OK", "HTTP_404"),
                   "DEPLOY": ("OK_ACCOUNT", "HTTP_401+CF_10000", "HTTP_401+CF_9109")}
        with mock.patch.dict(os.environ, values(), clear=True), mock.patch.object(d, "check_checkout"), \
             mock.patch.object(d, "collect", return_value=results), contextlib.redirect_stdout(output):
            self.assertEqual(d.main(), 1)
        self.assertIn("READ: verify=OK_USER, list=OK, subdomain=HTTP_404", output.getvalue())
        self.assertIn("DEPLOY: verify=OK_ACCOUNT, list=HTTP_401+CF_10000, subdomain=HTTP_401+CF_9109", output.getvalue())
        self.assertIn("does not prove its cause", output.getvalue())

    def test_invalid_category_cannot_inject_an_annotation(self):
        output = io.StringIO()
        results = {"READ": ("OK_USER", "OK", "::notice::" + READ), "DEPLOY": ("OK_ACCOUNT", "OK", "OK")}
        with mock.patch.dict(os.environ, values(), clear=True), mock.patch.object(d, "check_checkout"), \
             mock.patch.object(d, "collect", return_value=results), contextlib.redirect_stdout(output):
            self.assertEqual(d.main(), 1)
        self.assertNotIn(READ, output.getvalue())
        self.assertNotIn("READ: verify", output.getvalue())

    def test_complete_fake_provider_reports_401_code_without_private_response(self):
        calls = []
        class Response:
            status = 200
            def __init__(self, path):
                self.path = path
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                return False
            def read(self, maximum):
                if maximum != d.preflight.MAX_BODY + 1:
                    raise AssertionError("response limit missing")
                return json.dumps(valid_response(self.path)).encode()
        class FakeOpener:
            def open(self, request, timeout):
                url = request.full_url
                if not url.startswith("https://api.cloudflare.com/") or request.get_method() != "GET" or timeout != 10:
                    raise AssertionError("unexpected request")
                path = url.removeprefix("https://api.cloudflare.com")
                auth = request.get_header("Authorization")
                if auth not in ("Bearer " + READ, "Bearer " + DEPLOY):
                    raise AssertionError("unexpected credential")
                calls.append((path, auth))
                if (auth == "Bearer " + READ and path == ACCOUNT_PATH + "/tokens/verify") or \
                        (auth == "Bearer " + DEPLOY and "/workers/" in path):
                    error_body = json.dumps({"errors": [{"code": 10000, "message": DEPLOY}],
                                             "private_id": ACCOUNT, "subdomain": "private-subdomain"}).encode()
                    raise urllib.error.HTTPError(url, 401, READ, {}, io.BytesIO(error_body))
                return Response(path)
        output = io.StringIO()
        with mock.patch.dict(os.environ, values(), clear=True), mock.patch.object(d, "check_checkout"), \
             mock.patch.object(d.urllib.request, "build_opener", return_value=FakeOpener()), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(d.main(), 1)
        rendered = output.getvalue()
        self.assertIn("READ: verify=OK_USER, list=OK, subdomain=OK", rendered)
        self.assertIn("DEPLOY: verify=OK_ACCOUNT, list=HTTP_401+CF_10000, subdomain=HTTP_401+CF_10000", rendered)
        self.assertEqual(len(calls), 7)
        for secret in (ACCOUNT, READ, DEPLOY, "private-worker-name", "private-subdomain"):
            self.assertNotIn(secret, rendered)

    def test_checkout_or_unexpected_exception_cannot_expose_secrets_or_run_more_probes(self):
        for failed in ("checkout", "collect"):
            stdout, stderr = io.StringIO(), io.StringIO()
            with self.subTest(failed=failed), mock.patch.dict(os.environ, values(), clear=True), \
                 mock.patch.object(d, "check_checkout") as checkout, mock.patch.object(d, "collect") as collect, \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                (checkout if failed == "checkout" else collect).side_effect = RuntimeError(READ + ACCOUNT + DEPLOY)
                self.assertEqual(d.main(), 1)
                if failed == "checkout":
                    collect.assert_not_called()
            for secret in (ACCOUNT, READ, DEPLOY):
                self.assertNotIn(secret, stdout.getvalue() + stderr.getvalue())


class WorkflowTests(unittest.TestCase):
    def test_secrets_are_exposed_only_after_marker_tests_and_environment_review(self):
        workflow = (d.ROOT / ".github/workflows/push-subdomain-diagnostic.yml").read_text()
        before, after = workflow.split("  diagnose-read-only:\n", 1)
        self.assertIn("contains(github.event.head_commit.message, '[diagnose-push-subdomain]')", before)
        self.assertIn("python3 scripts/ci/test-diagnose-push-subdomain.py", before)
        self.assertIn("needs: offline-tests", before)
        self.assertIn("needs: protection-check", after)
        self.assertIn("environment: apu-push-staging", after)
        self.assertEqual(workflow.count("python3 scripts/ci/push-infra-preflight.py --check-protection"), 2)
        self.assertEqual(workflow.count("python3 scripts/ci/push-disabled-deploy.py --check-head"), 2)
        self.assertIn("python3 scripts/ci/diagnose-push-subdomain.py", after)
        self.assertNotIn("workflow_dispatch", workflow)
        for secret in (d.ACCOUNT, d.READ_TOKEN, d.DEPLOY_TOKEN):
            self.assertNotIn("secrets." + secret, before)
            self.assertEqual(after.count("secrets." + secret + " }}"), 1)
        self.assertNotIn("[deploy-push-disabled]", workflow)
        self.assertEqual(workflow.count("persist-credentials: false"), 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
