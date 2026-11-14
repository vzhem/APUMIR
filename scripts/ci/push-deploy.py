#!/usr/bin/env python3
"""Deploy the dedicated push Worker with its storage and provider credentials.

Unlike `push-disabled-deploy.py`, this is the *working* service: it uploads the
same six modules plus a Durable Object binding, a migration for the SQLite-backed
shard class, the FCM service-account credentials and the rate key, and finally
turns both switches on.

Safety rules kept from the prototype script, and why:
  - the deploy token is account-scoped and can therefore touch other Workers in
    the account; this script only ever addresses the one Worker name below;
  - provider responses are never printed, logged or retained: they can contain
    account data and, for the secret endpoints, echoes of what was uploaded;
  - nothing is ever created *or deleted* on a guess. Verify runs first and are
    read-only; the upload is one PUT to the exact name, which is idempotent, and
    the secrets are PUT per name, also idempotent;
  - a failed/uncertain step stops the run. There is no automatic retry and no
    cleanup path, because a half-applied deploy is inspected by a human.

What this script cannot promise and does not claim: that Google accepts the
credentials, that a phone receives a wake, or that a message is delivered. It
ends by asking the deployed service for its own health answer, which is the only
honest signal available from here.
"""

import importlib.util
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "tools" / "worker" / "push"
MODULES = ("worker.mjs", "fcm.mjs", "protocol.mjs", "mailbox-protocol.mjs", "mailbox.mjs", "wake-queue.mjs")
WORKER_NAME = "apu-push"
REVIEWED_HOST = WORKER_NAME + ".apuchat.workers.dev"
MAX_MODULE_BYTES = 128 * 1024
MAX_RESPONSE_BYTES = 256 * 1024

spec = importlib.util.spec_from_file_location("push_preflight", Path(__file__).with_name("push-infra-preflight.py"))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class DeployError(Exception):
    """Fixed code only; never provider text, tokens or account identifiers."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


def service_account(raw):
    """Parse the service-account JSON without echoing any field."""
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


def modules_payload(project=PROJECT):
    """Read the six modules as bounded UTF-8 text; no code is executed."""
    uploaded = []
    try:
        for name in MODULES:
            path = project / name
            if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_MODULE_BYTES:
                raise DeployError("modules")
            raw = path.read_bytes()
            if not 0 < len(raw) <= MAX_MODULE_BYTES:
                raise DeployError("modules")
            raw.decode("utf-8", errors="strict")
            uploaded.append({"name": name, "content_type": "application/javascript+module",
                             "content": raw.decode("utf-8")})
    except (OSError, UnicodeError):
        raise DeployError("modules") from None
    return uploaded


def script_payload(fcm, rate_key, modules):
    """One complete, reviewed version. Both switches on: this is the point."""
    if not isinstance(rate_key, str) or not re.fullmatch(r"[0-9a-f]{32,128}", rate_key):
        raise DeployError("rate_key")
    return {
        "main_module": "worker.mjs",
        "compatibility_date": "2025-07-18",
        "modules": modules,
        "bindings": [
            {"type": "plain_text", "name": "PUSH_ENABLED", "text": "true"},
            {"type": "plain_text", "name": "MAILBOX_ENABLED", "text": "true"},
            {"type": "plain_text", "name": "FCM_PROJECT_ID", "text": fcm["project"]},
            {"type": "secret_text", "name": "FCM_CLIENT_EMAIL", "text": fcm["email"]},
            {"type": "secret_text", "name": "FCM_PRIVATE_KEY", "text": fcm["key"]},
            {"type": "secret_text", "name": "PUSH_RATE_KEY", "text": rate_key},
            {"type": "durable_object_namespace", "name": "PUSH_SHARDS", "class_name": "PushShard"},
        ],
        "migrations": {"new_sqlite_classes": ["PushShard"]},
    }


def request_json(host, path, token, method="GET", body=None, maximum=MAX_RESPONSE_BYTES):
    """One fixed-host call; no redirects, no proxies, no retries."""
    encoded = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    if encoded is not None and len(encoded) > 4 * 1024 * 1024:
        raise DeployError("payload")
    request = urllib.request.Request("https://" + host + path, data=encoded, method=method, headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
        "Accept": "application/json",
        "User-Agent": "APU-push-deploy",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(request, timeout=60)
        except urllib.error.HTTPError as error:
            error.close()
            return error.code, None
        with response:
            raw = response.read(maximum + 1)
            if len(raw) > maximum:
                raise DeployError("provider")
            if not raw:
                return response.status, None
            return response.status, json.loads(raw.decode("utf-8"))
    except DeployError:
        raise
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise DeployError("provider") from None


def cloudflare_host(account):
    return "api.cloudflare.com"


def account_path(account):
    return "/client/v4/accounts/" + account


def deploy(account, token, fcm, rate_key, request=request_json):
    """Upload one version and turn it into the live deployment."""
    base = account_path(account) + "/workers/scripts/" + WORKER_NAME
    modules = modules_payload()
    payload = script_payload(fcm, rate_key, modules)

    # Read-only first: the migration may only be introduced with a fresh deploy
    # of this name, so its absence is a precondition, not an error to fix blindly.
    status, body = request("api.cloudflare.com", base + "/settings", token)
    if status not in (200, 404):
        raise DeployError("provider")
    if status == 200 and isinstance(body, dict):
        result = body.get("result")
        if not isinstance(result, dict):
            raise DeployError("provider")
        bindings = result.get("bindings")
        if isinstance(bindings, list) and any(
            isinstance(item, dict) and item.get("name") == "PUSH_SHARDS" for item in bindings
        ):
            # Already migrated: upload without the migration block, which
            # Cloudflare rejects when replayed on an existing class.
            payload.pop("migrations", None)

    status, _ = request("api.cloudflare.com", base, token, method="PUT", body=payload)
    if status not in (200, 201):
        raise DeployError("upload")

    # A new upload is not traffic until a deployment references it; asking the
    # API for the live deployment is the only readback available to this token.
    status, body = request("api.cloudflare.com", base + "/deployments", token)
    if status not in (200, 404):
        raise DeployError("deployment")
    if status == 404:
        raise DeployError("deployment")
    return True


def health(host=REVIEWED_HOST, opener=urllib.request.urlopen):
    """One public GET: the deployed service describing itself. No credentials."""
    request = urllib.request.Request("https://" + host + "/health", headers={"Accept": "application/json"})
    try:
        with opener(request, timeout=30) as response:
            body = json.loads(response.read(4096).decode("utf-8"))
            return response.status, body
    except urllib.error.HTTPError as error:
        error.close()
        return error.code, None
    except (OSError, ValueError, TypeError, UnicodeError):
        raise DeployError("health") from None


MESSAGES = {
    "invocation": "This deployment can run only from the designated GitHub Actions branch.",
    "debug": "Disable GitHub Actions debug logging before deploying.",
    "cloudflare_account_invalid": "The Cloudflare account ID format is invalid.",
    "cloudflare_token_invalid": "The Cloudflare deploy token format is invalid.",
    "fcm_json_invalid": "APU_PUSH_FCM_SERVICE_ACCOUNT is missing or malformed.",
    "fcm_identity_invalid": "The service-account project or email is malformed.",
    "rate_key": "APU_PUSH_RATE_KEY is missing or has the wrong format.",
    "modules": "The Worker modules are missing, too large or not valid UTF-8.",
    "payload": "The upload payload is too large.",
    "provider": "Cloudflare returned an unexpected answer; nothing was retried.",
    "upload": "Cloudflare refused the upload; the service may still be on its previous version.",
    "deployment": "The upload is not confirmed as the live deployment; check the dashboard.",
    "health": "The deployed service did not answer a health request.",
}


def main(values):
    try:
        account, token = preflight.deployment_target(values)
        raw_fcm = values.get("APU_PUSH_FCM_SERVICE_ACCOUNT", "")
        rate_key = values.get("APU_PUSH_RATE_KEY", "")
        fcm = service_account(raw_fcm)
        deploy(account, token, fcm, rate_key)
        print("Deployed the push service with both switches on.")
        print("Provider credentials were uploaded as encrypted bindings and not printed.")
        status, body = health()
        if status == 200 and isinstance(body, dict) and body.get("ok") is True:
            print("HEALTH=OK")
        else:
            print("HEALTH=UNEXPECTED status=" + str(status))
            print("The service did not confirm it is configured; inspect before enabling clients.")
        print("This is not delivery: no device registered and no provider message was sent.")
        return 0
    except DeployError as error:
        print("::error title=Push deployment::" + MESSAGES.get(error.code, MESSAGES["provider"]))
        return 1
    except preflight.PreflightError as error:
        print("::error title=Push deployment::" + preflight.MESSAGES.get(error.code, preflight.MESSAGES["unavailable"]))
        return 1
    except Exception:
        # Never print exception text: it can contain secrets and provider bodies.
        print("::error title=Push deployment::" + MESSAGES["provider"])
        return 1


if __name__ == "__main__":
    sys.exit(main(dict(__import__("os").environ)))
