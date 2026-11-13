#!/usr/bin/env python3
"""Compare three Firebase project identifiers without disclosing their values.

This diagnostic runs only in the protected GitHub environment. It parses the
existing Android configuration, the service-account JSON, and an owner-supplied
reference Android configuration in memory. It never checks the private key,
opens a connection, writes a file, or invokes another process. A successful
comparison is not proof of Google authentication, permissions, or delivery.
"""

import importlib.util
import json
import os
from pathlib import Path
import re
import sys

REPO = "vzhem/APUMIR"
BRANCH = "arena/1266946d-apumir"
CLIENT_NAME = "APU_FIREBASE_ANDROID_CONFIG"
REFERENCE_NAME = "APU_PUSH_REFERENCE_ANDROID_CONFIG"
SERVICE_NAME = "APU_PUSH_FCM_SERVICE_ACCOUNT"
SECRET_NAMES = (CLIENT_NAME, REFERENCE_NAME, SERVICE_NAME)
MAX_SERVICE_BYTES = 16_384

# Do not create bytecode files when loading the shared client validator.
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "firebase_client_validator", Path(__file__).with_name("check-firebase-client-config.py")
)
client_validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client_validator)


class ComparisonError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


ERROR_MESSAGES = {
    "invocation": "This diagnostic can run only from the designated GitHub Actions branch.",
    "debug": "Disable GitHub Actions debug logging before this diagnostic.",
    "client_missing": "The existing Android client configuration is missing.",
    "reference_missing": "The reference Android client configuration is missing from the protected environment.",
    "service_missing": "The existing service-account configuration is missing.",
    "client_invalid": "The existing Android client configuration is invalid.",
    "reference_invalid": "The reference must be a valid Android client configuration, not a service-account key.",
    "service_invalid": "The existing service-account configuration is invalid.",
    "unavailable": "The project comparison could not be completed.",
}

RESULT_MESSAGES = {
    (True, True): "Both GitHub configurations refer to the reference Android project.",
    (True, False): "The GitHub Android configuration refers to the reference project; the GitHub service account does not.",
    (False, True): "The GitHub service account refers to the reference project; the GitHub Android configuration does not.",
    (False, False): "Neither GitHub configuration refers to the reference Android project.",
}


def check_invocation(env):
    if (env.get("GITHUB_ACTIONS") != "true" or env.get("GITHUB_REPOSITORY") != REPO
            or env.get("GITHUB_REF") != "refs/heads/" + BRANCH):
        raise ComparisonError("invocation")
    debug_names = ("APU_GITHUB_STEP_DEBUG", "APU_GITHUB_RUNNER_DEBUG")
    if env.get("RUNNER_DEBUG") == "1" or any(env.get(name, "").lower() == "true" for name in debug_names):
        raise ComparisonError("debug")


def android_project(raw, error_code):
    if client_validator.validate(raw) != "valid":
        raise ComparisonError(error_code)
    try:
        config = json.loads(raw, object_pairs_hook=client_validator._unique_object,
                            parse_constant=client_validator._invalid_constant)
        project = config["project_info"]["project_id"]
        if not isinstance(project, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{4,62}", project):
            raise ComparisonError(error_code)
        return project
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
        raise ComparisonError(error_code) from None


def service_project(raw):
    try:
        if not isinstance(raw, str) or not raw or len(raw.encode("utf-8")) > MAX_SERVICE_BYTES:
            raise ComparisonError("service_invalid")
        config = json.loads(raw, object_pairs_hook=client_validator._unique_object,
                            parse_constant=client_validator._invalid_constant)
        if not isinstance(config, dict) or config.get("type") != "service_account":
            raise ComparisonError("service_invalid")
        project = config.get("project_id")
        if not isinstance(project, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{4,62}", project):
            raise ComparisonError("service_invalid")
        return project
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ComparisonError("service_invalid") from None


def compare(values):
    for name, code in ((REFERENCE_NAME, "reference_missing"), (CLIENT_NAME, "client_missing"),
                       (SERVICE_NAME, "service_missing")):
        if not values.get(name):
            raise ComparisonError(code)
    reference = android_project(values[REFERENCE_NAME], "reference_invalid")
    client = android_project(values[CLIENT_NAME], "client_invalid")
    service = service_project(values[SERVICE_NAME])
    return client == reference, service == reference


def main():
    values = {name: os.environ.pop(name, "") for name in SECRET_NAMES}
    result_message = None
    error_code = None
    try:
        check_invocation(os.environ)
        result_message = RESULT_MESSAGES.get(compare(values))
        if result_message is None:
            raise ComparisonError("unavailable")
    except ComparisonError as error:
        error_code = error.code
    except Exception:
        # Never print exception text or input excerpts; they can contain keys.
        error_code = "unavailable"
    finally:
        values.clear()
    if result_message is None:
        message = ERROR_MESSAGES["unavailable"]
        if isinstance(error_code, str):
            message = ERROR_MESSAGES.get(error_code, message)
        print("::error title=Firebase project comparison::" + message)
        return 1
    print("::notice title=Firebase project comparison::" + result_message)
    print("Diagnostic only: no key authentication, Cloudflare or Google requests were performed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
