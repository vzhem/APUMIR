#!/usr/bin/env python3
"""Read-only, redacted preflight for a future isolated push deployment.

No Worker is created or changed, no secret is uploaded to Cloudflare, and no
Firebase/FCM request is made. GitHub environment protection is checked before
any environment secret is exposed to the second job. Verification of an active
Cloudflare token and read access is not proof of deploy permission or delivery.
"""

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPO = "vzhem/APUMIR"
# The protected environment may be restricted to one branch. Both the branch
# that created the disabled prototype and the current line are accepted, so the
# check does not need to be weakened when the work continues elsewhere.
BRANCHES = ("arena/1266946d-apumir", "arena/2d5de8a6-apumir")
ENVIRONMENT = "apu-push-staging"
OWNER_ID = 314380434
MAX_BODY = 128 * 1024
CF_TOKEN_NAME = "APU_PUSH_CLOUDFLARE_TOKEN"
CF_ACCOUNT_NAME = "APU_PUSH_CLOUDFLARE_ACCOUNT_ID"
FCM_ACCOUNT_NAME = "APU_PUSH_FCM_SERVICE_ACCOUNT"
CLIENT_CONFIG_NAME = "APU_FIREBASE_ANDROID_CONFIG"

spec = importlib.util.spec_from_file_location("push_firebase_config_validator", Path(__file__).with_name("check-firebase-client-config.py"))
client_validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client_validator)


class PreflightError(Exception):
    """Only fixed codes, never exception details or input values."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


class CloudflareCredentials:
    def __init__(self, account, token):
        self.account = account
        self.token = token

    def __repr__(self):
        return "CloudflareCredentials(redacted)"


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise PreflightError("config_invalid")
        obj[key] = value
    return obj


def invalid_constant(_value):
    raise PreflightError("config_invalid")


def parsed_json(raw, maximum):
    try:
        if not isinstance(raw, str) or not raw or len(raw.encode("utf-8")) > maximum:
            raise PreflightError("config_invalid")
        return json.loads(raw, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise PreflightError("config_invalid") from None


def rsa_key_valid(private_key):
    """Check Google-style PKCS#8 RSA without writing it to disk or printing it."""
    if not isinstance(private_key, str) or not 1_000 <= len(private_key) <= 8_192:
        return False
    if not re.fullmatch(r"-----BEGIN PRIVATE KEY-----\n(?:[A-Za-z0-9+/=]+\n)+-----END PRIVATE KEY-----\n?", private_key):
        return False
    try:
        checked = subprocess.run(
            ["openssl", "rsa", "-check", "-noout"], input=private_key.encode("ascii"),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, check=False,
            env={"PATH": os.defpath, "LC_ALL": "C"},
        )
        return checked.returncode == 0
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return False


def credentials(values, validate_key=rsa_key_valid):
    """Validate inputs without emitting identifiers, secrets, or error details."""
    names = (CF_TOKEN_NAME, CF_ACCOUNT_NAME, FCM_ACCOUNT_NAME, CLIENT_CONFIG_NAME)
    if any(not values.get(name) for name in names):
        raise PreflightError("config_missing")
    token, account, raw_fcm, raw_client = (values[name] for name in names)
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token):
        raise PreflightError("cloudflare_token_invalid")
    if not isinstance(account, str) or not re.fullmatch(r"[0-9a-f]{32}", account):
        raise PreflightError("cloudflare_account_invalid")
    if client_validator.validate(raw_client) != "valid":
        raise PreflightError("client_invalid")
    client = parsed_json(raw_client, 65_536)
    try:
        service = parsed_json(raw_fcm, 16_384)
    except PreflightError:
        # Report only which fixed configuration needs attention, never JSON
        # excerpts, field values, exception text or provider responses.
        raise PreflightError("fcm_json_invalid") from None
    if not isinstance(client, dict) or not isinstance(service, dict) or service.get("type") != "service_account":
        raise PreflightError("fcm_json_invalid")
    project = service.get("project_id")
    email = service.get("client_email")
    private_key = service.get("private_key")
    if not isinstance(project, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{4,62}", project):
        raise PreflightError("fcm_identity_invalid")
    if not isinstance(email, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+", email):
        raise PreflightError("fcm_identity_invalid")
    if not email.endswith("@" + project + ".iam.gserviceaccount.com"):
        raise PreflightError("fcm_identity_invalid")
    if client.get("project_info", {}).get("project_id") != project:
        raise PreflightError("project_mismatch")
    if not validate_key(private_key):
        raise PreflightError("key_invalid")
    # Do not return or persist the FCM private key, email, or client config.
    return CloudflareCredentials(account, token)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, _req, _fp, _code, _msg, _headers, _newurl):
        return None


def request_json(host, path, token):
    """GET only, fixed host, no redirects/proxy, bounded response, no error body logs."""
    if host not in {"api.github.com", "api.cloudflare.com"} or not path.startswith("/"):
        raise PreflightError("unavailable")
    request = urllib.request.Request(
        "https://" + host + path,
        method="GET",
        headers={"Authorization": "Bearer " + token, "Accept": "application/json", "User-Agent": "APU-push-preflight"},
    )
    if host == "api.github.com":
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        try:
            response = opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            # Keep only the status. Bodies can contain confidential diagnostics.
            error.close()
            return error.code, None
        with response:
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise PreflightError("unavailable")
            result = json.loads(body.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=invalid_constant)
            return response.status, result
    except PreflightError:
        raise
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        raise PreflightError("unavailable") from None


def deployment_target(values):
    """Return (account, deploy token) for a reviewed deployment of this branch.

    Only fixed codes leave this function: no token, account, or provider text.
    """
    invocation(values)
    account = values.get(CF_ACCOUNT_NAME, "")
    token = values.get("APU_PUSH_CLOUDFLARE_DEPLOY_TOKEN", "")
    if not isinstance(account, str) or not re.fullmatch(r"[0-9a-f]{32}", account):
        raise PreflightError("cloudflare_account_invalid")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._-]{16,512}", token):
        raise PreflightError("cloudflare_token_invalid")
    return account, token


def check_environment(token, request=request_json):
    if not isinstance(token, str) or not token:
        raise PreflightError("environment")
    prefix = f"/repos/{REPO}/environments/{ENVIRONMENT}"
    status, body = request("api.github.com", prefix, token)
    if status != 200 or not isinstance(body, dict):
        raise PreflightError("environment")
    policy = body.get("deployment_branch_policy")
    if not isinstance(policy, dict) or policy.get("protected_branches") is not False or policy.get("custom_branch_policies") is not True:
        raise PreflightError("environment")
    if body.get("can_admins_bypass") is not False:
        raise PreflightError("environment")
    rules = body.get("protection_rules")
    if not isinstance(rules, list):
        raise PreflightError("environment")
    required = [rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "required_reviewers"]
    if len(required) != 1 or required[0].get("prevent_self_review") is not False:
        raise PreflightError("environment")
    reviewers = required[0].get("reviewers")
    if not isinstance(reviewers, list) or len(reviewers) != 1:
        raise PreflightError("environment")
    reviewer = reviewers[0]
    if not isinstance(reviewer, dict) or reviewer.get("type") != "User" or not isinstance(reviewer.get("reviewer"), dict):
        raise PreflightError("environment")
    if reviewer["reviewer"].get("id") != OWNER_ID:
        raise PreflightError("environment")
    status, body = request("api.github.com", prefix + "/deployment-branch-policies?per_page=100", token)
    if status != 200 or not isinstance(body, dict):
        raise PreflightError("environment")
    policies = body.get("branch_policies")
    if not isinstance(policies, list) or body.get("total_count") != len(policies):
        raise PreflightError("environment")
    # The environment accumulates one rule per branch that a deployment may start
    # from. Requiring exactly one made this check fail the moment a second rule
    # was added, which is a legitimate protection change, not a missing one. What
    # must hold is that every rule is a branch rule inside the reviewed set, and
    # that an unknown rule can never widen the environment.
    names = set()
    for policy in policies:
        if not isinstance(policy, dict) or policy.get("type") != "branch":
            raise PreflightError("environment")
        name = policy.get("name")
        if not isinstance(name, str) or name not in BRANCHES:
            raise PreflightError("environment")
        names.add(name)
    # A duplicate rule protects nothing extra and is not something GitHub creates
    # on its own, so it stays a reason to stop.
    if not names or len(names) != len(policies):
        raise PreflightError("environment")


def check_cloudflare(credentials, request=request_json):
    account_path = f"/client/v4/accounts/{credentials.account}"
    # Account-owned tokens use the account endpoint; user-owned tokens use the
    # user endpoint. A transient failure is not a reason to try another kind.
    status, body = request("api.cloudflare.com", account_path + "/tokens/verify", credentials.token)
    if status in {401, 403, 404}:
        status, body = request("api.cloudflare.com", "/client/v4/user/tokens/verify", credentials.token)
    if status != 200 or not isinstance(body, dict) or body.get("success") is not True:
        raise PreflightError("cloudflare")
    result = body.get("result")
    if not isinstance(result, dict) or result.get("status") != "active":
        raise PreflightError("cloudflare")
    # A successful list proves read access in this account, not deploy/edit.
    status, body = request("api.cloudflare.com", account_path + "/workers/scripts?per_page=1&page=1", credentials.token)
    if status != 200 or not isinstance(body, dict) or body.get("success") is not True or not isinstance(body.get("result"), list):
        raise PreflightError("cloudflare")


def invocation(values):
    if values.get("GITHUB_ACTIONS") != "true" or values.get("GITHUB_REPOSITORY") != REPO or \
            values.get("GITHUB_REF") not in {"refs/heads/" + branch for branch in BRANCHES}:
        raise PreflightError("invocation")
    debug_flags = ("APU_GITHUB_STEP_DEBUG", "APU_GITHUB_RUNNER_DEBUG")
    if values.get("RUNNER_DEBUG") == "1" or any(values.get(name, "").lower() == "true" for name in debug_flags):
        # Refuse known runner/step debug settings before multiline secrets.
        raise PreflightError("debug")


MESSAGES = {
    "invocation": "This check can run only from the designated GitHub Actions branch.",
    "debug": "Disable GitHub Actions debug logging before running this preflight.",
    "environment": "The GitHub environment is absent or does not have the required reviewer and branch protection.",
    "config_missing": "One or more required GitHub environment secrets or the existing Android client configuration are missing.",
    "config_invalid": "The Cloudflare or Firebase service-account configuration is malformed.",
    "cloudflare_token_invalid": "The Cloudflare API token format is invalid; check APU_PUSH_CLOUDFLARE_TOKEN without sharing its value.",
    "cloudflare_account_invalid": "The Cloudflare account ID format is invalid; check APU_PUSH_CLOUDFLARE_ACCOUNT_ID without sharing its value.",
    "fcm_json_invalid": "The Firebase service-account JSON is invalid; check APU_PUSH_FCM_SERVICE_ACCOUNT without sharing its value.",
    "fcm_identity_invalid": "The Firebase service-account project or email format is invalid; check APU_PUSH_FCM_SERVICE_ACCOUNT without sharing its value.",
    "client_invalid": "The existing Firebase Android client configuration is missing or invalid.",
    "project_mismatch": "The Firebase service account and Android client configuration belong to different projects.",
    "key_invalid": "The Firebase service-account RSA private key could not be checked.",
    "cloudflare": "Cloudflare could not verify the token or read Workers in this account; no deployment was attempted.",
    "unavailable": "A required verification service is unavailable or returned an invalid response.",
}


def main(argv):
    if argv not in (["--check-protection"], ["--check-secrets"]):
        print("usage: push-infra-preflight.py --check-protection|--check-secrets", file=sys.stderr)
        return 2
    try:
        invocation(os.environ)
        if argv == ["--check-protection"]:
            token = os.environ.pop("GH_TOKEN", "")
            check_environment(token)
            print("GitHub staging environment has the required owner review and exact branch restriction.")
        else:
            names = (CF_TOKEN_NAME, CF_ACCOUNT_NAME, FCM_ACCOUNT_NAME, CLIENT_CONFIG_NAME)
            values = {name: os.environ.pop(name, "") for name in names}
            try:
                config = credentials(values)
            finally:
                # Drop references before networking. Immutable Python strings
                # cannot be reliably erased from memory.
                values.clear()
            check_cloudflare(config)
            print("Firebase projects match structurally; the Cloudflare token is active and can read Workers in the selected account.")
            print("Read-only check: no Worker deployment, FCM authentication or message delivery was tested.")
        return 0
    except PreflightError as error:
        # GitHub exposes annotations through the API even where redirected logs
        # cannot be downloaded. Only predefined text, never input/exception text.
        print("::error title=Push infrastructure preflight::" + MESSAGES.get(error.code, MESSAGES["unavailable"]))
        return 1
    except Exception:
        # Never print exception text: malformed input and transport failures can contain secrets.
        print("::error title=Push infrastructure preflight::" + MESSAGES["unavailable"])
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
