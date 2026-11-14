#!/usr/bin/env python3
"""Exercise the working push deployment offline, with a fabricated provider.

No network, no Cloudflare, no Google. The fake records every call so the tests
can assert what would have been sent, and the assertions never require a real
credential. The point is the safety shape: one Worker name, one upload, no
retry, no delete, and nothing secret in any output.
"""
import contextlib
import importlib.util
import io
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("push_deploy", HERE / "push-deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

ACCOUNT = "a" * 32
TOKEN = "cf-token-value-for-tests-only"
RATE_KEY = "b" * 64
FCM_JSON = json.dumps({
    "project_id": "apu-test-project",
    "client_email": "sender@apu-test-project.iam.gserviceaccount.com",
    "private_key": "-----BEGIN PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END PRIVATE KEY-----\n",
})


class FakeProvider:
    def __init__(self, settings_status=404, upload_status=200, deployments_status=200,
                 existing_bindings=None):
        self.calls = []
        self.settings_status = settings_status
        self.existing_bindings = existing_bindings or []
        self.upload_status = upload_status
        self.deployments_status = deployments_status
        self.uploaded = None

    def __call__(self, host, path, token, method="GET", body=None, maximum=None):
        self.calls.append((host, path, method))
        if path.endswith("/settings"):
            if self.settings_status == 200:
                return 200, {"result": {"bindings": self.existing_bindings}}
            return self.settings_status, None
        if method == "PUT":
            self.uploaded = body
            return self.upload_status, {"success": True}
        if path.endswith("/deployments"):
            return self.deployments_status, {"success": True, "result": {"deployments": []}}
        raise AssertionError("unexpected path " + path)


def runtime(extra=None):
    values = {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REPOSITORY": "vzhem/APUMIR",
        "GITHUB_REF": "refs/heads/arena/2d5de8a6-apumir",
        "APU_PUSH_CLOUDFLARE_ACCOUNT_ID": ACCOUNT,
        "APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN": TOKEN,
        "APU_PUSH_FCM_SERVICE_ACCOUNT": FCM_JSON,
        "APU_PUSH_RATE_KEY": RATE_KEY,
    }
    values.update(extra or {})
    return values


class DeploymentTests(unittest.TestCase):
    def test_one_upload_to_the_reviewed_name_with_both_switches_on(self):
        fake = FakeProvider()
        self.assertTrue(deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=fake))
        # Exactly one write, and it addressed the single reviewed Worker.
        writes = [call for call in fake.calls if call[2] != "GET"]
        self.assertEqual(1, len(writes))
        self.assertEqual("api.cloudflare.com", writes[0][0])
        self.assertTrue(writes[0][1].endswith("/workers/scripts/apu-push"))
        payload = fake.uploaded
        self.assertEqual("worker.mjs", payload["main_module"])
        names = {binding["name"]: binding for binding in payload["bindings"]}
        self.assertEqual("true", names["PUSH_ENABLED"]["text"])
        self.assertEqual("true", names["MAILBOX_ENABLED"]["text"])
        self.assertEqual("durable_object_namespace", names["PUSH_SHARDS"]["type"])
        self.assertEqual("PushShard", names["PUSH_SHARDS"]["class_name"])
        # Every module is uploaded as text, never as a path or an exec'd file.
        uploaded_modules = {module["name"] for module in payload["modules"]}
        self.assertEqual(set(deploy.MODULES), uploaded_modules)
        self.assertTrue(all(isinstance(module["content"], str) for module in payload["modules"]))

    def test_credentials_are_secret_bindings_and_never_plain_text(self):
        fake = FakeProvider()
        deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=fake)
        bindings = {binding["name"]: binding for binding in fake.uploaded["bindings"]}
        self.assertEqual("secret_text", bindings["FCM_PRIVATE_KEY"]["type"])
        self.assertEqual("secret_text", bindings["FCM_CLIENT_EMAIL"]["type"])
        self.assertEqual("secret_text", bindings["PUSH_RATE_KEY"]["type"])
        # The project id is not a credential and is useful in readbacks.
        self.assertEqual("plain_text", bindings["FCM_PROJECT_ID"]["type"])

    def test_the_migration_is_sent_once_and_not_replayed(self):
        fresh = FakeProvider(settings_status=404)
        deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=fresh)
        self.assertEqual({"new_sqlite_classes": ["PushShard"]}, fresh.uploaded["migrations"])
        # A Worker that already owns the shard class must not replay the
        # migration: Cloudflare rejects it for an existing class.
        existing = FakeProvider(settings_status=200, existing_bindings=[
            {"name": "PUSH_ENABLED"}, {"name": "PUSH_SHARDS", "class_name": "PushShard"},
        ])
        deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=existing)
        self.assertNotIn("migrations", existing.uploaded)
        self.assertEqual(1, len([c for c in existing.calls if c[2] == "PUT"]))
        # A Worker that exists without the shard binding is the upgrade case and
        # does get the migration.
        upgrade = FakeProvider(settings_status=200, existing_bindings=[{"name": "PUSH_ENABLED"}])
        deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=upgrade)
        self.assertEqual({"new_sqlite_classes": ["PushShard"]}, upgrade.uploaded["migrations"])

    def test_a_refused_upload_stops_without_retry_or_delete(self):
        fake = FakeProvider(upload_status=400)
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=fake)
        self.assertEqual("upload", raised.exception.code)
        methods = [call[2] for call in fake.calls]
        self.assertEqual(["GET", "PUT"], methods)
        self.assertNotIn("DELETE", methods)
        self.assertEqual(1, methods.count("PUT"))

    def test_an_unconfirmed_deployment_is_an_error_not_success(self):
        fake = FakeProvider(deployments_status=404)
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.deploy(ACCOUNT, TOKEN, deploy.service_account(FCM_JSON), RATE_KEY, request=fake)
        self.assertEqual("deployment", raised.exception.code)

    def test_malformed_inputs_fail_before_any_request(self):
        for raw in ("", "{}", "[]", json.dumps({"project_id": "x"})):
            with self.assertRaises(deploy.DeployError):
                deploy.service_account(raw)
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.service_account(json.dumps({"project_id": "apu-test-project", "client_email": "x",
                                               "private_key": "not a key"}))
        self.assertEqual("fcm_identity_invalid", raised.exception.code)
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.script_payload(deploy.service_account(FCM_JSON), "short", deploy.modules_payload())
        self.assertEqual("rate_key", raised.exception.code)

    def test_no_output_contains_a_token_key_or_account(self):
        fake = FakeProvider()
        secret_service = deploy.service_account(FCM_JSON)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            original_health, original_deploy = deploy.health, deploy.deploy
            deploy.health = lambda: (200, {"ok": True})
            deploy.deploy = lambda account, token, fcm, rate_key: True
            try:
                code = deploy.main(runtime())
            finally:
                deploy.health, deploy.deploy = original_health, original_deploy
        text = out.getvalue()
        self.assertEqual(0, code, text)
        for secret in (TOKEN, RATE_KEY, ACCOUNT, secret_service["key"], "BEGIN PRIVATE KEY"):
            self.assertNotIn(secret, text)
        self.assertIn("HEALTH=OK", text)

    def test_the_reviewed_host_matches_the_android_allowlist(self):
        config = (HERE.parents[1] / "android-app/app/src/main/java/com/vladimir/messenger/data/push/PushRuntimeConfig.kt")\
            .read_text(encoding="utf-8")
        self.assertIn('PRODUCTION_HOST: String = "' + deploy.REVIEWED_HOST + '"', config)

    def test_an_unrelated_branch_cannot_deploy(self):
        values = runtime({"GITHUB_REF": "refs/heads/main"})
        with self.assertRaises(deploy.preflight.PreflightError):
            deploy.preflight.deployment_target(values)


if __name__ == "__main__":
    unittest.main(verbosity=2)
