#!/usr/bin/env python3
"""Proposed one-time creation of an isolated, *disabled* push Worker.

This is not activation and is not evidence of push delivery. No FCM secrets,
rate key, DNS routes, existing Worker names or external bindings are supplied.
The first POST uses Cloudflare's create-Worker API, which documents failure on
an existing name; the second POST uses the ID returned by that creation, not a
name looked up in a separate request. This relies on the provider's semantics
and has not been validated against the real account. An account-scoped write
credential is still capable of more than this program does.

A lost/failed response after either POST leaves the result uncertain. Never
retry blindly, upload to a found Worker, or automatically delete anything.
Provider output is suppressed because it may contain sensitive information.
"""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "tools/worker/push"
CONFIG = PROJECT / "wrangler.toml"
MODULES = ("worker.mjs", "fcm.mjs", "protocol.mjs", "mailbox-protocol.mjs", "mailbox.mjs", "wake-queue.mjs")
MAX_MODULE_BYTES = 128 * 1024
MAX_REQUEST_BYTES = 1024 * 1024
MAX_RESPONSE_BYTES = 256 * 1024
# A version readback includes base64 source plus Cloudflare's metadata.
MAX_VERSION_READBACK_BYTES = MAX_REQUEST_BYTES + MAX_RESPONSE_BYTES
WORKER_NAME = re.compile(r"apu-push-[0-9a-f]{20}\Z")
WORKER_ID = re.compile(r"[0-9a-f]{32}\Z")
VERSION_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")

spec = importlib.util.spec_from_file_location("push_preflight", Path(__file__).with_name("push-infra-preflight.py"))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class DeployError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def validate_config(path=CONFIG):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 32_768:
        raise DeployError("config")
    try:
        with path.open("rb") as source:
            config = tomllib.load(source)
    except (OSError, tomllib.TOMLDecodeError):
        raise DeployError("config") from None
    expected_keys = {"name", "main", "compatibility_date", "workers_dev", "vars", "durable_objects", "migrations"}
    if set(config) != expected_keys or config.get("name") != "apu-push-wake-experimental" or config.get("main") != "worker.mjs":
        raise DeployError("config")
    if config.get("compatibility_date") != "2025-07-18" or config.get("workers_dev") is not True:
        raise DeployError("config")
    if config.get("vars") != {"PUSH_ENABLED": "false", "MAILBOX_ENABLED": "false"}:
        raise DeployError("config")
    if config.get("durable_objects") != {"bindings": [{"name": "PUSH_SHARDS", "class_name": "PushShard"}]}:
        raise DeployError("config")
    if config.get("migrations") != [{"tag": "v1", "new_sqlite_classes": ["PushShard"]}]:
        raise DeployError("config")


def deployment_inputs(values):
    try:
        preflight.invocation(values)
    except preflight.PreflightError as error:
        raise DeployError(error.code) from None
    sha = values.get("GITHUB_SHA", "")
    account = values.get("APU_PUSH_CLOUDFLARE_ACCOUNT_ID", "")
    token = values.get("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise DeployError("invocation")
    if not re.fullmatch(r"[0-9a-f]{32}", account):
        raise DeployError("account")
    if not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token):
        raise DeployError("token")
    # Stable for a reviewed commit. A rerun must fail at create, not update.
    return account, token, "apu-push-" + sha[:20]


def check_branch_head(values, request=preflight.request_json):
    """Read-only check against a stale approved commit; not a remote lock."""
    try:
        preflight.invocation(values)
    except preflight.PreflightError as error:
        raise DeployError(error.code) from None
    sha = values.get("GITHUB_SHA", "")
    token = values.get("GH_TOKEN", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not token:
        raise DeployError("head")
    try:
        status, body = request("api.github.com", f"/repos/{preflight.REPO}/git/ref/heads/{preflight.BRANCH}", token)
    except preflight.PreflightError:
        raise DeployError("head") from None
    obj = body.get("object") if isinstance(body, dict) else None
    if (status != 200 or not isinstance(body, dict) or body.get("ref") != "refs/heads/" + preflight.BRANCH or
            not isinstance(obj, dict) or obj.get("type") != "commit" or obj.get("sha") != sha):
        raise DeployError("head")


def check_checkout(sha):
    # Git must not inherit deployment or unrelated credentials; do not execute
    # a configured fsmonitor hook from an Actions checkout with secrets present.
    env = {"PATH": os.defpath, "HOME": "/", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}
    try:
        head = subprocess.run(["git", "-c", "core.fsmonitor=false", "rev-parse", "HEAD"], cwd=ROOT, env=env,
                              check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode("ascii").strip()
        changed = subprocess.run(["git", "-c", "core.fsmonitor=false", "status", "--porcelain", "--untracked-files=no"],
                                 cwd=ROOT, env=env, check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    except (OSError, UnicodeError, subprocess.SubprocessError):
        raise DeployError("checkout") from None
    if head != sha or changed:
        raise DeployError("checkout")


def version_payload(project=PROJECT):
    """Build a bounded, fixed version upload; no code is executed with credentials."""
    modules = []
    try:
        for name in MODULES:
            path = project / name
            if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_MODULE_BYTES:
                raise DeployError("modules")
            with path.open("rb") as source:
                raw = source.read(MAX_MODULE_BYTES + 1)
            if not 0 < len(raw) <= MAX_MODULE_BYTES:
                raise DeployError("modules")
            raw.decode("utf-8", errors="strict")
            modules.append({"name": name, "content_type": "application/javascript+module",
                            "content_base64": base64.b64encode(raw).decode("ascii")})
    except (OSError, UnicodeError):
        raise DeployError("modules") from None
    payload = {
        "main_module": "worker.mjs", "compatibility_date": "2025-07-18", "modules": modules,
        "bindings": [
            {"type": "plain_text", "name": "PUSH_ENABLED", "text": "false"},
            {"type": "plain_text", "name": "MAILBOX_ENABLED", "text": "false"},
            {"type": "durable_object_namespace", "name": "PUSH_SHARDS", "class_name": "PushShard"},
        ],
        "migrations": {"new_tag": "v1", "new_sqlite_classes": ["PushShard"]},
    }
    if len(json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("ascii")) > MAX_REQUEST_BYTES:
        raise DeployError("modules")
    return payload


def post_json(path, token, body):
    """One POST to the fixed API host; no proxies, redirects, retries, or provider logs."""
    if not re.fullmatch(r"/client/v4/accounts/[0-9a-f]{32}/workers/workers(?:/[0-9a-f]{32}/versions\?deploy=true)?", path):
        raise DeployError("provider")
    encoded = json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    if len(encoded) > MAX_REQUEST_BYTES:
        raise DeployError("modules")
    req = urllib.request.Request("https://api.cloudflare.com" + path, data=encoded, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json", "Accept": "application/json",
        "User-Agent": "APU-disabled-push-create",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(req, timeout=45)
        except urllib.error.HTTPError as error:
            # Do not log or retain response bodies, even on conflict.
            error.close()
            return error.code, None
        with response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise DeployError("provider")
            return response.status, preflight.parsed_json(raw.decode("utf-8"), MAX_RESPONSE_BYTES)
    except DeployError:
        raise
    except (preflight.PreflightError, OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise DeployError("provider") from None


def workers_subdomain(account, token, request=preflight.request_json):
    try:
        status, body = request("api.cloudflare.com", f"/client/v4/accounts/{account}/workers/subdomain", token)
    except preflight.PreflightError:
        raise DeployError("subdomain") from None
    result = body.get("result") if isinstance(body, dict) else None
    subdomain = result.get("subdomain") if isinstance(result, dict) else None
    if (status != 200 or not isinstance(body, dict) or body.get("success") is not True or not isinstance(subdomain, str)
            or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", subdomain)):
        raise DeployError("subdomain")
    return subdomain


def create_worker(account, token, name, post=post_json):
    if not WORKER_NAME.fullmatch(name):
        raise DeployError("invocation")
    # Cloudflare documents this POST as creating a new Worker and failing if its
    # name already exists. We never fall back to an update or search-and-PUT.
    path = f"/client/v4/accounts/{account}/workers/workers"
    body = {"name": name, "logpush": False, "subdomain": {"enabled": True, "previews_enabled": False}}
    try:
        status, result = post(path, token, body)
    except DeployError:
        raise DeployError("create_uncertain") from None
    item = result.get("result") if isinstance(result, dict) else None
    subdomain = item.get("subdomain") if isinstance(item, dict) else None
    worker_id = item.get("id") if isinstance(item, dict) else None
    if (status not in (200, 201) or not isinstance(result, dict) or result.get("success") is not True or
            not isinstance(item, dict) or item.get("name") != name or not isinstance(worker_id, str) or
            not WORKER_ID.fullmatch(worker_id) or not isinstance(subdomain, dict) or
            subdomain.get("enabled") is not True or subdomain.get("previews_enabled") is not False):
        raise DeployError("create_uncertain")
    return worker_id


def create_disabled_version(account, token, worker_id, payload, post=post_json):
    if not isinstance(worker_id, str) or not WORKER_ID.fullmatch(worker_id):
        raise DeployError("version_uncertain")
    try:
        # Do not send a mutated payload or one no longer matching the checked
        # modules. The new Worker already may exist if this check fails.
        if payload != version_payload():
            raise DeployError("version_uncertain")
    except DeployError:
        raise DeployError("version_uncertain") from None
    # The immutable ID came only from successful creation above; never use a
    # name or a discovered existing ID for the version/deploy operation.
    path = f"/client/v4/accounts/{account}/workers/workers/{worker_id}/versions?deploy=true"
    try:
        status, result = post(path, token, payload)
    except DeployError:
        raise DeployError("version_uncertain") from None
    item = result.get("result") if isinstance(result, dict) else None
    version = item.get("id") if isinstance(item, dict) else None
    if (status not in (200, 201) or not isinstance(result, dict) or result.get("success") is not True or
            not isinstance(version, str) or not VERSION_ID.fullmatch(version)):
        raise DeployError("version_uncertain")
    return version


def version_readback_json(path, token):
    """Bounded GET of one version, including source; never log the response."""
    if not re.fullmatch(
        r"/client/v4/accounts/[0-9a-f]{32}/workers/workers/[0-9a-f]{32}/versions/"
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\?include=modules", path,
    ):
        raise DeployError("version_readback")
    req = urllib.request.Request("https://api.cloudflare.com" + path, method="GET", headers={
        "Authorization": "Bearer " + token, "Accept": "application/json", "User-Agent": "APU-disabled-push-create",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            error.close()  # provider bodies may contain confidential diagnostics
            return error.code, None
        with response:
            raw = response.read(MAX_VERSION_READBACK_BYTES + 1)
            if len(raw) > MAX_VERSION_READBACK_BYTES:
                raise DeployError("version_readback")
            return response.status, preflight.parsed_json(raw.decode("utf-8"), MAX_VERSION_READBACK_BYTES)
    except DeployError:
        raise
    except (preflight.PreflightError, OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise DeployError("version_readback") from None


def verify_new_version(account, token, worker_id, version_id, payload, request=version_readback_json):
    """Compare the created version's code and metadata, not its traffic state."""
    if (not isinstance(worker_id, str) or not WORKER_ID.fullmatch(worker_id) or
            not isinstance(version_id, str) or not VERSION_ID.fullmatch(version_id)):
        raise DeployError("version_readback")
    path = f"/client/v4/accounts/{account}/workers/workers/{worker_id}/versions/{version_id}?include=modules"
    try:
        status, body = request(path, token)
    except DeployError:
        raise DeployError("version_readback") from None
    item = body.get("result") if isinstance(body, dict) else None
    bindings = item.get("bindings") if isinstance(item, dict) else None
    if (status != 200 or not isinstance(body, dict) or body.get("success") is not True or
            not isinstance(item, dict) or item.get("id") != version_id or
            item.get("compatibility_date") not in ("2025-07-18", "2025-07-18T00:00:00Z") or
            item.get("compatibility_flags") not in (None, []) or item.get("main_module") != "worker.mjs" or
            not isinstance(bindings, list) or len(bindings) != 3 or
            any(not isinstance(binding, dict) for binding in bindings)):
        raise DeployError("version_readback")
    by_name = {binding.get("name"): binding for binding in bindings if isinstance(binding.get("name"), str)}
    if set(by_name) != {"PUSH_ENABLED", "MAILBOX_ENABLED", "PUSH_SHARDS"}:
        raise DeployError("version_readback")
    for name in ("PUSH_ENABLED", "MAILBOX_ENABLED"):
        if by_name[name].get("type") != "plain_text" or by_name[name].get("text") != "false":
            raise DeployError("version_readback")
    shard = by_name["PUSH_SHARDS"]
    if (shard.get("type") != "durable_object_namespace" or shard.get("class_name") != "PushShard" or
            shard.get("script_name") not in (None, "") or shard.get("environment") not in (None, "") or
            shard.get("dispatch_namespace") not in (None, "")):
        raise DeployError("version_readback")
    modules = item.get("modules")
    if not isinstance(modules, list) or len(modules) != len(MODULES) or any(not isinstance(m, dict) for m in modules):
        raise DeployError("version_readback")
    returned = {m.get("name"): m for m in modules if isinstance(m.get("name"), str)}
    expected = {m["name"]: m for m in payload["modules"]}
    if set(returned) != set(MODULES) or set(expected) != set(MODULES):
        raise DeployError("version_readback")
    # Cloudflare's documented response uses base64 too; compare the content and
    # type to our checked-in upload, without printing source or provider output.
    for name in MODULES:
        if (returned[name].get("content_type") != expected[name]["content_type"] or
                returned[name].get("content_base64") != expected[name]["content_base64"]):
            raise DeployError("version_readback")


def verify_active_deployment(account, token, worker_id, name, version_id, request=preflight.request_json):
    """Read Cloudflare's latest deployment and name->ID mapping; not a lock."""
    if (not isinstance(worker_id, str) or not WORKER_ID.fullmatch(worker_id) or
            not isinstance(name, str) or not WORKER_NAME.fullmatch(name) or
            not isinstance(version_id, str) or not VERSION_ID.fullmatch(version_id)):
        raise DeployError("deployment_readback")
    base = f"/client/v4/accounts/{account}/workers"
    def read(path):
        try:
            status, body = request("api.cloudflare.com", path, token)
        except preflight.PreflightError:
            raise DeployError("deployment_readback") from None
        if status != 200 or not isinstance(body, dict) or body.get("success") is not True:
            raise DeployError("deployment_readback")
        return body.get("result")
    def check_name():
        worker = read(f"{base}/workers/{name}")
        if not isinstance(worker, dict) or worker.get("id") != worker_id or worker.get("name") != name:
            raise DeployError("deployment_readback")
    check_name()
    result = read(f"{base}/scripts/{name}/deployments?page=1&per_page=1")
    deployments = result.get("deployments") if isinstance(result, dict) else None
    if not isinstance(deployments, list) or not deployments or not isinstance(deployments[0], dict):
        raise DeployError("deployment_readback")
    active = deployments[0]
    versions = active.get("versions")
    if (active.get("strategy") != "percentage" or not isinstance(versions, list) or len(versions) != 1 or
            not isinstance(versions[0], dict) or versions[0].get("version_id") != version_id or
            versions[0].get("percentage") != 100):
        raise DeployError("deployment_readback")
    check_name()  # catches ordinary renames, but not ABA/concurrent API changes


def disabled_health(host):
    if not re.fullmatch(r"apu-push-[0-9a-f]{20}\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.workers\.dev", host):
        raise DeployError("health")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    req = urllib.request.Request("https://" + host + "/health", headers={"Cache-Control": "no-cache"})
    try:
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(1025)
            if response.code != 503 or len(data) > 1024:
                raise DeployError("health")
        health = preflight.parsed_json(data.decode("utf-8"), 1024)
        if (not isinstance(health, dict) or set(health) != {"ok", "service", "experimental"} or
                health["ok"] is not False or health["service"] != "apu-push-wake" or health["experimental"] is not True):
            raise DeployError("health")
    except DeployError:
        raise
    except (preflight.PreflightError, OSError, ValueError, TypeError, UnicodeError):
        raise DeployError("health") from None


MESSAGES = {
    "invocation": "Wrong repository, branch, commit, or Actions invocation; no deployment attempted.",
    "debug": "Disable Actions debug logging; no deployment attempted.",
    "account": "Cloudflare account ID is missing or malformed; no deployment attempted.",
    "token": "The separate deployment secret is missing or malformed; no deployment attempted.",
    "config": "Worker config is not the isolated, disabled configuration; no deployment attempted.",
    "checkout": "The checked-out commit is not the reviewed clean commit; no deployment attempted.",
    "head": "The designated branch is unavailable or has moved from the reviewed commit; no deployment attempted.",
    "modules": "The checked-in Worker modules are invalid or too large; no deployment attempted.",
    "subdomain": "The existing workers.dev account subdomain could not be verified; no deployment attempted.",
    "create_uncertain": "Worker creation did not complete cleanly: it may exist. No version was sent; inspect before retry. Nothing was deleted.",
    "version_uncertain": "The new Worker may exist and the version may be deployed. Inspect before retry; nothing was deleted.",
    "version_readback": "The new Worker may exist, but its version source and metadata could not be verified. Inspect before retry; nothing was deleted.",
    "deployment_readback": "The new Worker may exist, but the active deployment could not be verified. Inspect before retry; nothing was deleted.",
    "health": "The new Worker may exist but unavailable health was not verified. Inspect before retry; nothing was deleted.",
}


def main():
    try:
        validate_config()
        account, token, name = deployment_inputs(os.environ)
        check_checkout(os.environ["GITHUB_SHA"])
        payload = version_payload()
        subdomain = workers_subdomain(account, token)
        # Print the non-secret name before the first write for uncertain outcomes.
        print(f"::notice::Attempting to create {name}; inspect this name before any retry.", flush=True)
        worker_id = create_worker(account, token, name)
        version_id = create_disabled_version(account, token, worker_id, payload)
        verify_new_version(account, token, worker_id, version_id, payload)
        verify_active_deployment(account, token, worker_id, name, version_id)
        host = f"{name}.{subdomain}.workers.dev"
        disabled_health(host)
        print(f"::notice title=Disabled push Worker::Created {host}; readbacks match disabled code and flags, health reports unavailable.")
        print("::notice::Readbacks are snapshots, not a lock. No FCM credentials supplied; push and delivery remain unverified.")
        return 0
    except DeployError as error:
        print("::error title=Disabled push Worker::" + MESSAGES.get(error.code, "Deployment stopped; inspect before retry."))
        return 1
    except Exception:
        # No exception text: provider or process errors can contain credentials.
        print("::error title=Disabled push Worker::Deployment stopped; inspect before retry. No automatic cleanup was attempted.")
        return 1


def main_cli(argv):
    if not argv:
        return main()
    if argv != ["--check-head"]:
        print("usage: push-disabled-deploy.py [--check-head]", file=sys.stderr)
        return 2
    try:
        values = {name: os.environ.get(name, "") for name in (
            "GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_REF", "GITHUB_SHA", "GH_TOKEN",
            "RUNNER_DEBUG", "APU_GITHUB_STEP_DEBUG", "APU_GITHUB_RUNNER_DEBUG",
        )}
        os.environ.pop("GH_TOKEN", None)
        check_branch_head(values)
        print("The designated branch still points to the reviewed commit (read-only check, not a lock).")
        return 0
    except DeployError as error:
        print("::error title=Disabled push Worker::" + MESSAGES.get(error.code, MESSAGES["head"]))
        return 1
    except Exception:
        # Never print API responses, token contents or exception details.
        print("::error title=Disabled push Worker::" + MESSAGES["head"])
        return 1


if __name__ == "__main__":
    sys.exit(main_cli(sys.argv[1:]))
