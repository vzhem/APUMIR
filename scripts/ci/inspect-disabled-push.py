#!/usr/bin/env python3
"""Inspect one already-created disabled Worker; fixed GET requests only.

This is a separate, owner-reviewed diagnostic, not a deployment or a retry.
It uses only the separately stored read credential, never the deploy or FCM
secrets. The selected Worker and the source hashes are fixed to the creation
commit. No provider response, credential, account ID, subdomain, or exception
text is printed. API readbacks are snapshots, not a lock or delivery proof.
"""

import base64
import hashlib
import http.client
import importlib.util
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ACCOUNT = "APU_PUSH_CLOUDFLARE_ACCOUNT_ID"
READ_TOKEN = "APU_PUSH_CLOUDFLARE_TOKEN"
CREATION_SHA = "b266925f78ee9afbf19ae42b3f251d84f841c7dd"
WORKER_NAME = "apu-push-" + CREATION_SHA[:20]
WORKER_ID = re.compile(r"[0-9a-f]{32}\Z")
VERSION_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
SUBDOMAIN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
MAX_API_BYTES = 1280 * 1024
MAX_MODULE_BYTES = 128 * 1024
MAX_HEALTH_BYTES = 1024

# SHA-256 of the six source modules of the deployed version. These are not
# secrets; they are the bytes `wrangler.push.toml` uploads today, so this
# inspection can be re-run against the live service (§5 of PUSH_INFRA_SETUP.md).
#
# They no longer describe the prototype created at CREATION_SHA: the working
# service is a different Worker with storage and both switches on, and it is
# inspected by its own health answer, not by this readback. `worker.mjs` changed
# when a refused request stopped arming the shard alarm; everything else is byte
# for byte the creation code.
# Readback compares decoded bytes, not provider-supplied hashes.
MODULE_HASHES = {
    "worker.mjs": "a3583b6eba5d013bb3a05eb5b8d8e63d188147f00e1b1cff521887d55f957a9d",
    "fcm.mjs": "3fbe9b7ec2379ec6885adfb45a77f13b3f40fd5a052749aa2d7acef6252fd9aa",
    "protocol.mjs": "404ab784fdc9e86d1df74bd2e182dc5a79ba31ea132a5089bd6c8b4f8592f0e1",
    "mailbox-protocol.mjs": "58f08d52403c76a23d9ea330718d32d25ad2870bfab9946371952489e4162ace",
    "mailbox.mjs": "1f0f98eafb1cac4dd4ed4b517c88f4c25f23aa7e6c07bbcd7989731f572462a3",
    "wake-queue.mjs": "0c1380fe11ff0036b99578279dd1ba9c25e7d14a2c46f226df06c4eba0c1ba87",
}

spec = importlib.util.spec_from_file_location("push_inspection_preflight", HERE / "push-infra-preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class InspectionError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def validate_inputs(values):
    try:
        preflight.invocation(values)
    except preflight.PreflightError as error:
        raise InspectionError(error.code) from None
    sha, account, token = values.get("GITHUB_SHA", ""), values.get(ACCOUNT, ""), values.get(READ_TOKEN, "")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise InspectionError("invocation")
    if not isinstance(account, str) or not re.fullmatch(r"[0-9a-f]{32}", account):
        raise InspectionError("account")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token):
        raise InspectionError("token")
    return sha, account, token


def check_checkout(sha):
    # Git gets a small clean environment, no credentials, prompts, or hooks.
    env = {"PATH": os.defpath, "HOME": "/", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}
    try:
        head = subprocess.run(["git", "-c", "core.fsmonitor=false", "rev-parse", "HEAD"], cwd=ROOT, env=env,
                              check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode("ascii").strip()
        changed = subprocess.run(["git", "-c", "core.fsmonitor=false", "status", "--porcelain", "--untracked-files=no"],
                                 cwd=ROOT, env=env, check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    except (OSError, UnicodeError, subprocess.SubprocessError):
        raise InspectionError("checkout") from None
    if head != sha or changed:
        raise InspectionError("checkout")


def allowed_api_path(account, path):
    if (not isinstance(account, str) or not re.fullmatch(r"[0-9a-f]{32}", account) or
            not isinstance(path, str)):
        return False
    base = f"/client/v4/accounts/{account}/workers"
    if path in {f"{base}/subdomain", f"{base}/workers/{WORKER_NAME}",
                f"{base}/scripts/{WORKER_NAME}/deployments?page=1&per_page=1"}:
        return True
    return re.fullmatch(
        re.escape(base) + r"/workers/[0-9a-f]{32}/versions/"
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\?include=modules", path,
    ) is not None


def api_status_category(status):
    if status in (401, 403, 404, 429):
        return f"api_http_{status}"
    if isinstance(status, int) and 300 <= status < 400:
        return "api_redirect"
    if isinstance(status, int) and 400 <= status < 500:
        return "api_other_4xx"
    if isinstance(status, int) and 500 <= status < 600:
        return "api_5xx"
    return "api_response"


def api_get(account, token, path):
    """One bounded GET to the fixed API host; no redirect, proxy, or error body."""
    if (not allowed_api_path(account, path) or not isinstance(token, str) or
            not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token)):
        raise InspectionError("internal")
    req = urllib.request.Request("https://api.cloudflare.com" + path, method="GET", headers={
        "Authorization": "Bearer " + token, "Accept": "application/json", "User-Agent": "APU-disabled-push-inspection/1.0",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()  # Discard provider messages; never read or log them.
            raise InspectionError(api_status_category(status)) from None
        with response:
            if response.status != 200:
                raise InspectionError(api_status_category(response.status))
            raw = response.read(MAX_API_BYTES + 1)
            if len(raw) > MAX_API_BYTES:
                raise InspectionError("api_response")
            body = preflight.parsed_json(raw.decode("utf-8"), MAX_API_BYTES)
        if not isinstance(body, dict) or body.get("success") is not True or not isinstance(body.get("result"), dict):
            raise InspectionError("api_response")
        return body["result"]
    except InspectionError:
        raise
    except (preflight.PreflightError, OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise InspectionError("api_unavailable") from None


def worker_id(result):
    subdomain = result.get("subdomain") if isinstance(result, dict) else None
    identifier = result.get("id") if isinstance(result, dict) else None
    if (not isinstance(result, dict) or result.get("name") != WORKER_NAME or
            not isinstance(identifier, str) or not WORKER_ID.fullmatch(identifier) or
            not isinstance(subdomain, dict) or subdomain.get("enabled") is not True or
            subdomain.get("previews_enabled") is not False):
        raise InspectionError("worker_state")
    return identifier


def active_version(result):
    deployments = result.get("deployments") if isinstance(result, dict) else None
    if not isinstance(deployments, list) or not deployments or not isinstance(deployments[0], dict):
        raise InspectionError("deployment_state")
    deployment = deployments[0]
    versions = deployment.get("versions")
    if (deployment.get("strategy") != "percentage" or not isinstance(versions, list) or len(versions) != 1 or
            not isinstance(versions[0], dict) or versions[0].get("percentage") != 100):
        raise InspectionError("deployment_state")
    version = versions[0].get("version_id")
    if not isinstance(version, str) or not VERSION_ID.fullmatch(version):
        raise InspectionError("deployment_state")
    return version


def verify_version(result, expected_version):
    bindings = result.get("bindings") if isinstance(result, dict) else None
    if (not isinstance(result, dict) or result.get("id") != expected_version or
            result.get("compatibility_date") not in ("2025-07-18", "2025-07-18T00:00:00Z") or
            result.get("compatibility_flags") not in (None, []) or result.get("main_module") != "worker.mjs" or
            not isinstance(bindings, list) or len(bindings) != 2 or any(not isinstance(b, dict) for b in bindings)):
        raise InspectionError("version_state")
    by_name = {b.get("name"): b for b in bindings if isinstance(b.get("name"), str)}
    if set(by_name) != {"PUSH_ENABLED", "MAILBOX_ENABLED"}:
        raise InspectionError("version_state")
    for name in by_name:
        if by_name[name].get("type") != "plain_text" or by_name[name].get("text") != "false":
            raise InspectionError("version_state")
    exports = result.get("exports")
    if (result.get("migration_tag") not in (None, "") or result.get("migrations") not in (None, {}) or
            result.get("containers") not in (None, []) or
            (exports is not None and (not isinstance(exports, dict) or
             any(not isinstance(entry, dict) or entry.get("type") != "worker" for entry in exports.values())))):
        raise InspectionError("version_state")
    modules = result.get("modules")
    if not isinstance(modules, list) or len(modules) != len(MODULE_HASHES) or any(not isinstance(m, dict) for m in modules):
        raise InspectionError("version_state")
    by_name = {m.get("name"): m for m in modules if isinstance(m.get("name"), str)}
    if set(by_name) != set(MODULE_HASHES):
        raise InspectionError("version_state")
    for name, expected_hash in MODULE_HASHES.items():
        entry = by_name[name]
        if entry.get("content_type") != "application/javascript+module" or not isinstance(entry.get("content_base64"), str):
            raise InspectionError("version_state")
        try:
            raw = base64.b64decode(entry["content_base64"], validate=True)
        except (ValueError, TypeError):
            raise InspectionError("version_state") from None
        if not 0 < len(raw) <= MAX_MODULE_BYTES or hashlib.sha256(raw).hexdigest() != expected_hash:
            raise InspectionError("version_state")


def read_snapshot(account, token, request=api_get):
    base = f"/client/v4/accounts/{account}/workers"
    worker_path = f"{base}/workers/{WORKER_NAME}"
    deployment_path = f"{base}/scripts/{WORKER_NAME}/deployments?page=1&per_page=1"
    identifier = worker_id(request(account, token, worker_path))
    version = active_version(request(account, token, deployment_path))
    version_path = f"{base}/workers/{identifier}/versions/{version}?include=modules"
    verify_version(request(account, token, version_path), version)
    # Catch ordinary concurrent renames/changes around the version readback.
    if (worker_id(request(account, token, worker_path)) != identifier or
            active_version(request(account, token, deployment_path)) != version):
        raise InspectionError("changed")
    return identifier, version


def account_subdomain(account, token, request=api_get):
    result = request(account, token, f"/client/v4/accounts/{account}/workers/subdomain")
    subdomain = result.get("subdomain") if isinstance(result, dict) else None
    if not isinstance(subdomain, str) or not SUBDOMAIN.fullmatch(subdomain):
        raise InspectionError("subdomain")
    return subdomain


HEALTH_HTTP_STATUSES = frozenset({400, 401, 403, 404, 408, 429, 500, 502, 504, 522, 523, 524, 525, 526, 530})
HEALTH_CATEGORIES = frozenset({"OK_DISABLED_503", "HTTP_503_UNEXPECTED_BODY", "HTTP_REDIRECT_BLOCKED",
                               "HTTP_OTHER_2XX", "HTTP_OTHER_4XX", "HTTP_OTHER_5XX", "HTTP_UNEXPECTED",
                               "DNS_UNAVAILABLE", "TLS_UNAVAILABLE", "TIMEOUT", "NETWORK_UNAVAILABLE"} |
                              {f"HTTP_{status}" for status in HEALTH_HTTP_STATUSES})


def health_http_category(status):
    if status == 503:
        return "HTTP_503_UNEXPECTED_BODY"
    if status in HEALTH_HTTP_STATUSES:
        return f"HTTP_{status}"
    if isinstance(status, int) and 200 <= status < 300:
        return "HTTP_OTHER_2XX"
    if isinstance(status, int) and 300 <= status < 400:
        return "HTTP_REDIRECT_BLOCKED"
    if isinstance(status, int) and 400 <= status < 500:
        return "HTTP_OTHER_4XX"
    if isinstance(status, int) and 500 <= status < 600:
        return "HTTP_OTHER_5XX"
    return "HTTP_UNEXPECTED"


def network_category(reason):
    if isinstance(reason, (TimeoutError, socket.timeout)):
        return "TIMEOUT"
    if isinstance(reason, socket.gaierror):
        return "DNS_UNAVAILABLE"
    if isinstance(reason, ssl.SSLError):
        return "TLS_UNAVAILABLE"
    return "NETWORK_UNAVAILABLE"


def public_health(subdomain):
    """One unauthenticated GET to the fixed new Worker; return only categories."""
    if not isinstance(subdomain, str) or not SUBDOMAIN.fullmatch(subdomain):
        raise InspectionError("subdomain")
    host = f"{WORKER_NAME}.{subdomain}.workers.dev"
    req = urllib.request.Request("https://" + host + "/health", method="GET", headers={
        "Cache-Control": "no-cache", "Accept": "application/json",
        "User-Agent": "APU-Push-Disabled-Health/1.0 (+https://github.com/vzhem/APUMIR)",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error  # A valid disabled result is itself HTTP 503.
        with response:
            status = response.code
            if status != 503:
                return health_http_category(status)
            raw = response.read(MAX_HEALTH_BYTES + 1)
        if len(raw) > MAX_HEALTH_BYTES:
            return "HTTP_503_UNEXPECTED_BODY"
        try:
            body = preflight.parsed_json(raw.decode("utf-8"), MAX_HEALTH_BYTES)
        except (preflight.PreflightError, UnicodeError):
            return "HTTP_503_UNEXPECTED_BODY"
        if (isinstance(body, dict) and set(body) == {"ok", "service", "experimental"} and
                body["ok"] is False and body["service"] == "apu-push-wake" and body["experimental"] is True):
            return "OK_DISABLED_503"
        return "HTTP_503_UNEXPECTED_BODY"
    except urllib.error.URLError as error:
        return network_category(error.reason)
    except (http.client.HTTPException, OSError, ValueError, TypeError, RecursionError) as error:
        return network_category(error)


MESSAGES = {
    "invocation": "Wrong repository, branch, commit or Actions invocation; no Cloudflare request was made.",
    "debug": "Disable Actions debug logging; no Cloudflare request was made.",
    "account": "The account ID secret is missing or malformed; no Cloudflare request was made.",
    "token": "The read token secret is missing or malformed; no Cloudflare request was made.",
    "checkout": "The checkout is not the reviewed clean commit; no Cloudflare request was made.",
    "api_http_401": "A Cloudflare read request returned HTTP 401; the current state was not verified.",
    "api_http_403": "A Cloudflare read request returned HTTP 403; the current state was not verified.",
    "api_http_404": "A Cloudflare read request returned HTTP 404; the current state was not verified.",
    "api_http_429": "A Cloudflare read request returned HTTP 429; the current state was not verified.",
    "api_redirect": "A Cloudflare API redirect was blocked; the current state was not verified.",
    "api_other_4xx": "A Cloudflare read request returned another 4xx; the current state was not verified.",
    "api_5xx": "A Cloudflare read request returned 5xx; the current state was not verified.",
    "api_unavailable": "Cloudflare API was unavailable or returned invalid data; the current state was not verified.",
    "api_response": "Cloudflare returned an unexpected API response; the current state was not verified.",
    "worker_state": "Worker identity or workers.dev settings differ from the expected snapshot.",
    "deployment_state": "The active deployment does not match the expected single-version shape.",
    "version_state": "The active version's code or disabled settings do not match the creation commit.",
    "changed": "Worker or deployment changed between reads; the snapshots are inconclusive.",
    "subdomain": "The existing account subdomain was not verified; no health request was made.",
}


def main():
    # Do not give Git or unrelated subprocesses the environment secrets.
    secret_values = {name: os.environ.pop(name, "") for name in (ACCOUNT, READ_TOKEN)}
    values = {}
    try:
        names = ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_REF", "GITHUB_SHA", "RUNNER_DEBUG",
                 "APU_GITHUB_STEP_DEBUG", "APU_GITHUB_RUNNER_DEBUG")
        values = {name: os.environ.get(name, "") for name in names}
        values.update(secret_values)
        sha, account, token = validate_inputs(values)
        secret_values.clear()
        values.clear()
        check_checkout(sha)
        before = read_snapshot(account, token)
        subdomain = account_subdomain(account, token)
        health = public_health(subdomain)
        if health not in HEALTH_CATEGORIES:
            raise InspectionError("internal")
        print(f"::notice title=Disabled push inspection::HEALTH={health}")
        after = read_snapshot(account, token)
        if before != after:
            raise InspectionError("changed")
        print("::notice title=Disabled push inspection::API readbacks match the creation code and disabled flags before and after health; these are snapshots, not a lock.")
        if health != "OK_DISABLED_503":
            print("::error title=Disabled push inspection::The public health response was not verified; its category alone does not establish the cause.")
            return 1
        print("::notice title=Disabled push inspection::One public health GET matched disabled 503. FCM and delivery remain unverified.")
        return 0
    except InspectionError as error:
        print("::error title=Disabled push inspection::" + MESSAGES.get(error.code, "Inspection stopped; no result was verified."))
        return 1
    except Exception:
        # Provider, network, and process exceptions can contain confidential data.
        print("::error title=Disabled push inspection::Inspection stopped; no result was verified.")
        return 1
    finally:
        secret_values.clear()
        values.clear()


if __name__ == "__main__":
    sys.exit(main())
