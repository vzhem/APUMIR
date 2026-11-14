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
  1. a first deploy of `wrangler.push.toml` with both switches left at "false".
     `wrangler secret put` addresses an existing Worker, so the Worker has to
     exist before any secret can be attached. Starting from a switched-off
     version also means the account never holds a version that is on but is
     missing a credential;
  2. the secrets, so the version that turns the switches on can never start
     without them (project id included - the service reports itself disabled
     without it);
  3. a second deploy with both switches overridden to "true";
  4. one public health request, which is the only honest signal available here.

Both deploys are the same source and the same storage; only the two plain-text
bindings differ, and the Worker itself reports "off" until every credential is
present.

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
# fcmConfigured() refuses to call the provider unless all three of these bind,
# so the project id is not optional: without it the service would be up and
# silently disabled.
SECRET_NAMES = ("FCM_PROJECT_ID", "FCM_CLIENT_EMAIL", "FCM_PRIVATE_KEY", "PUSH_RATE_KEY")


class DeployError(Exception):
    """Fixed code only; never provider text, tokens or account identifiers."""

    def __init__(self, code, detail=None, stage=None):
        self.code = code
        # A second fixed code from DIAGNOSTICS, or None. Never free text.
        self.detail = detail
        # 1-based index of the Wrangler call that failed, so a run says where it
        # stopped without quoting anything Wrangler printed.
        self.stage = stage
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
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


# Wrangler and Cloudflare echo request identifiers, account names, routes and
# token prefixes on failure, so its output never goes to the log as-is. These are
# the phrases that name the reason; everything around them is dropped.
DIAGNOSTICS = (
    ("Couldn't find a Durable Object namespace", "worker_namespace_missing"),
    # Credential and transport failures are listed before the generic API phrase,
    # because Cloudflare's own wording for them also contains "A request to the
    # Cloudflare API" and the first match is the one that is reported.
    ("Authentication error", "credentials_refused"),
    ("Invalid API Token", "credentials_refused"),
    ("Unauthorized", "credentials_refused"),
    ("10000", "credentials_refused"),
    ("fetch failed", "network"),
    ("ECONNRESET", "network"),
    ("ETIMEDOUT", "network"),
    ("getaddrinfo", "network"),
    ("You need to register a workers.dev subdomain", "subdomain_missing"),
    ("Cannot create a new class", "migration_refused"),
    ("cannot be migrated", "migration_refused"),
    ("Exceeded the limit", "quota"),
    ("A request to the Cloudflare API", "api_refused"),
)


def diagnosis(text):
    """Map Wrangler's own failure wording to one fixed code, or None."""
    if not isinstance(text, str):
        return None
    for phrase, code in DIAGNOSTICS:
        if phrase in text:
            return code
    return None


def upload_secrets(env, fcm, rate_key):
    """PUT each secret through Wrangler's own secret path, one at a time."""
    values = {
        "FCM_PROJECT_ID": fcm["project"],
        "FCM_CLIENT_EMAIL": fcm["email"],
        "FCM_PRIVATE_KEY": fcm["key"],
        "PUSH_RATE_KEY": rate_key,
    }
    for name in SECRET_NAMES:
        code, output = run_wrangler(
            # --name is explicit: a command must never depend on which directory
            # (or which config default) it happens to run from.
            ["secret", "put", name, "--name", WORKER_NAME,
             "--config", str(CONFIG.relative_to(PROJECT))],
            env, stdin_text=values[name] + "\n",
        )
        if code != 0:
            raise DeployError("secret", diagnosis(output), stage=SECRET_NAMES.index(name) + 2)
    return True


def deploy(env, enabled):
    """One deploy; the two switches are plain-text bindings, not secrets."""
    value = "true" if enabled else "false"
    code, output = run_wrangler([
        "deploy", "--config", str(CONFIG.relative_to(PROJECT)),
        "--var", "PUSH_ENABLED:" + value, "--var", "MAILBOX_ENABLED:" + value,
        "--name", WORKER_NAME,
    ], env)
    if code != 0:
        # Stage 1 is the switched-off deploy, stages 2..5 are the secrets, and
        # stage 6 is the deploy that turns everything on.
        raise DeployError("upload", diagnosis(output),
                          stage=6 if enabled else 1)
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
    "worker_namespace_missing": "The Worker tried to reach its storage class before that class existed; the storage binding is missing from the deployed version.",
    "credentials_refused": "Cloudflare refused the deployment token: it cannot write Workers in this account.",
    "api_refused": "Cloudflare refused the request; the deploy token may lack the Workers edit permission.",
    "subdomain_missing": "The account has no workers.dev subdomain, so the reviewed host cannot resolve.",
    "migration_refused": "Cloudflare refused the storage migration; the class already exists in another form.",
    "quota": "A Cloudflare account limit was reached.",
    "network": "The runner could not reach the Cloudflare API; nothing was changed.",
    "secret": "A Worker secret was refused. The service exists with both switches off; inspect before retrying.",
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
        # The Worker must exist before a secret can be attached to it, and it
        # must exist in the off position before it holds any credential.
        try:
            deploy(env, enabled=False)
        except DeployError as error:
            # A first deploy onto an existing Worker can legitimately conflict
            # with what is already there. Say so with an annotation instead of
            # leaving only a bare "exit code 1".
            print("::warning title=Push deployment::The first, switched-off deploy was refused"
                  + ((" [" + error.detail + "] " + MESSAGES.get(error.detail, "")) if error.detail else "")
                  + " (failed at step 1 of 6) Nothing was uploaded and no secret was attached.")
            raise
        print("Created or refreshed " + WORKER_NAME + " with both switches off.")
        upload_secrets(env, fcm, key)
        print("Uploaded " + str(len(SECRET_NAMES)) + " encrypted secrets; values were not printed.")
        deploy(env, enabled=True)
        print("Deployed " + WORKER_NAME + " with both switches on.")
        if generated:
            print("Generated a random PUSH_RATE_KEY for this deployment (not stored by this program).")
        status, body = health()
        healthy = status == 200 and isinstance(body, dict) and body.get("ok") is True
        if not healthy:
            print("HEALTH=UNEXPECTED status=" + str(status))
            # Both the requested switches and the Worker's own idea of "enabled"
            # are what /health reports, so a switch is the first thing to check.
            print("A switch or a credential binding is not in effect; the service reports itself off.")
            return 1
        print("HEALTH=OK")
        print("This is not delivery: no device registered and no provider message was sent.")
        return 0
    except DeployError as error:
        message = MESSAGES.get(error.code, MESSAGES["wrangler"])
        if error.detail:
            message += " [" + error.detail + "] " + MESSAGES.get(error.detail, "")
        if error.stage:
            message += " (failed at step " + str(error.stage) + " of 6)"
        print("::error title=Push deployment::" + message.rstrip())
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
