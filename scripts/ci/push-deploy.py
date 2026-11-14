#!/usr/bin/env python3
"""Deploy the dedicated push Worker with its storage and provider credentials.

This is the *working* service, not the storage-free prototype: the same Worker
source, plus the Durable Object namespace, provider credentials as encrypted
secrets, and both switches on.

Why Wrangler and not a hand-written upload: the prototype's create/version calls
were verified against the real account, but they deliberately omit storage and
secrets. Rather than guess a second API surface for those, this script lets
Wrangler - the documented path, already pinned in the project - read the
configuration file and perform the upload. Everything before and after it stays
explicit and reviewable.

Order matters and is deliberate:
  1. secrets are uploaded first, so the version that turns the switches on can
     never start without the credentials it needs;
  2. one deploy of `wrangler.push.toml` with both switches overridden to "true";
  3. one public health request, which is the only honest signal available here.

No retries, no deletes, no other Worker names, no secret values printed.
"""

import os
import re
import secrets as random_secrets
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "tools" / "worker" / "push"
CONFIG = PROJECT / "wrangler.push.toml"
WRANGLER = PROJECT / "node_modules" / ".bin" / "wrangler"
WORKER_NAME = "apu-push"
REVIEWED_HOST = WORKER_NAME + ".apuchat.workers.dev"
SECRET_NAMES = ("FCM_CLIENT_EMAIL", "FCM_PRIVATE_KEY", "PUSH_RATE_KEY")


class DeployError(Exception):
    """Fixed code only; never provider text, tokens or account identifiers."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


def service_account(raw):
    """Parse the service-account JSON without echoing any field."""
    import json

    try:
        if not isinstance(raw, str) or not raw or len(raw) > 16 * 1024:
            raise DeployError("fcm_json_invalid")
        data = json.loads(raw)
    except (ValueError, TypeError, RecursionError):
        raise DeployError("fcm_json_invalid") from None
    if not isinstance(data, dict):
        raise DeployError("fcm_json_invalid")
    project = data.get("project_id")
    email = data.get("client_email")
    key = data.get("private_key")
    if not isinstance(project, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{4,62}", project):
        raise DeployError("fcm_identity_invalid")
    if not isinstance(email, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+", email):
        raise DeployError("fcm_identity_invalid")
    if not isinstance(key, str) or not key.startswith("-----BEGIN PRIVATE KEY-----"):
        raise DeployError("fcm_json_invalid")
    return {"project": project, "email": email, "key": key}


def run_wrangler(args, env, stdin_text=None):
    """Run one Wrangler command with output suppressed; return (code, stdout).

    Wrangler can print account, route and provider details, so nothing it writes
    goes to this program's output. Only the exit code and a health answer cross
    that boundary.
    """
    if not WRANGLER.is_file():
        raise DeployError("wrangler_missing")
    try:
        completed = subprocess.run(
            [str(WRANGLER), *args],
            cwd=str(PROJECT),
            env=env,
            input=stdin_text,
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise DeployError("wrangler") from None
    return completed.returncode, completed.stdout or ""


def upload_secrets(env, fcm, rate_key):
    """PUT each secret through Wrangler's own secret path, one at a time."""
    values = {
        "FCM_CLIENT_EMAIL": fcm["email"],
        "FCM_PRIVATE_KEY": fcm["key"],
        "PUSH_RATE_KEY": rate_key,
    }
    for name in SECRET_NAMES:
        code, _ = run_wrangler(
            ["secret", "put", name, "--config", str(CONFIG.relative_to(PROJECT))],
            env, stdin_text=values[name] + "\n",
        )
        if code != 0:
            raise DeployError("secret")
    return True


def deploy(env):
    """One deploy with both switches on; the plain-text ones are not secrets."""
    code, _ = run_wrangler([
        "deploy", "--config", str(CONFIG.relative_to(PROJECT)),
        "--var", "PUSH_ENABLED:true", "--var", "MAILBOX_ENABLED:true",
        "--name", WORKER_NAME,
    ], env)
    if code != 0:
        raise DeployError("upload")
    return True


def health(host=REVIEWED_HOST, opener=urllib.request.urlopen):
    """One public GET: the deployed service describing itself. No credentials."""
    request = urllib.request.Request("https://" + host + "/health", headers={"Accept": "application/json"})
    try:
        with opener(request, timeout=30) as response:
            body = __import__("json").loads(response.read(4096).decode("utf-8"))
            return response.status, body
    except urllib.error.HTTPError as error:
        error.close()
        return error.code, None
    except (OSError, ValueError, TypeError, UnicodeError):
        raise DeployError("health") from None


def rate_key(values):
    """Use the stored key if there is one; otherwise generate exactly one.

    A generated key is random per deployment and is not persisted anywhere by
    this program. It only needs to be stable for the lifetime of the deployed
    version: it is the HMAC key for hashing a source address before it is stored,
    not a credential that any client holds.
    """
    stored = values.get("APU_PUSH_RATE_KEY", "")
    if stored:
        if not re.fullmatch(r"[0-9a-f]{32,128}", stored):
            raise DeployError("rate_key")
        return stored, False
    return random_secrets.token_hex(32), True


MESSAGES = {
    "invocation": "This deployment can run only from the designated GitHub Actions branch.",
    "debug": "Disable GitHub Actions debug logging before deploying.",
    "cloudflare_account_invalid": "The Cloudflare account ID format is invalid.",
    "cloudflare_token_invalid": "The Cloudflare deploy token format is invalid.",
    "fcm_json_invalid": "APU_PUSH_FCM_SERVICE_ACCOUNT is missing or malformed.",
    "fcm_identity_invalid": "The service-account project or email is malformed.",
    "rate_key": "APU_PUSH_RATE_KEY is present but has the wrong format (32-128 hex characters).",
    "wrangler_missing": "Wrangler is not installed; run npm ci in tools/worker/push first.",
    "wrangler": "Wrangler could not be started.",
    "secret": "A Worker secret was refused. The service may be partially configured; inspect before retrying.",
    "upload": "Wrangler refused the deploy. The service may still be on its previous version.",
    "health": "The deployed service did not answer a health request.",
}


def main(values, environ=None):
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "push_preflight", Path(__file__).with_name("push-infra-preflight.py"))
    preflight = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preflight)
    try:
        account, token = preflight.deployment_target(values)
        fcm = service_account(values.get("APU_PUSH_FCM_SERVICE_ACCOUNT", ""))
        key, generated = rate_key(values)
        # Only these three names are handed to Wrangler; everything else stays
        # out of the child process.
        env = {
            "PATH": (environ or os.environ).get("PATH", "/usr/local/bin:/usr/bin:/bin"),
            "HOME": (environ or os.environ).get("HOME", "/tmp"),
            "CI": "true",
            "CLOUDFLARE_ACCOUNT_ID": account,
            "CLOUDFLARE_API_TOKEN": token,
            "WRANGLER_SEND_METRICS": "false",
        }
        upload_secrets(env, fcm, key)
        print("Uploaded three encrypted secrets; values were not printed.")
        deploy(env)
        print("Deployed " + WORKER_NAME + " with both switches on.")
        if generated:
            print("Generated a random PUSH_RATE_KEY for this deployment (not stored by this program).")
        status, body = health()
        if status == 200 and isinstance(body, dict) and body.get("ok") is True:
            print("HEALTH=OK")
        else:
            print("HEALTH=UNEXPECTED status=" + str(status))
            print("The service did not confirm it is configured; inspect before enabling clients.")
            return 1
        print("This is not delivery: no device registered and no provider message was sent.")
        return 0
    except DeployError as error:
        print("::error title=Push deployment::" + MESSAGES.get(error.code, MESSAGES["wrangler"]))
        return 1
    except preflight.PreflightError as error:
        print("::error title=Push deployment::" + preflight.MESSAGES.get(error.code, preflight.MESSAGES["unavailable"]))
        return 1
    except Exception:
        # Never print exception text: it can contain secrets and provider bodies.
        print("::error title=Push deployment::" + MESSAGES["wrangler"])
        return 1


if __name__ == "__main__":
    sys.exit(main(dict(os.environ)))
