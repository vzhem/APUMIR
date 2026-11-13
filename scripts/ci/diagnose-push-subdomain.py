#!/usr/bin/env python3
"""Protected, read-only diagnosis of a failed workers.dev subdomain lookup.

Only fixed Cloudflare GETs are made. A deploy credential is used solely for
read requests, alongside the separately stored read credential. HTTP errors are
read with a small limit; only a fixed allowlist of numeric Cloudflare error codes
is reported. Never print credentials, account IDs, response bodies, messages,
Worker names or the subdomain. The result cannot establish the token's full
permissions or the ability to deploy.
"""

import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
READ_TOKEN = "APU_PUSH_CLOUDFLARE_TOKEN"
DEPLOY_TOKEN = "APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN"
ACCOUNT = "APU_PUSH_CLOUDFLARE_ACCOUNT_ID"
MAX_ERROR_BYTES = 16 * 1024
SAFE_CF_CODES = frozenset({6003, 6103, 9103, 9106, 9109, 10000, 10001})
SAFE_CF_CATEGORIES = frozenset({"CF_NONE", "CF_OTHER", "CF_MULTIPLE", "CF_UNAVAILABLE"} |
                               {f"CF_{code}" for code in SAFE_CF_CODES})
HTTP_CATEGORIES = frozenset({"HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_429",
                             "HTTP_REDIRECT_BLOCKED", "HTTP_OTHER_4XX", "HTTP_5XX", "HTTP_UNEXPECTED"})

spec = importlib.util.spec_from_file_location("push_infra_preflight", HERE / "push-infra-preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class DiagnosticError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def validate_inputs(values):
    try:
        preflight.invocation(values)
    except preflight.PreflightError as error:
        raise DiagnosticError(error.code) from None
    sha = values.get("GITHUB_SHA", "")
    account = values.get(ACCOUNT, "")
    read_token = values.get(READ_TOKEN, "")
    deploy_token = values.get(DEPLOY_TOKEN, "")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise DiagnosticError("invocation")
    if not isinstance(account, str) or not re.fullmatch(r"[0-9a-f]{32}", account):
        raise DiagnosticError("account")
    if not isinstance(read_token, str) or not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", read_token):
        raise DiagnosticError("read_token")
    if not isinstance(deploy_token, str) or not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", deploy_token):
        raise DiagnosticError("deploy_token")
    if read_token == deploy_token:
        raise DiagnosticError("same_token")
    return sha, account, read_token, deploy_token


def check_checkout(sha):
    # No secrets, configured Git hooks, global config, or prompts in child Git.
    env = {"PATH": os.defpath, "HOME": "/", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}
    try:
        head = subprocess.run(["git", "-c", "core.fsmonitor=false", "rev-parse", "HEAD"], cwd=ROOT, env=env,
                              check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode("ascii").strip()
        changed = subprocess.run(["git", "-c", "core.fsmonitor=false", "status", "--porcelain", "--untracked-files=no"],
                                 cwd=ROOT, env=env, check=True, timeout=10, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    except (OSError, UnicodeError, subprocess.SubprocessError):
        raise DiagnosticError("checkout") from None
    if head != sha or changed:
        raise DiagnosticError("checkout")


def http_category(status, body=None):
    # Only fixed categories can leave the process; never include provider text.
    if status in (400, 401, 403, 404, 429):
        category = f"HTTP_{status}"
    elif isinstance(status, int) and 300 <= status < 400:
        category = "HTTP_REDIRECT_BLOCKED"
    elif isinstance(status, int) and 400 <= status < 500:
        category = "HTTP_OTHER_4XX"
    elif isinstance(status, int) and 500 <= status < 600:
        category = "HTTP_5XX"
    else:
        category = "HTTP_UNEXPECTED"
    safe_code = body.get("_safe_code") if isinstance(body, dict) else None
    if isinstance(safe_code, str) and safe_code in SAFE_CF_CATEGORIES:
        return category + "+" + safe_code
    return category


def allowed_request(path, token):
    return (isinstance(path, str) and re.fullmatch(
            r"/client/v4/(?:accounts/[0-9a-f]{32}/(?:tokens/verify|workers/subdomain|workers/scripts\?per_page=1&page=1)|user/tokens/verify)", path) is not None and
            isinstance(token, str) and re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token) is not None)


def error_code_category(raw):
    """Read only recognized numeric error codes, never the provider's messages."""
    if not isinstance(raw, bytes) or len(raw) > MAX_ERROR_BYTES:
        return "CF_UNAVAILABLE"
    if not raw:
        return "CF_NONE"
    try:
        body = preflight.parsed_json(raw.decode("utf-8"), MAX_ERROR_BYTES)
    except (preflight.PreflightError, UnicodeError):
        return "CF_UNAVAILABLE"
    errors = body.get("errors") if isinstance(body, dict) else None
    if not isinstance(errors, list):
        return "CF_NONE"
    codes = {entry.get("code") for entry in errors if isinstance(entry, dict) and type(entry.get("code")) is int}
    if not codes:
        return "CF_NONE"
    if len(codes) != 1:
        return "CF_MULTIPLE"
    code = next(iter(codes))
    return f"CF_{code}" if code in SAFE_CF_CODES else "CF_OTHER"


def diagnostic_request(host, path, token):
    """Fixed GET, bounded success/error bodies, no redirects, proxies or logs."""
    if host != "api.cloudflare.com" or not allowed_request(path, token):
        raise preflight.PreflightError("unavailable")
    req = urllib.request.Request("https://api.cloudflare.com" + path, method="GET", headers={
        "Authorization": "Bearer " + token, "Accept": "application/json", "User-Agent": "APU-push-preflight",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), preflight.NoRedirect())
    try:
        try:
            response = opener.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            status = error.code
            try:
                with error:
                    raw = error.read(MAX_ERROR_BYTES + 1)
                safe_code = error_code_category(raw)
            except Exception:
                # Even malformed/oversized error bodies and exception text may
                # contain secrets; only a fixed category is retained.
                safe_code = "CF_UNAVAILABLE"
            return status, {"_safe_code": safe_code}
        with response:
            raw = response.read(preflight.MAX_BODY + 1)
            if len(raw) > preflight.MAX_BODY:
                raise preflight.PreflightError("unavailable")
            return response.status, preflight.parsed_json(raw.decode("utf-8"), preflight.MAX_BODY)
    except preflight.PreflightError:
        raise
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise preflight.PreflightError("unavailable") from None


def read(request, path, token):
    # Restrict even internal callers to the documented read-only endpoints.
    if not allowed_request(path, token):
        raise DiagnosticError("internal")
    try:
        return request("api.cloudflare.com", path, token)
    except preflight.PreflightError:
        return None, None


def verify_status(request, account_path, token):
    status, body = read(request, account_path + "/tokens/verify", token)
    via = "ACCOUNT"
    # As in the existing preflight: account-owned tokens use the account
    # endpoint; user-owned tokens may require the user endpoint instead.
    if status in (401, 403, 404):
        via = "USER"
        status, body = read(request, "/client/v4/user/tokens/verify", token)
    if status is None:
        return "UNAVAILABLE"
    if status != 200:
        return http_category(status, body)
    if not isinstance(body, dict) or body.get("success") is not True or not isinstance(body.get("result"), dict):
        return "UNEXPECTED_RESPONSE"
    token_status = body["result"].get("status")
    if token_status == "active":
        # This reports the accepting endpoint, not the token's full scope.
        return "OK_" + via
    if token_status in ("disabled", "expired"):
        return "NOT_ACTIVE"
    return "UNEXPECTED_RESPONSE"


def list_status(request, account_path, token):
    status, body = read(request, account_path + "/workers/scripts?per_page=1&page=1", token)
    if status is None:
        return "UNAVAILABLE"
    if status != 200:
        return http_category(status, body)
    if not isinstance(body, dict) or body.get("success") is not True or not isinstance(body.get("result"), list):
        return "UNEXPECTED_RESPONSE"
    return "OK"


def subdomain_status(request, account_path, token):
    status, body = read(request, account_path + "/workers/subdomain", token)
    if status is None:
        return "UNAVAILABLE"
    if status != 200:
        return http_category(status, body)
    if not isinstance(body, dict) or body.get("success") is not True:
        return "UNEXPECTED_RESPONSE"
    result = body.get("result")
    if result is None:
        return "EMPTY_RESULT"
    if not isinstance(result, dict):
        return "UNEXPECTED_RESPONSE"
    subdomain = result.get("subdomain")
    if subdomain is None or subdomain == "":
        return "SUBDOMAIN_UNSET_IN_RESPONSE"
    if not isinstance(subdomain, str) or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", subdomain):
        return "INVALID_SUBDOMAIN_SHAPE"
    return "OK"


def collect(account, read_token, deploy_token, request=None):
    request = request or diagnostic_request
    account_path = f"/client/v4/accounts/{account}"
    results = {}
    for label, token in (("READ", read_token), ("DEPLOY", deploy_token)):
        results[label] = (verify_status(request, account_path, token),
                          list_status(request, account_path, token),
                          subdomain_status(request, account_path, token))
    return results


CATEGORIES = frozenset({"OK", "OK_ACCOUNT", "OK_USER", "NOT_ACTIVE", "UNAVAILABLE", "UNEXPECTED_RESPONSE",
                        "EMPTY_RESULT", "SUBDOMAIN_UNSET_IN_RESPONSE", "INVALID_SUBDOMAIN_SHAPE"} |
                       set(HTTP_CATEGORIES) |
                       {http + "+" + code for http in HTTP_CATEGORIES for code in SAFE_CF_CATEGORIES})
MESSAGES = {
    "invocation": "Wrong repository, branch, commit, or Actions invocation; no Cloudflare request was made.",
    "debug": "Disable Actions debug logging; no Cloudflare request was made.",
    "account": "The account ID secret is missing or malformed; no Cloudflare request was made.",
    "read_token": "The separate read token secret is missing or malformed; no Cloudflare request was made.",
    "deploy_token": "The separate deployment token secret is missing or malformed; no Cloudflare request was made.",
    "same_token": "The read and deployment token secrets contain the same value; separate tokens are required. No Cloudflare request was made.",
    "checkout": "The checkout does not match the reviewed clean commit; no Cloudflare request was made.",
}


def main():
    # Remove secrets from the environment before Git or network calls. Python
    # strings remain in memory temporarily and cannot be securely erased.
    secret_values = {name: os.environ.pop(name, "") for name in (ACCOUNT, READ_TOKEN, DEPLOY_TOKEN)}
    values = {}
    try:
        context_names = ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_REF", "GITHUB_SHA", "RUNNER_DEBUG",
                         "APU_GITHUB_STEP_DEBUG", "APU_GITHUB_RUNNER_DEBUG")
        values = {name: os.environ.get(name, "") for name in context_names}
        values.update(secret_values)
        sha, account, read_token, deploy_token = validate_inputs(values)
        secret_values.clear()
        values.clear()
        check_checkout(sha)
        results = collect(account, read_token, deploy_token)
        if (set(results) != {"READ", "DEPLOY"} or
                any(not isinstance(results[label], tuple) or len(results[label]) != 3 or
                    any(value not in CATEGORIES for value in results[label]) for label in ("READ", "DEPLOY"))):
            raise DiagnosticError("internal")
        for label in ("READ", "DEPLOY"):
            verify, scripts, subdomain = results[label]
            print(f"::notice title=Push subdomain diagnostic::{label}: verify={verify}, list={scripts}, subdomain={subdomain}")
        if any(statuses[0] not in ("OK_ACCOUNT", "OK_USER") or statuses[1:] != ("OK", "OK")
               for statuses in results.values()):
            print("::error title=Push subdomain diagnostic::At least one read probe was not verified. A status alone does not prove its cause.")
            return 1
        print("::notice title=Push subdomain diagnostic::Read-only probes succeeded. Full token permissions and deployment are not verified.")
        return 0
    except DiagnosticError as error:
        print("::error title=Push subdomain diagnostic::" + MESSAGES.get(error.code, "Diagnostic stopped; no result was verified."))
        return 1
    except Exception:
        # Provider, subprocess, or malformed input errors may contain secrets.
        print("::error title=Push subdomain diagnostic::Diagnostic stopped; no result was verified.")
        return 1
    finally:
        secret_values.clear()
        values.clear()


if __name__ == "__main__":
    sys.exit(main())
