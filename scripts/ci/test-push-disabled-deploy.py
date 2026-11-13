#!/usr/bin/env python3
"""Offline tests for the proposed disabled Worker creation; fake provider only."""
import base64
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("push_disabled_deploy", HERE / "push-disabled-deploy.py")
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

FAKE_ACCOUNT = "1" * 32
FAKE_TOKEN = "fake_deploy_token_1234567890"
FAKE_SHA = "a" * 40
FAKE_NAME = "apu-push-" + "a" * 20
FAKE_ID = "2" * 32
FAKE_VERSION = "12345678-1234-1234-1234-123456789abc"
FAKE_HOST = FAKE_NAME + ".example.workers.dev"


def values():
    return {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "vzhem/APUMIR",
            "GITHUB_REF": "refs/heads/arena/1266946d-apumir", "GITHUB_SHA": FAKE_SHA,
            "APU_PUSH_CLOUDFLARE_ACCOUNT_ID": FAKE_ACCOUNT,
            "APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN": FAKE_TOKEN}


def created(name=FAKE_NAME, worker_id=FAKE_ID):
    return {"success": True, "result": {"name": name, "id": worker_id,
            "subdomain": {"enabled": True, "previews_enabled": False}}}


class InputsTest(unittest.TestCase):
    def test_exact_designated_invocation_and_new_name(self):
        self.assertEqual(d.deployment_inputs(values()), (FAKE_ACCOUNT, FAKE_TOKEN, FAKE_NAME))
        self.assertNotEqual(FAKE_NAME, "p2p-relay")
        self.assertNotEqual(FAKE_NAME, "apu-push-wake-experimental")

    def test_wrong_invocation_debug_or_missing_secrets_fail_closed(self):
        changes = ({"GITHUB_ACTIONS": "false"}, {"GITHUB_REPOSITORY": "attacker/repo"},
                   {"GITHUB_REF": "refs/heads/main"}, {"GITHUB_SHA": "short"},
                   {"RUNNER_DEBUG": "1"}, {"APU_GITHUB_STEP_DEBUG": "true"},
                   {"APU_GITHUB_RUNNER_DEBUG": "TRUE"}, {"APU_PUSH_CLOUDFLARE_ACCOUNT_ID": "x"},
                   {"APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN": ""})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(d.DeployError):
                d.deployment_inputs({**values(), **change})

    def test_only_the_expected_disabled_config_is_accepted(self):
        content = d.CONFIG.read_text()
        d.validate_config()
        for modified in (content.replace('PUSH_ENABLED = "false"', 'PUSH_ENABLED = "true"'),
                         content.replace('MAILBOX_ENABLED = "false"', 'MAILBOX_ENABLED = "true"'),
                         content.replace('name = "apu-push-wake-experimental"', 'name = "p2p-relay"'),
                         content.replace('class_name = "PushShard"', 'class_name = "ExistingShard"'),
                         content.replace('workers_dev = true', 'workers_dev = false'),
                         content + '\n[env.production]\nname = "p2p-relay"\n',
                         content + '\nroutes = [{ pattern = "example.org/*" }]\n'):
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "wrangler.toml"
                path.write_text(modified)
                with self.subTest(modified=modified[-60:]), self.assertRaises(d.DeployError):
                    d.validate_config(path)

    def test_symlink_or_oversized_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            oversized = root / "big"
            oversized.write_bytes(b"x" * 32_769)
            with self.assertRaises(d.DeployError):
                d.validate_config(oversized)
            link = root / "linked"
            link.symlink_to(d.CONFIG)
            with self.assertRaises(d.DeployError):
                d.validate_config(link)

    def test_branch_head_matches_exact_ref_and_commit_or_fails_closed(self):
        env = {**values(), "GH_TOKEN": "synthetic-github-token"}
        calls = []
        def request(host, path, token):
            calls.append((host, path, token))
            return 200, {"ref": env["GITHUB_REF"], "object": {"type": "commit", "sha": FAKE_SHA}}
        d.check_branch_head(env, request)
        self.assertEqual(calls, [("api.github.com", "/repos/vzhem/APUMIR/git/ref/heads/arena/1266946d-apumir",
                                 "synthetic-github-token")])
        for status, body in ((404, None), (200, {}), (200, {"ref": "refs/heads/main", "object": {"type": "commit", "sha": FAKE_SHA}}),
                             (200, {"ref": env["GITHUB_REF"], "object": {"type": "tag", "sha": FAKE_SHA}}),
                             (200, {"ref": env["GITHUB_REF"], "object": {"type": "commit", "sha": "b" * 40}})):
            with self.subTest(status=status, body=body), self.assertRaises(d.DeployError):
                d.check_branch_head(env, lambda *_: (status, body))

    def test_head_check_removes_token_and_does_not_log_exception(self):
        secret = "SECRET_SENTINEL"
        env = {**values(), "GH_TOKEN": secret}
        output = io.StringIO()
        def fail(_values):
            self.assertNotIn("GH_TOKEN", os.environ)
            raise RuntimeError(secret)
        with patch.dict(os.environ, env, clear=True), patch.object(d, "check_branch_head", side_effect=fail), redirect_stdout(output):
            self.assertEqual(d.main_cli(["--check-head"]), 1)
        self.assertNotIn(secret, output.getvalue())

    def test_git_commands_get_a_clean_environment_and_no_secret(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            self.assertNotIn("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN", kwargs["env"])
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            self.assertNotIn("FCM_PRIVATE_KEY", kwargs["env"])
            self.assertEqual(kwargs["env"]["GIT_CONFIG_NOSYSTEM"], "1")
            self.assertIn("core.fsmonitor=false", args)
            return subprocess.CompletedProcess(args, 0, stdout=(FAKE_SHA + "\n").encode() if "rev-parse" in args else b"")
        with patch.object(d.subprocess, "run", side_effect=run), patch.dict(os.environ, {
                "APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN": FAKE_TOKEN, "GH_TOKEN": "hidden", "FCM_PRIVATE_KEY": "hidden"}):
            d.check_checkout(FAKE_SHA)
        self.assertEqual(len(calls), 2)

    def test_changed_checkout_never_passes(self):
        def run(args, **_kwargs):
            return subprocess.CompletedProcess(args, 0, stdout=(FAKE_SHA + "\n").encode() if "rev-parse" in args else b" M script")
        with patch.object(d.subprocess, "run", side_effect=run), self.assertRaises(d.DeployError):
            d.check_checkout(FAKE_SHA)


class PayloadTest(unittest.TestCase):
    def test_exact_disabled_bindings_migration_and_checked_in_modules(self):
        payload = d.version_payload()
        self.assertEqual(set(payload), {"main_module", "compatibility_date", "modules", "bindings", "migrations"})
        self.assertEqual(payload["main_module"], "worker.mjs")
        self.assertEqual(payload["compatibility_date"], "2025-07-18")
        self.assertEqual(payload["bindings"], [
            {"type": "plain_text", "name": "PUSH_ENABLED", "text": "false"},
            {"type": "plain_text", "name": "MAILBOX_ENABLED", "text": "false"},
            {"type": "durable_object_namespace", "name": "PUSH_SHARDS", "class_name": "PushShard"},
        ])
        self.assertEqual(payload["migrations"], {"new_tag": "v1", "new_sqlite_classes": ["PushShard"]})
        self.assertEqual([entry["name"] for entry in payload["modules"]], list(d.MODULES))
        for entry in payload["modules"]:
            self.assertEqual(set(entry), {"name", "content_type", "content_base64"})
            self.assertEqual(entry["content_type"], "application/javascript+module")
            self.assertEqual(base64.b64decode(entry["content_base64"], validate=True), (d.PROJECT / entry["name"]).read_bytes())
        self.assertLess(len(json.dumps(payload)), d.MAX_REQUEST_BYTES)
        self.assertNotIn("FCM_PRIVATE_KEY", json.dumps(payload["bindings"]))
        self.assertNotIn("PUSH_RATE_KEY", json.dumps(payload["bindings"]))
        self.assertNotIn("script_name", json.dumps(payload["bindings"]))

    def test_missing_symlink_invalid_utf8_and_oversized_module_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in d.MODULES:
                (root / name).write_bytes(b"// test\n")
            for bad in (b"", b"\xff", b"x" * (d.MAX_MODULE_BYTES + 1)):
                (root / d.MODULES[0]).write_bytes(bad)
                with self.subTest(length=len(bad)), self.assertRaises(d.DeployError):
                    d.version_payload(root)
            (root / d.MODULES[0]).unlink()
            (root / d.MODULES[0]).symlink_to(d.PROJECT / d.MODULES[0])
            with self.assertRaises(d.DeployError):
                d.version_payload(root)
            (root / d.MODULES[0]).unlink()
            with self.assertRaises(d.DeployError):
                d.version_payload(root)

    def test_total_payload_budget_is_checked_before_any_provider_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in d.MODULES:
                (root / name).write_bytes(b"x" * d.MAX_MODULE_BYTES)
            with self.assertRaises(d.DeployError) as error:
                d.version_payload(root)
            self.assertEqual(error.exception.code, "modules")

    def test_mutated_payload_is_not_uploaded(self):
        for change in ("enabled", "extra_binding", "migration", "module"):
            payload = copy.deepcopy(d.version_payload())
            if change == "enabled":
                payload["bindings"][0]["text"] = "true"
            if change == "extra_binding":
                payload["bindings"].append({"type": "secret_text", "name": "FCM_PRIVATE_KEY", "text": "fake"})
            if change == "migration":
                payload["migrations"]["deleted_classes"] = ["Other"]
            if change == "module":
                payload["modules"][0]["content_base64"] = "eA=="
            with self.subTest(change=change), patch.object(d, "post_json") as post:
                with self.assertRaises(d.DeployError):
                    d.create_disabled_version(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, payload, post=post)
                post.assert_not_called()


class FakeProvider:
    """Models create-only by name and version upload by a returned immutable ID."""
    def __init__(self):
        self.workers = {"p2p-relay": {"id": "3" * 32, "version": "existing-relay"},
                        "apu-push-wake-experimental": {"id": "4" * 32, "version": "other"}}
        self.calls = []
        self.lose_creation_reply = False

    def post(self, path, token, payload):
        self.calls.append((path, token, copy.deepcopy(payload)))
        self_account_path = f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers"
        if path == self_account_path:
            name = payload["name"]
            if name in self.workers:
                return 409, None
            self.workers[name] = {"id": FAKE_ID, "version": None}
            if self.lose_creation_reply:
                raise d.DeployError("provider")
            return 200, created(name)
        if path == self_account_path + "/" + FAKE_ID + "/versions?deploy=true":
            self.workers[FAKE_NAME]["version"] = copy.deepcopy(payload)
            return 200, {"success": True, "result": {"id": FAKE_VERSION}}
        raise AssertionError("unexpected target")


class ProviderGuardTest(unittest.TestCase):
    def test_create_and_version_use_only_fresh_id_never_existing_workers(self):
        fake = FakeProvider()
        before = copy.deepcopy(fake.workers)
        payload = d.version_payload()
        worker_id = d.create_worker(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_NAME, fake.post)
        self.assertEqual(d.create_disabled_version(FAKE_ACCOUNT, FAKE_TOKEN, worker_id, payload, fake.post), FAKE_VERSION)
        self.assertEqual(fake.workers["p2p-relay"], before["p2p-relay"])
        self.assertEqual(fake.workers["apu-push-wake-experimental"], before["apu-push-wake-experimental"])
        self.assertEqual(fake.workers[FAKE_NAME]["version"], payload)
        self.assertEqual(len(fake.calls), 2)
        self.assertEqual(fake.calls[0][2], {"name": FAKE_NAME, "logpush": False,
                                        "subdomain": {"enabled": True, "previews_enabled": False}})
        self.assertEqual(fake.calls[1][0], f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers/{FAKE_ID}/versions?deploy=true")
        self.assertNotIn("p2p-relay", fake.calls[1][0])

    def test_existing_name_or_uncertain_create_never_uploads_version(self):
        fake = FakeProvider()
        fake.lose_creation_reply = True
        with self.assertRaises(d.DeployError) as error:
            d.create_worker(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_NAME, fake.post)
        self.assertEqual(error.exception.code, "create_uncertain")
        self.assertIn(FAKE_NAME, fake.workers)  # model provider commit then timeout
        self.assertIsNone(fake.workers[FAKE_NAME]["version"])
        fake.lose_creation_reply = False
        with self.assertRaises(d.DeployError) as error:
            d.create_worker(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_NAME, fake.post)
        self.assertEqual(error.exception.code, "create_uncertain")
        self.assertIsNone(fake.workers[FAKE_NAME]["version"])
        self.assertEqual(len(fake.calls), 2)
        self.assertTrue(all("/versions" not in call[0] for call in fake.calls))

    def test_malformed_creation_response_never_yields_a_worker_id(self):
        invalid = [None, {}, {"success": False, "result": {}}, created("p2p-relay"), created(worker_id="../p2p-relay"),
                   {"success": True, "result": {"id": FAKE_ID, "name": FAKE_NAME}},
                   {"success": True, "result": {"id": FAKE_ID, "name": FAKE_NAME,
                        "subdomain": {"enabled": True, "previews_enabled": True}}}]
        for result in invalid:
            with self.subTest(result=result), self.assertRaises(d.DeployError):
                d.create_worker(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_NAME, lambda *_: (200, result))
        with self.assertRaises(d.DeployError):
            d.create_worker(FAKE_ACCOUNT, FAKE_TOKEN, "p2p-relay", lambda *_: (200, created()))

    def test_version_failure_is_uncertain_not_automatically_cleaned(self):
        with self.assertRaises(d.DeployError) as error:
            d.create_disabled_version(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, d.version_payload(), lambda *_: (500, None))
        self.assertEqual(error.exception.code, "version_uncertain")
        with self.assertRaises(d.DeployError):
            d.create_disabled_version(FAKE_ACCOUNT, FAKE_TOKEN, "p2p-relay", d.version_payload(),
                                      lambda *_: self.fail("invalid ID sent"))

    def test_version_readback_requires_exact_source_disabled_flags_and_local_shard(self):
        payload = d.version_payload()
        item = {"id": FAKE_VERSION, "compatibility_date": "2025-07-18", "main_module": "worker.mjs",
                "bindings": copy.deepcopy(payload["bindings"]), "modules": copy.deepcopy(payload["modules"])}
        calls = []
        def request(path, token):
            calls.append((path, token))
            return 200, {"success": True, "result": item}
        d.verify_new_version(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_VERSION, payload, request)
        self.assertEqual(calls, [(f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers/{FAKE_ID}/versions/{FAKE_VERSION}?include=modules", FAKE_TOKEN)])
        # Cloudflare's documentation shows a timestamp representation too.
        item["compatibility_date"] = "2025-07-18T00:00:00Z"
        d.verify_new_version(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_VERSION, payload, request)
        variants = []
        for field in ("push", "mailbox", "foreign", "dispatch", "extra", "duplicate", "id", "date",
                      "flags", "main", "source", "source_type", "missing_module", "duplicate_module"):
            changed = copy.deepcopy(item)
            if field == "push": changed["bindings"][0]["text"] = "true"
            if field == "mailbox": changed["bindings"][1]["text"] = "true"
            if field == "foreign": changed["bindings"][2]["script_name"] = "p2p-relay"
            if field == "dispatch": changed["bindings"][2]["dispatch_namespace"] = "foreign"
            if field == "extra": changed["bindings"].append({"name": "FCM_PRIVATE_KEY", "type": "secret_text"})
            if field == "duplicate": changed["bindings"][1] = changed["bindings"][0]
            if field == "id": changed["id"] = "other"
            if field == "date": changed["compatibility_date"] = "2025-07-19"
            if field == "flags": changed["compatibility_flags"] = ["nodejs_compat"]
            if field == "main": changed["main_module"] = "other.mjs"
            if field == "source": changed["modules"][0]["content_base64"] = "YQ=="
            if field == "source_type": changed["modules"][0]["content_type"] = "text/plain"
            if field == "missing_module": changed["modules"].pop()
            if field == "duplicate_module": changed["modules"][1] = changed["modules"][0]
            variants.append((field, changed))
        for field, changed in variants:
            with self.subTest(field=field), self.assertRaises(d.DeployError):
                d.verify_new_version(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_VERSION, payload,
                                     lambda *_: (200, {"success": True, "result": changed}))

    def test_active_deployment_requires_matching_worker_identity_and_only_the_new_version(self):
        worker = {"id": FAKE_ID, "name": FAKE_NAME}
        deployment = {"strategy": "percentage", "versions": [{"version_id": FAKE_VERSION, "percentage": 100}]}
        calls = []
        def request(host, path, token):
            calls.append((host, path, token))
            result = {"deployments": [deployment]} if "/scripts/" in path else worker
            return 200, {"success": True, "result": result}
        d.verify_active_deployment(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_NAME, FAKE_VERSION, request)
        base = f"/client/v4/accounts/{FAKE_ACCOUNT}/workers"
        self.assertEqual([entry[1] for entry in calls], [f"{base}/workers/{FAKE_NAME}",
            f"{base}/scripts/{FAKE_NAME}/deployments?page=1&per_page=1", f"{base}/workers/{FAKE_NAME}"])
        for bad in ({"strategy": "percentage", "versions": [{"version_id": FAKE_VERSION, "percentage": 99}]},
                    {"strategy": "percentage", "versions": [{"version_id": "other", "percentage": 100}]},
                    {"strategy": "percentage", "versions": [{"version_id": FAKE_VERSION, "percentage": 100},
                                                             {"version_id": "other", "percentage": 0}]},
                    {"strategy": "none", "versions": [{"version_id": FAKE_VERSION, "percentage": 100}]}, {}):
            with self.subTest(bad=bad), self.assertRaises(d.DeployError):
                d.verify_active_deployment(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_NAME, FAKE_VERSION,
                    lambda _host, path, _token: (200, {"success": True, "result":
                        {"deployments": [bad]} if "/scripts/" in path else worker}))
        # A rename/replacement after the deployment read also fails closed.
        count = 0
        def renamed(_host, path, _token):
            nonlocal count
            if "/scripts/" in path:
                return 200, {"success": True, "result": {"deployments": [deployment]}}
            count += 1
            return 200, {"success": True, "result": worker if count == 1 else {**worker, "id": "3" * 32}}
        with self.assertRaises(d.DeployError):
            d.verify_active_deployment(FAKE_ACCOUNT, FAKE_TOKEN, FAKE_ID, FAKE_NAME, FAKE_VERSION, renamed)

    def test_existing_subdomain_is_strictly_validated_before_creation(self):
        request = lambda *_: (200, {"success": True, "result": {"subdomain": "account-1"}})
        self.assertEqual(d.workers_subdomain(FAKE_ACCOUNT, FAKE_TOKEN, request), "account-1")
        for subdomain in ("evil.example", "UPPER", "-invalid", "x/health", ""):
            with self.subTest(subdomain=subdomain), self.assertRaises(d.DeployError):
                d.workers_subdomain(FAKE_ACCOUNT, FAKE_TOKEN,
                                    lambda *_: (200, {"success": True, "result": {"subdomain": subdomain}}))

    def test_api_request_is_one_fixed_host_post_and_never_follows_redirect(self):
        class Response(io.BytesIO):
            status = 200
        class Opener:
            def open(self, request, timeout):
                self.request, self.timeout = request, timeout
                return Response(b'{"success":true,"result":{}}')
        opener = Opener()
        path = f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers"
        with patch.object(d.urllib.request, "build_opener", return_value=opener) as build:
            self.assertEqual(d.post_json(path, FAKE_TOKEN, {"name": FAKE_NAME}), (200, {"success": True, "result": {}}))
        self.assertEqual(opener.request.full_url, "https://api.cloudflare.com" + path)
        self.assertEqual(opener.request.get_method(), "POST")
        self.assertEqual(opener.request.get_header("Authorization"), "Bearer " + FAKE_TOKEN)
        self.assertEqual(json.loads(opener.request.data), {"name": FAKE_NAME})
        self.assertEqual(opener.timeout, 45)
        self.assertTrue(any(isinstance(arg, d.preflight.NoRedirect) for arg in build.call_args.args))
        self.assertNotIn(FAKE_TOKEN, opener.request.full_url)
        with patch.object(d.urllib.request, "build_opener") as never:
            with self.assertRaises(d.DeployError):
                d.post_json("/client/v4/evil", FAKE_TOKEN, {})
            never.assert_not_called()

    def test_version_source_readback_is_bounded_fixed_host_get_without_redirects(self):
        class Response(io.BytesIO):
            status = 200
        class Opener:
            def __init__(self, content): self.content = content
            def open(self, request, timeout):
                self.request, self.timeout = request, timeout
                return Response(self.content)
        path = f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers/{FAKE_ID}/versions/{FAKE_VERSION}?include=modules"
        opener = Opener(b'{"success":true,"result":{}}')
        with patch.object(d.urllib.request, "build_opener", return_value=opener) as build:
            self.assertEqual(d.version_readback_json(path, FAKE_TOKEN), (200, {"success": True, "result": {}}))
        self.assertEqual(opener.request.full_url, "https://api.cloudflare.com" + path)
        self.assertEqual(opener.request.get_method(), "GET")
        self.assertEqual(opener.request.get_header("Authorization"), "Bearer " + FAKE_TOKEN)
        self.assertEqual(opener.timeout, 15)
        self.assertTrue(any(isinstance(arg, d.preflight.NoRedirect) for arg in build.call_args.args))
        with patch.object(d.urllib.request, "build_opener") as never, self.assertRaises(d.DeployError):
            d.version_readback_json(path.replace(FAKE_ID, "p2p-relay"), FAKE_TOKEN)
        never.assert_not_called()
        with patch.object(d.urllib.request, "build_opener", return_value=Opener(b"x" * (d.MAX_VERSION_READBACK_BYTES + 1))), \
                self.assertRaises(d.DeployError):
            d.version_readback_json(path, FAKE_TOKEN)

    def test_http_error_bodies_are_discarded(self):
        class Opener:
            def open(self, *_args, **_kwargs):
                raise urllib.error.HTTPError("https://api.cloudflare.com", 409, "private", {}, io.BytesIO(b"secret"))
        with patch.object(d.urllib.request, "build_opener", return_value=Opener()):
            self.assertEqual(d.post_json(f"/client/v4/accounts/{FAKE_ACCOUNT}/workers/workers", FAKE_TOKEN, {}), (409, None))

    def test_disabled_health_requires_strict_503_payload_and_no_redirect(self):
        class Response(io.BytesIO):
            code = 503
        class Opener:
            def __init__(self, response):
                self.response = response
            def open(self, request, timeout):
                self.request, self.url, self.timeout = request, request.full_url, timeout
                # Model workers.dev Browser Integrity Check, which can block
                # generic library clients before the Worker sees the request.
                if request.get_header("User-agent") != "APU-Push-Disabled-Health/1.0 (+https://github.com/vzhem/APUMIR)":
                    raise urllib.error.HTTPError(request.full_url, 403, "blocked by test proxy", {}, io.BytesIO(b"private"))
                return self.response
        good = Opener(Response(b'{"ok":false,"service":"apu-push-wake","experimental":true}'))
        with patch.object(d.urllib.request, "build_opener", return_value=good) as build:
            d.disabled_health(FAKE_HOST)
            self.assertEqual(good.url, "https://" + FAKE_HOST + "/health")
            self.assertEqual(good.request.get_method(), "GET")
            self.assertIsNone(good.request.get_header("Authorization"))
            self.assertEqual(good.request.get_header("Cache-control"), "no-cache")
            self.assertTrue(any(isinstance(arg, d.preflight.NoRedirect) for arg in build.call_args.args))
        for payload in (b'{"ok":true,"service":"apu-push-wake","experimental":true}',
                        b'{"ok":0,"service":"apu-push-wake","experimental":1}',
                        b'{"ok":false,"ok":false,"service":"apu-push-wake","experimental":true}', b'not json', b'x' * 1025):
            with patch.object(d.urllib.request, "build_opener", return_value=Opener(Response(payload))), \
                    self.assertRaises(d.DeployError):
                d.disabled_health(FAKE_HOST)
        with self.assertRaises(d.DeployError):
            d.disabled_health("p2p-relay.example.workers.dev")


class CompleteFakeProviderTest(unittest.TestCase):
    """Exercise the real main/client stack with an in-memory HTTPS provider, never the account."""
    def test_create_readback_health_and_duplicate_creation_fail_closed(self):
        class Response(io.BytesIO):
            def __init__(self, body, status=200):
                super().__init__(json.dumps(body, separators=(",", ":")).encode())
                self.status = self.code = status
        class Provider:
            def __init__(self):
                self.created = False
                self.uploaded = None
                self.calls = []
                self.existing_relay = {"name": "p2p-relay", "source": "untouched", "bindings": ["original"]}
            def open(self, req, timeout):
                from urllib.parse import urlsplit
                url = urlsplit(req.full_url)
                self.calls.append((req.get_method(), url.hostname, url.path, url.query))
                if url.hostname == FAKE_HOST:
                    assert req.get_method() == "GET" and url.path == "/health" and url.query == ""
                    assert req.get_header("Authorization") is None
                    assert req.get_header("User-agent") == "APU-Push-Disabled-Health/1.0 (+https://github.com/vzhem/APUMIR)"
                    return Response({"ok": False, "service": "apu-push-wake", "experimental": True}, 503)
                assert url.hostname == "api.cloudflare.com"
                assert req.get_header("Authorization") == "Bearer " + FAKE_TOKEN
                base = f"/client/v4/accounts/{FAKE_ACCOUNT}/workers"
                if req.get_method() == "GET" and url.path == base + "/subdomain":
                    return Response({"success": True, "result": {"subdomain": "example"}})
                if req.get_method() == "POST" and url.path == base + "/workers":
                    assert json.loads(req.data) == {"name": FAKE_NAME, "logpush": False,
                                                  "subdomain": {"enabled": True, "previews_enabled": False}}
                    if self.created:
                        raise urllib.error.HTTPError(req.full_url, 409, "private", {}, io.BytesIO(b"private"))
                    self.created = True
                    return Response(created())
                if req.get_method() == "POST" and url.path == base + f"/workers/{FAKE_ID}/versions":
                    assert self.created and self.uploaded is None and url.query == "deploy=true"
                    self.uploaded = json.loads(req.data)
                    return Response({"success": True, "result": {"id": FAKE_VERSION}})
                if req.get_method() == "GET" and url.path == base + f"/workers/{FAKE_ID}/versions/{FAKE_VERSION}":
                    assert url.query == "include=modules" and self.uploaded is not None
                    item = {key: self.uploaded[key] for key in ("main_module", "compatibility_date", "bindings", "modules")}
                    return Response({"success": True, "result": {"id": FAKE_VERSION, **item}})
                if req.get_method() == "GET" and url.path == base + "/workers/" + FAKE_NAME:
                    return Response({"success": True, "result": {"id": FAKE_ID, "name": FAKE_NAME}})
                if req.get_method() == "GET" and url.path == base + "/scripts/" + FAKE_NAME + "/deployments":
                    assert url.query == "page=1&per_page=1"
                    return Response({"success": True, "result": {"deployments": [{"strategy": "percentage", "versions":
                        [{"version_id": FAKE_VERSION, "percentage": 100}]}]}})
                raise AssertionError("unexpected fake request")
        fake = Provider()
        old_relay = copy.deepcopy(fake.existing_relay)
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "check_checkout"), \
                patch.object(d.urllib.request, "build_opener", return_value=fake), redirect_stdout(output):
            self.assertEqual(d.main(), 0)
            calls_after_first = list(fake.calls)
            # A repeated approved commit cannot fall back to an update/version POST.
            self.assertEqual(d.main(), 1)
        self.assertEqual(fake.existing_relay, old_relay)
        self.assertEqual(fake.uploaded, d.version_payload())
        self.assertEqual(len(calls_after_first), 8)
        self.assertEqual(sum(1 for method, _, path, _ in fake.calls if method == "POST" and path.endswith("/versions")), 1)
        self.assertEqual(len(fake.calls), len(calls_after_first) + 2)  # subdomain GET and failed create only
        self.assertNotIn(FAKE_TOKEN, output.getvalue())
        self.assertIn("nothing was deleted", output.getvalue().lower())


class WorkflowContractTest(unittest.TestCase):
    def test_credentialed_job_is_marker_gated_and_requires_owner_environment(self):
        workflow = (d.ROOT / ".github/workflows/deploy-disabled-push.yml").read_text()
        offline = workflow.split("  offline-tests:", 1)[1].split("  protection-check:", 1)[0]
        protection = workflow.split("  protection-check:", 1)[1].split("  create-disabled:", 1)[0]
        creation = workflow.split("  create-disabled:", 1)[1]
        self.assertIn("github.repository == 'vzhem/APUMIR'", offline)
        self.assertIn("github.ref == 'refs/heads/arena/1266946d-apumir'", offline)
        self.assertIn("contains(github.event.head_commit.message, '[deploy-push-disabled]')", offline)
        self.assertIn("needs: offline-tests", protection)
        self.assertIn("needs: protection-check", creation)
        self.assertIn("environment: apu-push-staging", creation)
        self.assertEqual(workflow.count("persist-credentials: false"), 3)
        self.assertEqual(workflow.count("push-infra-preflight.py --check-protection"), 2)
        self.assertEqual(workflow.count("push-disabled-deploy.py --check-head"), 2)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("pull_request_target:", workflow)
        self.assertNotIn("APU_PUSH_FCM_SERVICE_ACCOUNT", creation)
        self.assertNotIn("APU_FIREBASE_ANDROID_CONFIG", creation)
        self.assertNotIn("FCM_PRIVATE_KEY", creation)
        self.assertEqual(creation.count("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN:"), 1)
        self.assertNotIn("npm ci", creation)
        self.assertNotIn("wrangler deploy", creation)
        self.assertTrue(creation.rstrip().endswith("run: python3 scripts/ci/push-disabled-deploy.py"))


class MainTest(unittest.TestCase):
    def test_success_requires_guards_then_create_version_and_health_in_order(self):
        calls = []
        def guard(name, result=None):
            def run(*_args):
                calls.append(name)
                return result
            return run
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), \
                patch.object(d, "validate_config", side_effect=guard("config")), \
                patch.object(d, "check_checkout", side_effect=guard("checkout")), \
                patch.object(d, "version_payload", side_effect=guard("payload", {})), \
                patch.object(d, "workers_subdomain", side_effect=guard("subdomain", "example")), \
                patch.object(d, "create_worker", side_effect=guard("create", FAKE_ID)), \
                patch.object(d, "create_disabled_version", side_effect=guard("version", FAKE_VERSION)), \
                patch.object(d, "verify_new_version", side_effect=guard("readback")), \
                patch.object(d, "verify_active_deployment", side_effect=guard("active")), \
                patch.object(d, "disabled_health", side_effect=guard("health")), redirect_stdout(output):
            self.assertEqual(d.main(), 0)
        self.assertEqual(calls, ["config", "checkout", "payload", "subdomain", "create", "version", "readback", "active", "health"])
        self.assertIn(FAKE_HOST, output.getvalue())
        self.assertNotIn(FAKE_ACCOUNT, output.getvalue())
        self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_failed_create_stops_before_version_and_exposes_only_name(self):
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "validate_config"), \
                patch.object(d, "check_checkout"), patch.object(d, "version_payload", return_value={}), \
                patch.object(d, "workers_subdomain", return_value="example"), \
                patch.object(d, "create_worker", side_effect=d.DeployError("create_uncertain")), \
                patch.object(d, "create_disabled_version") as version, redirect_stdout(output):
            self.assertEqual(d.main(), 1)
            version.assert_not_called()
        self.assertIn(FAKE_NAME, output.getvalue())
        self.assertIn("No version was sent", output.getvalue())
        self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_failed_readback_does_not_claim_health_or_retry(self):
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "validate_config"), \
                patch.object(d, "check_checkout"), patch.object(d, "version_payload", return_value={}), \
                patch.object(d, "workers_subdomain", return_value="example"), \
                patch.object(d, "create_worker", return_value=FAKE_ID), \
                patch.object(d, "create_disabled_version", return_value=FAKE_VERSION), \
                patch.object(d, "verify_new_version", side_effect=d.DeployError("version_readback")), \
                patch.object(d, "disabled_health") as health, redirect_stdout(output):
            self.assertEqual(d.main(), 1)
            health.assert_not_called()
        self.assertIn("version source and metadata could not be verified", output.getvalue())
        self.assertIn("nothing was deleted", output.getvalue())
        self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_failed_deployment_readback_does_not_claim_health_or_retry(self):
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "validate_config"), \
                patch.object(d, "check_checkout"), patch.object(d, "version_payload", return_value={}), \
                patch.object(d, "workers_subdomain", return_value="example"), \
                patch.object(d, "create_worker", return_value=FAKE_ID), \
                patch.object(d, "create_disabled_version", return_value=FAKE_VERSION), \
                patch.object(d, "verify_new_version"), \
                patch.object(d, "verify_active_deployment", side_effect=d.DeployError("deployment_readback")), \
                patch.object(d, "disabled_health") as health, redirect_stdout(output):
            self.assertEqual(d.main(), 1)
            health.assert_not_called()
        self.assertIn("active deployment could not be verified", output.getvalue())
        self.assertIn("nothing was deleted", output.getvalue())
        self.assertNotIn(FAKE_TOKEN, output.getvalue())

    def test_failed_health_reports_uncertain_result_and_no_cleanup(self):
        output = io.StringIO()
        with patch.dict(os.environ, values(), clear=True), patch.object(d, "validate_config"), \
                patch.object(d, "check_checkout"), patch.object(d, "version_payload", return_value={}), \
                patch.object(d, "workers_subdomain", return_value="example"), \
                patch.object(d, "create_worker", return_value=FAKE_ID), patch.object(d, "create_disabled_version", return_value=FAKE_VERSION) as version, \
                patch.object(d, "verify_new_version"), patch.object(d, "verify_active_deployment"), \
                patch.object(d, "disabled_health", side_effect=d.DeployError("health")), redirect_stdout(output):
            self.assertEqual(d.main(), 1)
            version.assert_called_once()
        self.assertIn("may exist", output.getvalue())
        self.assertIn("nothing was deleted", output.getvalue())
        self.assertNotIn(FAKE_TOKEN, output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
