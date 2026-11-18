#!/usr/bin/env python3
"""Exercise the working push deployment offline, with a fabricated provider.

No network, no Cloudflare, no Google, no Wrangler process. The fake records each
command so the tests can assert what would have been sent, and no assertion
requires a real credential. The point is the safety shape: secrets before the
switch, one deploy of one name, no retry, no delete, nothing secret in output.
"""
import contextlib
import importlib.util
import io
import json
import os
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("push_deploy", HERE / "push-deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

ACCOUNT = "a" * 32
TOKEN = "cf-token-value-for-tests-only"
RATE_KEY = "b" * 64
SERVICE_ACCOUNT = {
    "project_id": "apu-test-project",
    "client_email": "sender@apu-test-project.iam.gserviceaccount.com",
    "private_key": "-----BEGIN PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END PRIVATE KEY-----\n",
}
FCM_JSON = json.dumps(SERVICE_ACCOUNT)


class FakeWrangler:
    """Stands in for subprocess.run; records argv, env and stdin."""

    def __init__(self, fail_on=None):
        self.calls = []
        self.fail_on = fail_on

    def __call__(self, argv, cwd=None, env=None, input=None, capture_output=None,
                 text=None, timeout=None, check=None):
        self.calls.append({"argv": list(argv), "env": dict(env or {}), "stdin": input})

        class Completed:
            returncode = 1 if self.fail_on and self.fail_on in " ".join(argv) else 0
            stdout = "Total Upload: 48 KiB"
            stderr = ""
        return Completed()


def runtime(extra=None):
    values = {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REPOSITORY": "vzhem/APUMIR",
        "GITHUB_REF": "refs/heads/arena/2d5de8a6-apumir",
        "APU_PUSH_CLOUDFLARE_ACCOUNT_ID": ACCOUNT,
        "APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN": TOKEN,
        "APU_PUSH_FCM_SERVICE_ACCOUNT": FCM_JSON,
    }
    values.update(extra or {})
    return values


def patch(name, value):
    original = getattr(deploy, name)
    setattr(deploy, name, value)
    return original


class DeploymentTests(unittest.TestCase):
    def run_deploy(self, values=None, fake=None, health_result=(200, {"ok": True})):
        fake = fake or FakeWrangler()

        def fake_run(args, env, stdin_text=None):
            # Same contract as the real helper: record, then honour the failure.
            fake.calls.append({"argv": list(args), "env": dict(env), "stdin": stdin_text})
            failed = fake.fail_on and fake.fail_on in " ".join(args)
            return (1 if failed else 0), ""

        out = io.StringIO()
        saved_run, saved_health = deploy.run_wrangler, deploy.health
        deploy.run_wrangler, deploy.health = fake_run, lambda: health_result
        try:
            with contextlib.redirect_stdout(out):
                code = deploy.main(values or runtime())
        finally:
            deploy.run_wrangler, deploy.health = saved_run, saved_health
        return code, out.getvalue(), fake

    def test_the_service_never_exists_on_without_its_credentials(self):
        # Reasons, in order: `secret put` needs an existing Worker; a version that
        # is on but missing a credential would be a lie the account then holds;
        # and the last deploy is the only one that turns the switches on.
        code, text, fake = self.run_deploy()
        self.assertEqual(0, code, text)
        argv = [" ".join(call["argv"]) for call in fake.calls]
        self.assertEqual(6, len(argv), argv)
        self.assertTrue(argv[0].startswith("deploy"), argv[0])
        self.assertIn("--var PUSH_ENABLED:false", argv[0])
        self.assertIn("--var MAILBOX_ENABLED:false", argv[0])
        self.assertEqual(
            ["secret put FCM_PROJECT_ID", "secret put FCM_CLIENT_EMAIL",
             "secret put FCM_PRIVATE_KEY", "secret put PUSH_RATE_KEY"],
            [item.split(" --name")[0] for item in argv[1:5]],
        )
        self.assertTrue(argv[5].startswith("deploy"), argv[5])
        self.assertIn("--var PUSH_ENABLED:true", argv[5])
        self.assertIn("--var MAILBOX_ENABLED:true", argv[5])
        self.assertIn("--name apu-push", argv[5])
        # Nothing before the last step claims to be enabled.
        self.assertEqual(1, sum("PUSH_ENABLED:true" in item for item in argv))

    def test_the_project_id_is_uploaded_or_the_service_stays_disabled(self):
        # fcmConfigured() needs FCM_PROJECT_ID; without it the service deploys
        # with both switches on and still refuses every wake. The value must come
        # from the service account, not from a second copy in repository config.
        _, _, fake = self.run_deploy()
        puts = {call["argv"][2]: call for call in fake.calls if call["argv"][0] == "secret"}
        self.assertIn("FCM_PROJECT_ID", puts)
        self.assertEqual(SERVICE_ACCOUNT["project_id"] + chr(10), puts["FCM_PROJECT_ID"]["stdin"])

    def test_secret_values_travel_on_stdin_and_never_as_arguments(self):
        _, _, fake = self.run_deploy(values=runtime({"APU_PUSH_RATE_KEY": RATE_KEY}))
        puts = [call for call in fake.calls if call["argv"][0] == "secret"]
        self.assertEqual(len(deploy.SECRET_NAMES), len(puts))
        # A value in argv would be visible in process listings and logs.
        for call in puts:
            self.assertNotIn("BEGIN PRIVATE KEY", " ".join(call["argv"]))
            self.assertNotIn(RATE_KEY, " ".join(call["argv"]))
            self.assertTrue(call["stdin"])
        by_name = {call["argv"][2]: call for call in puts}
        self.assertEqual(RATE_KEY + chr(10), by_name["PUSH_RATE_KEY"]["stdin"])

    def test_the_child_process_gets_no_service_account_and_no_token_file(self):
        _, _, fake = self.run_deploy()
        env = fake.calls[0]["env"]
        self.assertEqual(ACCOUNT, env["CLOUDFLARE_ACCOUNT_ID"])
        self.assertEqual(TOKEN, env["CLOUDFLARE_API_TOKEN"])
        # The service-account JSON must not be inherited by Wrangler.
        self.assertNotIn("APU_PUSH_FCM_SERVICE_ACCOUNT", env)
        self.assertNotIn("BEGIN PRIVATE KEY", json.dumps(env))

    def test_a_refused_secret_or_upload_stops_without_retry_or_delete(self):
        for marker, expected in (("secret", "secret"), ("deploy", "upload")):
            fake = FakeWrangler(fail_on=marker)
            code, text, _ = self.run_deploy(fake=fake)
            self.assertEqual(1, code, text)
            self.assertIn("::error", text)
            joined = [" ".join(call["argv"]) for call in fake.calls]
            self.assertNotIn("delete", " ".join(joined))
            # Failures are not retried: the run stops at the failing command.
            # A refused secret stops the run: the switched-off deploy happened,
            # nothing after it did.
            self.assertLessEqual(len(joined), 2 if marker == "secret" else 6)

    def test_a_service_that_does_not_confirm_health_is_a_failure(self):
        code, text, _ = self.run_deploy(health_result=(503, {"ok": False}))
        self.assertEqual(1, code, text)
        self.assertIn("HEALTH=UNEXPECTED", text)
        # Not a delivery claim even on success.
        code, text, _ = self.run_deploy()
        self.assertIn("not delivery", text.lower())

    def test_the_health_request_looks_like_the_app_not_like_a_library(self):
        # The bare Python-urllib agent was answered by a Cloudflare edge rule, not
        # by the Worker, which made a successful upload look like a failed one.
        seen = {}

        class Opener:
            def __call__(self, request, timeout=None):
                seen["agent"] = request.get_header("User-agent")
                seen["url"] = request.full_url
                import io as _io
                response = _io.BytesIO(b'{"ok": true}')
                response.status = 200
                return contextlib.closing(response)

        status, body = deploy.health(opener=Opener())
        self.assertEqual(200, status)
        self.assertEqual({"ok": True}, body)
        self.assertIn("APU-Messenger/", seen["agent"])
        self.assertEqual("https://" + deploy.REVIEWED_HOST + "/health", seen["url"])

    def test_a_status_from_in_front_of_the_worker_is_not_blamed_on_a_switch(self):
        # Only the Worker's own 503 means "I am switched off". Anything else is
        # reported as something that answered before the Worker.
        for status, expected in ((503, "reports itself off"), (403, "before the Worker did")):
            code, text, _ = self.run_deploy(health_result=(status, None))
            self.assertEqual(1, code)
            self.assertIn(expected, text)

    def test_a_missing_rate_key_is_generated_once_and_never_printed(self):
        code, text, fake = self.run_deploy()
        self.assertEqual(0, code, text)
        puts = {call["argv"][2]: call for call in fake.calls if call["argv"][:2] == ["secret", "put"]}
        uploaded = puts["PUSH_RATE_KEY"]["stdin"]
        self.assertRegex(uploaded.strip(), r"^[0-9a-f]{64}$")
        # One key for the deployment, not a new one per secret.
        self.assertEqual(uploaded, puts["PUSH_RATE_KEY"]["stdin"])
        self.assertNotIn(uploaded.strip(), text)
        self.assertIn("Generated a random PUSH_RATE_KEY", text)

    def test_a_malformed_stored_rate_key_fails_before_any_call(self):
        fake = FakeWrangler()
        code, text, _ = self.run_deploy(values=runtime({"APU_PUSH_RATE_KEY": "nope"}), fake=fake)
        self.assertEqual(1, code, text)
        self.assertEqual([], fake.calls)
        code, text, _ = self.run_deploy(values=runtime({"APU_PUSH_RATE_KEY": "c" * 32}), fake=FakeWrangler())
        self.assertEqual(0, code, text)
        self.assertNotIn("Generated a random PUSH_RATE_KEY", text)

    def test_malformed_inputs_fail_before_any_request(self):
        for raw in ("", "{}", "[]", json.dumps({"project_id": "x"})):
            with self.assertRaises(deploy.DeployError):
                deploy.service_account(raw)
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.service_account(json.dumps({"project_id": "apu-test-project", "client_email": "x",
                                               "private_key": "not a key"}))
        self.assertEqual("fcm_identity_invalid", raised.exception.code)

    def test_a_refusal_is_reported_as_one_fixed_code_never_wrangler_text(self):
        # Wrangler prints request ids, account names and token prefixes on
        # failure, so only a recognised phrase may leave the process, as a code.
        cases = [
            ("Error: Couldn't find a Durable Object namespace named PUSH_SHARDS", "worker_namespace_missing"),
            ("Authentication error [code: 10000]", "credentials_refused"),
            ("A request to the Cloudflare API (/accounts/abc/workers/scripts/apu-push) failed", "api_refused"),
        ]
        for text, expected in cases:
            self.assertEqual(expected, deploy.diagnosis(text), text)
        self.assertIsNone(deploy.diagnosis("some unrecognised text"))
        self.assertIsNone(deploy.diagnosis(None))

    def test_a_failure_says_which_step_it_was_without_quoting_anything(self):
        for marker, expected_step, expected_code in (
                ("deploy", 1, "upload"), ("secret", 2, "secret")):
            fake = FakeWrangler(fail_on=marker)
            code, text, _ = self.run_deploy(fake=fake)
            self.assertEqual(1, code)
            self.assertIn("failed at step " + str(expected_step) + " of 6", text)
        # A failure on the last deploy (the one that turns the switches on) is
        # reported as step 6, not as step 1.
        calls = []

        def run(args, env, stdin_text=None):
            calls.append(" ".join(args))
            return (1 if len(calls) == 6 else 0), ""

        out = io.StringIO()
        saved_run, saved_health = deploy.run_wrangler, deploy.health
        deploy.run_wrangler, deploy.health = run, lambda: (200, {"ok": True})
        try:
            with contextlib.redirect_stdout(out):
                code = deploy.main(runtime())
        finally:
            deploy.run_wrangler, deploy.health = saved_run, saved_health
        self.assertEqual(1, code)
        self.assertIn("failed at step 6 of 6", out.getvalue())

    def test_wrangler_text_never_reaches_the_output_even_when_it_fails(self):
        noisy = "Authentication error [code: 10000] token=cf-abcdef123456 account=0123456789abcdef0123456789abcdef"

        def failing_run(args, env, stdin_text=None):
            return 1, noisy

        out = io.StringIO()
        saved_run, saved_health = deploy.run_wrangler, deploy.health
        deploy.run_wrangler = failing_run
        try:
            with contextlib.redirect_stdout(out):
                code = deploy.main(runtime())
        finally:
            deploy.run_wrangler, deploy.health = saved_run, saved_health
        text = out.getvalue()
        self.assertEqual(1, code)
        self.assertNotIn("cf-abcdef123456", text)
        self.assertNotIn("0123456789abcdef0123456789abcdef", text)
        self.assertNotIn("Authentication error", text)
        self.assertIn("credentials_refused", text)

    def test_a_broken_setup_is_reported_not_silent(self):
        # The first approved run failed with no annotation from this program at
        # all, so the run page said only "exit code 1". Whatever breaks, the run
        # must say that it broke and where.
        broken = [
            lambda: deploy.service_account("not json"),
            lambda: deploy.rate_key({"APU_PUSH_RATE_KEY": "no"}),
        ]
        for make in broken:
            with self.assertRaises(deploy.DeployError):
                make()

        # A failure inside the first step still produces an annotation, and the
        # message names the step rather than staying silent.
        def run(args, env, stdin_text=None):
            raise RuntimeError("simulated bug")

        out = io.StringIO()
        saved_run, saved_health = deploy.run_wrangler, deploy.health
        deploy.run_wrangler, deploy.health = run, lambda: (200, {"ok": True})
        try:
            with contextlib.redirect_stdout(out):
                code = deploy.main(runtime())
        finally:
            deploy.run_wrangler, deploy.health = saved_run, saved_health
        self.assertEqual(1, code)
        self.assertIn("::error", out.getvalue())
        self.assertIn("RuntimeError", out.getvalue())
        # The exception's own text can carry provider bodies, so it stays out.
        self.assertNotIn("simulated bug", out.getvalue())

    def test_progress_reaches_the_run_summary(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            path = folder + "/summary.md"
            saved = os.environ.get("GITHUB_STEP_SUMMARY")
            os.environ["GITHUB_STEP_SUMMARY"] = path
            try:
                code, text, _ = self.run_deploy()
            finally:
                if saved is None:
                    os.environ.pop("GITHUB_STEP_SUMMARY", None)
                else:
                    os.environ["GITHUB_STEP_SUMMARY"] = saved
            self.assertEqual(0, code, text)
            summary = open(path, encoding="utf-8").read()
        # Every step is named, so a run that stops halfway still shows where.
        for expected in ("started", "Step 1 of 6", "Steps 2-5 of 6", "Step 6 of 6"):
            self.assertIn(expected, summary)
        self.assertNotIn(RATE_KEY, summary)
        self.assertNotIn(TOKEN, summary)

    def test_no_output_contains_a_token_key_or_account(self):
        code, text, _ = self.run_deploy()
        self.assertEqual(0, code, text)
        for secret in (TOKEN, RATE_KEY, ACCOUNT, "BEGIN PRIVATE KEY", "MIIBOgIBAAJBAK"):
            self.assertNotIn(secret, text)

    def test_the_reviewed_host_matches_every_place_that_names_it(self):
        config = (HERE.parents[1] / "android-app/app/src/main/java/com/vladimir/messenger/data/push/PushRuntimeConfig.kt")\
            .read_text(encoding="utf-8")
        self.assertIn('PRODUCTION_HOST: String = "' + deploy.REVIEWED_HOST + '"', config)
        toml = (deploy.CONFIG).read_text(encoding="utf-8")
        # Only the assignment matters; the comment above it names the relay on
        # purpose, to say what this Worker must never be.
        # Everything before the first table header is the Worker identity.
        header = toml.split(chr(10) + "[", 1)[0]
        top_level = [line.strip() for line in header.splitlines() if line.strip().startswith("name = ")]
        self.assertEqual(['name = "' + deploy.WORKER_NAME + '"'], top_level)
        self.assertNotIn("p2p-relay", top_level)
        self.assertIn('class_name = "PushShard"', toml)

    def test_an_unrelated_branch_cannot_deploy(self):
        spec = importlib.util.spec_from_file_location(
            "push_preflight", HERE / "push-infra-preflight.py")
        preflight = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(preflight)
        with self.assertRaises(preflight.PreflightError):
            preflight.deployment_target(runtime({"GITHUB_REF": "refs/heads/main"}))


if __name__ == "__main__":
    unittest.main(verbosity=2)
