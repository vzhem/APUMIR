#!/usr/bin/env python3
"""Offline safety and state tests for the one-target GET-only inspection."""

import ast
import base64
import copy
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("inspect_disabled_push", HERE / "inspect-disabled-push.py")
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

FAKE_ACCOUNT = "1" * 32
FAKE_TOKEN = "synthetic_read_token_123456789"
FAKE_SHA = "a" * 40
FAKE_ID = "2" * 32
FAKE_VERSION = "12345678-1234-1234-1234-123456789abc"
FAKE_SUBDOMAIN = "private-subdomain-example"
SENTINEL = "SENSITIVE_PROVIDER_SENTINEL"


def values():
    return {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "vzhem/APUMIR",
            "GITHUB_REF": "refs/heads/arena/1266946d-apumir", "GITHUB_SHA": FAKE_SHA,
            d.ACCOUNT: FAKE_ACCOUNT, d.READ_TOKEN: FAKE_TOKEN}


def worker():
    return {"id": FAKE_ID, "name": d.WORKER_NAME,
            "subdomain": {"enabled": True, "previews_enabled": False}}


def deployment():
    return {"deployments": [{"strategy": "percentage",
                             "versions": [{"version_id": FAKE_VERSION, "percentage": 100}]}]}


def version():
    modules = []
    for name in d.MODULE_HASHES:
        raw = (d.ROOT / "tools/worker/push" / name).read_bytes()
        modules.append({"name": name, "content_type": "application/javascript+module",
                        "content_base64": base64.b64encode(raw).decode("ascii")})
    return {"id": FAKE_VERSION, "compatibility_date": "2025-07-18", "compatibility_flags": [],
            "main_module": "worker.mjs", "bindings": [
                {"name": "PUSH_ENABLED", "type": "plain_text", "text": "false"},
                {"name": "MAILBOX_ENABLED", "type": "plain_text", "text": "false"},
            ], "modules": modules}


def base():
    return f"/client/v4/accounts/{FAKE_ACCOUNT}/workers"


def path_worker():
    return f"{base()}/workers/{d.WORKER_NAME}"


def path_deployment():
    return f"{base()}/scripts/{d.WORKER_NAME}/deployments?page=1&per_page=1"


def path_version():
    return f"{base()}/workers/{FAKE_ID}/versions/{FAKE_VERSION}?include=modules"


class Response(io.BytesIO):
    def __init__(self, status, body):
        super().__init__(body)
        self.status = self.code = status


def reply(result):
    return json.dumps({"success": True, "result": result}).encode("utf-8")


def opener_returning(response=None, error=None):
    class Opener:
        def open(self, req, timeout):
            if error is not None:
                raise error
            return response
    return Opener()


class InputsTest(unittest.TestCase):
    def test_target_is_fixed_to_creation_sha_and_separate_from_relay(self):
        self.assertEqual(d.CREATION_SHA, "b266925f78ee9afbf19ae42b3f251d84f841c7dd")
        self.assertEqual(d.WORKER_NAME, "apu-push-b266925f78ee9afbf19a")
        self.assertNotIn("p2p-relay", d.WORKER_NAME)
        self.assertEqual(d.validate_inputs(values()), (FAKE_SHA, FAKE_ACCOUNT, FAKE_TOKEN))

    def test_wrong_invocation_debug_and_bad_secrets_fail_before_network(self):
        changes = ({"GITHUB_ACTIONS": "false"}, {"GITHUB_REPOSITORY": "other/repo"},
                   {"GITHUB_REF": "refs/heads/main"}, {"GITHUB_SHA": "short"},
                   {"RUNNER_DEBUG": "1"}, {"APU_GITHUB_STEP_DEBUG": "true"},
                   {"APU_GITHUB_RUNNER_DEBUG": "TRUE"}, {d.ACCOUNT: "x"}, {d.READ_TOKEN: "bad"})
        for change in changes:
            output = io.StringIO()
            with self.subTest(change=change):
                with self.assertRaises(d.InspectionError):
                    d.validate_inputs({**values(), **change})
                with patch.dict(os.environ, {**values(), **change}, clear=True), \
                        patch.object(d, "check_checkout") as checkout, patch.object(d, "read_snapshot") as read, \
                        patch.object(d, "public_health") as health, redirect_stdout(output):
                    self.assertEqual(d.main(), 1)
                    checkout.assert_not_called()
                    read.assert_not_called()
                    health.assert_not_called()
                self.assertNotIn(FAKE_ACCOUNT, output.getvalue())
                self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_git_is_clean_and_receives_no_secret_or_configured_hooks(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            self.assertIn("core.fsmonitor=false", args)
            self.assertEqual(kwargs["env"]["GIT_CONFIG_GLOBAL"], os.devnull)
            self.assertEqual(kwargs["env"]["GIT_CONFIG_NOSYSTEM"], "1")
            self.assertNotIn(d.READ_TOKEN, kwargs["env"])
            self.assertNotIn(d.ACCOUNT, kwargs["env"])
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            self.assertNotIn("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN", kwargs["env"])
            stdout = (FAKE_SHA + "\n").encode() if "rev-parse" in args else b""
            return subprocess.CompletedProcess(args, 0, stdout=stdout)
        with patch.object(d.subprocess, "run", side_effect=run):
            d.check_checkout(FAKE_SHA)
        self.assertEqual(len(calls), 2)
        for head, changed in (("b" * 40, b""), (FAKE_SHA, b" M script")):
            def bad_run(args, **_kwargs):
                return subprocess.CompletedProcess(args, 0, stdout=(head + "\n").encode() if "rev-parse" in args else changed)
            with self.subTest(head=head, changed=changed), patch.object(d.subprocess, "run", side_effect=bad_run), self.assertRaises(d.InspectionError):
                d.check_checkout(FAKE_SHA)

    def test_checked_in_modules_match_pinned_creation_hashes(self):
        self.assertEqual(len(d.MODULE_HASHES), 6)
        for name, expected in d.MODULE_HASHES.items():
            raw = (d.ROOT / "tools/worker/push" / name).read_bytes()
            with self.subTest(module=name):
                self.assertEqual(hashlib.sha256(raw).hexdigest(), expected)


class ApiSafetyTest(unittest.TestCase):
    def test_allowlist_only_specific_read_endpoints(self):
        allowed = [path_worker(), path_deployment(), path_version(), f"{base()}/subdomain"]
        for path in allowed:
            with self.subTest(path=path):
                self.assertTrue(d.allowed_api_path(FAKE_ACCOUNT, path))
        denied = [f"{base()}/workers", f"{base()}/scripts/p2p-relay", f"{base()}/workers/p2p-relay",
                  path_worker() + "/versions", path_worker() + "?x=1", path_worker() + "/../other",
                  path_deployment().replace("per_page=1", "per_page=2"),
                  path_version().replace("include=modules", "include=modules&x=1"),
                  path_version().replace(FAKE_ID, "../p2p-relay"), f"{base()}/subdomain#fragment",
                  f"https://api.cloudflare.com{path_worker()}"]
        for path in denied:
            with self.subTest(path=path):
                self.assertFalse(d.allowed_api_path(FAKE_ACCOUNT, path))
        self.assertFalse(d.allowed_api_path("b" * 32, path_worker()))
        self.assertFalse(d.allowed_api_path("x", path_worker()))
        self.assertFalse(d.allowed_api_path(FAKE_ACCOUNT, None))

    def test_api_get_is_bounded_authenticated_fixed_host_get_without_proxy_or_redirect(self):
        captured = []
        class Opener:
            def open(self, req, timeout):
                captured.append((req, timeout))
                return Response(200, reply({"ok": True}))
        with patch.object(d.urllib.request, "build_opener", return_value=Opener()) as build:
            self.assertEqual(d.api_get(FAKE_ACCOUNT, FAKE_TOKEN, path_worker()), {"ok": True})
        req, timeout = captured[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertIsNone(req.data)
        self.assertEqual(req.full_url, "https://api.cloudflare.com" + path_worker())
        self.assertEqual(req.get_header("Authorization"), "Bearer " + FAKE_TOKEN)
        self.assertEqual(timeout, 15)
        handlers = build.call_args.args
        self.assertTrue(any(isinstance(h, d.urllib.request.ProxyHandler) and h.proxies == {} for h in handlers))
        self.assertTrue(any(isinstance(h, d.preflight.NoRedirect) for h in handlers))
        self.assertIsNone(d.preflight.NoRedirect().redirect_request(None, None, None, None, None, "https://evil.example"))
        for bad_path, bad_token in ((f"{base()}/workers/p2p-relay", FAKE_TOKEN), (path_worker(), "bad\r\nX: evil")):
            with self.subTest(path=bad_path), patch.object(d.urllib.request, "build_opener") as unused:
                with self.assertRaises(d.InspectionError):
                    d.api_get(FAKE_ACCOUNT, bad_token, bad_path)
                unused.assert_not_called()

    def test_api_http_errors_discard_body_and_return_only_fixed_categories(self):
        class NeverRead(io.BytesIO):
            def read(self, *_args):
                raise AssertionError("error body was read")
        for status, expected in ((401, "api_http_401"), (403, "api_http_403"), (404, "api_http_404"),
                                 (429, "api_http_429"), (302, "api_redirect"), (418, "api_other_4xx"),
                                 (503, "api_5xx")):
            body = NeverRead(SENTINEL.encode())
            error = urllib.error.HTTPError("https://api.cloudflare.com/secret", status, SENTINEL, {}, body)
            with self.subTest(status=status), patch.object(d.urllib.request, "build_opener", return_value=opener_returning(error=error)), self.assertRaises(d.InspectionError) as caught:
                d.api_get(FAKE_ACCOUNT, FAKE_TOKEN, path_worker())
            self.assertEqual(caught.exception.code, expected)
            self.assertTrue(body.closed)
            self.assertNotIn(SENTINEL, str(caught.exception))

    def test_api_rejects_oversized_duplicate_or_malformed_responses(self):
        bodies = [b"x" * (d.MAX_API_BYTES + 1), b'{"success":true,"success":false,"result":{}}',
                  b"\xff", b'{"success":true,"result":[]}', b'{"success":false,"result":{}}', b"{}"]
        for body in bodies:
            with self.subTest(size=len(body), prefix=body[:40]), patch.object(d.urllib.request, "build_opener", return_value=opener_returning(Response(200, body))), self.assertRaises(d.InspectionError):
                d.api_get(FAKE_ACCOUNT, FAKE_TOKEN, path_worker())

    def test_all_source_requests_explicitly_use_get_and_no_upload_body(self):
        tree = ast.parse((HERE / "inspect-disabled-push.py").read_text())
        requests = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and
                    isinstance(node.func, ast.Attribute) and node.func.attr == "Request"]
        self.assertEqual(len(requests), 2)
        for call in requests:
            keys = {keyword.arg: keyword.value for keyword in call.keywords}
            self.assertNotIn("data", keys)
            self.assertIsInstance(keys.get("method"), ast.Constant)
            self.assertEqual(keys["method"].value, "GET")


class ReadbackTest(unittest.TestCase):
    def test_worker_identity_and_settings_fail_closed(self):
        self.assertEqual(d.worker_id(worker()), FAKE_ID)
        for bad in ({**worker(), "id": "wrong"}, {**worker(), "name": "p2p-relay"},
                    {**worker(), "subdomain": {"enabled": False, "previews_enabled": False}},
                    {**worker(), "subdomain": {"enabled": True, "previews_enabled": True}},
                    {**worker(), "subdomain": {"enabled": True}}, None):
            with self.subTest(value=bad), self.assertRaises(d.InspectionError):
                d.worker_id(bad)

    def test_only_one_fully_active_valid_version_is_accepted(self):
        self.assertEqual(d.active_version(deployment()), FAKE_VERSION)
        bad = [None, {}, {"deployments": []}, {"deployments": [{"strategy": "all", "versions": []}]},
               {"deployments": [{"strategy": "percentage", "versions": [{"version_id": FAKE_VERSION, "percentage": 99}]}]},
               {"deployments": [{"strategy": "percentage", "versions": [{"version_id": "wrong", "percentage": 100}]}]},
               {"deployments": [{"strategy": "percentage", "versions": [{"version_id": FAKE_VERSION, "percentage": 100},
                                                                            {"version_id": FAKE_VERSION, "percentage": 0}]}]}]
        for item in bad:
            with self.subTest(value=item), self.assertRaises(d.InspectionError):
                d.active_version(item)

    def test_version_requires_exact_source_hashes_disabled_flags_and_no_storage(self):
        good = version()
        d.verify_version(good, FAKE_VERSION)
        bad = []
        item = copy.deepcopy(good); item["id"] = "different"; bad.append(item)
        item = copy.deepcopy(good); item["compatibility_date"] = "2024-01-01"; bad.append(item)
        item = copy.deepcopy(good); item["compatibility_flags"] = ["nodejs_compat"]; bad.append(item)
        item = copy.deepcopy(good); item["main_module"] = "other.mjs"; bad.append(item)
        item = copy.deepcopy(good); item["bindings"][0]["text"] = "true"; bad.append(item)
        item = copy.deepcopy(good); item["bindings"][1]["text"] = "true"; bad.append(item)
        item = copy.deepcopy(good); item["bindings"][0]["type"] = "secret_text"; bad.append(item)
        item = copy.deepcopy(good); item["bindings"][1]["name"] = "PUSH_ENABLED"; bad.append(item)
        item = copy.deepcopy(good); item["bindings"].append({"name": "PUSH_SHARDS", "type": "durable_object_namespace"}); bad.append(item)
        item = copy.deepcopy(good); item["bindings"].append({"name": "FCM_PRIVATE_KEY", "type": "secret_text"}); bad.append(item)
        item = copy.deepcopy(good); item["migration_tag"] = "v1"; bad.append(item)
        item = copy.deepcopy(good); item["migrations"] = {"new_classes": ["PushShard"]}; bad.append(item)
        item = copy.deepcopy(good); item["containers"] = [{}]; bad.append(item)
        item = copy.deepcopy(good); item["exports"] = {"PushShard": {"type": "durable_object"}}; bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["name"] = item["modules"][1]["name"]; bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["content_type"] = "text/plain"; bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["content_base64"] = base64.b64encode(b"different").decode(); bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["content_base64"] = "!not-base64!"; bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["content_base64"] = ""; bad.append(item)
        item = copy.deepcopy(good); item["modules"][0]["content_base64"] = base64.b64encode(b"x" * (d.MAX_MODULE_BYTES + 1)).decode(); bad.append(item)
        for index, item in enumerate(bad):
            with self.subTest(mutation=index), self.assertRaises(d.InspectionError):
                d.verify_version(item, FAKE_VERSION)

    def test_snapshot_reads_only_target_and_detects_changes(self):
        calls = []
        def request(account, token, path):
            self.assertEqual((account, token), (FAKE_ACCOUNT, FAKE_TOKEN))
            calls.append(path)
            if path == path_worker(): return worker()
            if path == path_deployment(): return deployment()
            if path == path_version(): return version()
            self.fail("unexpected endpoint")
        self.assertEqual(d.read_snapshot(FAKE_ACCOUNT, FAKE_TOKEN, request), (FAKE_ID, FAKE_VERSION))
        self.assertEqual(calls, [path_worker(), path_deployment(), path_version(), path_worker(), path_deployment()])
        calls.clear()
        def changed(account, token, path):
            result = request(account, token, path)
            if path == path_worker() and calls.count(path_worker()) == 2:
                return {**result, "id": "3" * 32}
            return result
        with self.assertRaises(d.InspectionError) as caught:
            d.read_snapshot(FAKE_ACCOUNT, FAKE_TOKEN, changed)
        self.assertEqual(caught.exception.code, "changed")

    def test_subdomain_is_single_safe_label_and_never_a_url(self):
        def request(_account, _token, _path): return {"subdomain": FAKE_SUBDOMAIN}
        self.assertEqual(d.account_subdomain(FAKE_ACCOUNT, FAKE_TOKEN, request), FAKE_SUBDOMAIN)
        for name in (None, "", "UPPER", "-bad", "bad-", "evil.example", "x/health", "x" * 64):
            with self.subTest(value=name), self.assertRaises(d.InspectionError):
                d.account_subdomain(FAKE_ACCOUNT, FAKE_TOKEN, lambda *_args: {"subdomain": name})


class PublicHealthTest(unittest.TestCase):
    def get(self, status, raw):
        with patch.object(d.urllib.request, "build_opener", return_value=opener_returning(Response(status, raw))):
            return d.public_health(FAKE_SUBDOMAIN)

    def test_exact_disabled_503_and_unauthenticated_get(self):
        captured = []
        class Opener:
            def open(self, req, timeout):
                captured.append((req, timeout))
                raise urllib.error.HTTPError(req.full_url, 503, "Unavailable", {},
                                             io.BytesIO(b'{"ok":false,"service":"apu-push-wake","experimental":true}'))
        with patch.object(d.urllib.request, "build_opener", return_value=Opener()) as build:
            self.assertEqual(d.public_health(FAKE_SUBDOMAIN), "OK_DISABLED_503")
        req, timeout = captured[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertIsNone(req.data)
        self.assertEqual(req.full_url, f"https://{d.WORKER_NAME}.{FAKE_SUBDOMAIN}.workers.dev/health")
        self.assertIsNone(req.get_header("Authorization"))
        self.assertIsNone(req.get_header("Cookie"))
        self.assertEqual(timeout, 15)
        self.assertTrue(any(isinstance(h, d.urllib.request.ProxyHandler) and h.proxies == {} for h in build.call_args.args))
        self.assertTrue(any(isinstance(h, d.preflight.NoRedirect) for h in build.call_args.args))

    def test_body_and_status_classification_is_bounded_and_fail_closed(self):
        good = b'{"ok":false,"service":"apu-push-wake","experimental":true}'
        self.assertEqual(self.get(503, good), "OK_DISABLED_503")
        for raw in (b"", b"<html>" + SENTINEL.encode(), b"x" * (d.MAX_HEALTH_BYTES + 1),
                    b'{"ok":true,"service":"apu-push-wake","experimental":true}',
                    b'{"ok":false,"ok":false,"service":"apu-push-wake","experimental":true}',
                    b'{"ok":false,"service":"apu-push-wake","experimental":true,"extra":1}', b"\xff"):
            with self.subTest(raw=raw[:60]):
                self.assertEqual(self.get(503, raw), "HTTP_503_UNEXPECTED_BODY")
        for status, category in ((200, "HTTP_OTHER_2XX"), (301, "HTTP_REDIRECT_BLOCKED"), (403, "HTTP_403"),
                                 (404, "HTTP_404"), (418, "HTTP_OTHER_4XX"), (500, "HTTP_500"),
                                 (530, "HTTP_530"), (599, "HTTP_OTHER_5XX")):
            with self.subTest(status=status):
                self.assertEqual(self.get(status, good), category)

    def test_network_errors_are_categorized_without_leaking_details(self):
        errors = [(urllib.error.URLError(socket.gaierror(SENTINEL)), "DNS_UNAVAILABLE"),
                  (urllib.error.URLError(ssl.SSLError(SENTINEL)), "TLS_UNAVAILABLE"),
                  (urllib.error.URLError(socket.timeout(SENTINEL)), "TIMEOUT"),
                  (urllib.error.URLError(OSError(SENTINEL)), "NETWORK_UNAVAILABLE"),
                  (http.client.IncompleteRead(b"secret"), "NETWORK_UNAVAILABLE"),
                  (socket.timeout(SENTINEL), "TIMEOUT")]
        for error, category in errors:
            output = io.StringIO()
            with self.subTest(category=category), patch.object(d.urllib.request, "build_opener", return_value=opener_returning(error=error)), redirect_stdout(output):
                self.assertEqual(d.public_health(FAKE_SUBDOMAIN), category)
            self.assertNotIn(SENTINEL, output.getvalue())
        with patch.object(d.urllib.request, "build_opener") as unused, self.assertRaises(d.InspectionError):
            d.public_health("x.evil.example")
        unused.assert_not_called()


class FullFlowTest(unittest.TestCase):
    def test_complete_fake_provider_flow_makes_only_gets_and_does_not_leak(self):
        requests = []
        class Opener:
            def open(self, req, timeout):
                requests.append(req)
                self.assert_timeout = timeout
                selftest.assertEqual(req.get_method(), "GET")
                selftest.assertIsNone(req.data)
                if req.full_url.startswith("https://api.cloudflare.com"):
                    selftest.assertEqual(req.get_header("Authorization"), "Bearer " + FAKE_TOKEN)
                    path = req.full_url.removeprefix("https://api.cloudflare.com")
                    if path == path_worker(): result = worker()
                    elif path == path_deployment(): result = deployment()
                    elif path == path_version(): result = version()
                    elif path == f"{base()}/subdomain": result = {"subdomain": FAKE_SUBDOMAIN}
                    else: selftest.fail("unexpected API path")
                    return Response(200, reply(result))
                selftest.assertEqual(req.full_url, f"https://{d.WORKER_NAME}.{FAKE_SUBDOMAIN}.workers.dev/health")
                selftest.assertIsNone(req.get_header("Authorization"))
                selftest.assertIsNone(req.get_header("Cookie"))
                selftest.assertNotIn(d.READ_TOKEN, os.environ)
                selftest.assertNotIn(d.ACCOUNT, os.environ)
                return Response(503, b'{"ok":false,"service":"apu-push-wake","experimental":true}')
        selftest = self
        output = io.StringIO()
        def checkout(_sha):
            self.assertNotIn(d.READ_TOKEN, os.environ)
            self.assertNotIn(d.ACCOUNT, os.environ)
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "check_checkout", side_effect=checkout), \
                patch.object(d.urllib.request, "build_opener", return_value=Opener()), redirect_stdout(output):
            self.assertEqual(d.main(), 0)
        self.assertEqual(len(requests), 12)  # two 5-GET snapshots, subdomain, one public health GET
        self.assertIn("HEALTH=OK_DISABLED_503", output.getvalue())
        self.assertIn("snapshots, not a lock", output.getvalue())
        for secret in (FAKE_ACCOUNT, FAKE_TOKEN, FAKE_SUBDOMAIN, SENTINEL):
            self.assertNotIn(secret, output.getvalue())

    def test_unhealthy_or_changed_snapshots_never_claim_success(self):
        for health, before, after in (("HTTP_403", (FAKE_ID, FAKE_VERSION), (FAKE_ID, FAKE_VERSION)),
                                      ("OK_DISABLED_503", (FAKE_ID, FAKE_VERSION), ("3" * 32, FAKE_VERSION))):
            output = io.StringIO()
            with self.subTest(health=health, changed=before != after), patch.dict(os.environ, values(), clear=True), \
                    patch.object(d, "check_checkout"), patch.object(d, "read_snapshot", side_effect=[before, after]) as snapshots, \
                    patch.object(d, "account_subdomain", return_value=FAKE_SUBDOMAIN), patch.object(d, "public_health", return_value=health), redirect_stdout(output):
                self.assertEqual(d.main(), 1)
                self.assertEqual(snapshots.call_count, 2)
                self.assertNotIn("One public health GET matched", output.getvalue())
                self.assertNotIn(FAKE_ACCOUNT, output.getvalue())
                self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_bad_readback_stops_before_public_health_and_exceptions_are_redacted(self):
        for error in (d.InspectionError("version_state"), RuntimeError(SENTINEL)):
            output = io.StringIO()
            with self.subTest(error=type(error).__name__), patch.dict(os.environ, values(), clear=True), \
                    patch.object(d, "check_checkout"), patch.object(d, "read_snapshot", side_effect=error), \
                    patch.object(d, "account_subdomain") as subdomain, patch.object(d, "public_health") as health, redirect_stdout(output):
                self.assertEqual(d.main(), 1)
                subdomain.assert_not_called()
                health.assert_not_called()
            self.assertNotIn(SENTINEL, output.getvalue())
            self.assertNotIn(FAKE_ACCOUNT, output.getvalue())
            self.assertNotIn(FAKE_TOKEN, output.getvalue())


class WorkflowContractTest(unittest.TestCase):
    def test_only_marker_gated_read_job_after_owner_environment_and_checks(self):
        workflow = (d.ROOT / ".github/workflows/inspect-disabled-push.yml").read_text()
        offline = workflow.split("  offline-tests:", 1)[1].split("  protection-check:", 1)[0]
        protection = workflow.split("  protection-check:", 1)[1].split("  inspect-read-only:", 1)[0]
        inspection = workflow.split("  inspect-read-only:", 1)[1]
        self.assertIn("github.repository == 'vzhem/APUMIR'", offline)
        self.assertIn("github.ref == 'refs/heads/arena/1266946d-apumir'", offline)
        self.assertIn("contains(github.event.head_commit.message, '[inspect-disabled-push]')", offline)
        self.assertIn("needs: offline-tests", protection)
        self.assertIn("needs: protection-check", inspection)
        self.assertIn("environment: apu-push-staging", inspection)
        self.assertEqual(workflow.count("persist-credentials: false"), 3)
        self.assertEqual(workflow.count("push-infra-preflight.py --check-protection"), 2)
        self.assertEqual(workflow.count("push-disabled-deploy.py --check-head"), 2)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN", workflow)
        self.assertNotIn("APU_PUSH_FCM_SERVICE_ACCOUNT", workflow)
        self.assertNotIn("APU_FIREBASE_ANDROID_CONFIG", workflow)
        self.assertNotIn("FCM_PRIVATE_KEY", workflow)
        self.assertNotIn("npm ci", inspection)
        self.assertNotIn("wrangler deploy", inspection)
        self.assertEqual(inspection.count("APU_PUSH_CLOUDFLARE_TOKEN:"), 1)
        self.assertTrue(inspection.rstrip().endswith("run: python3 scripts/ci/inspect-disabled-push.py"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
