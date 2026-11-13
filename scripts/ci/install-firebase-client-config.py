#!/usr/bin/env python3
"""Install a validated client-only Firebase config for an isolated CI build.

The real value is read from a step environment and never printed. The test
mode uses fabricated public-looking identifiers and cannot contact a real
project. The file is ignored by Git and CI must delete it in an always step.
"""

import importlib.util
import json
import os
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("firebase_config_validator", HERE / "check-firebase-client-config.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
DESTINATION = HERE.parent.parent / "android-app/app/google-services.json"


def fake_configuration():
    return json.dumps({
        "project_info": {"project_number": "123456789012", "project_id": "apu-ci-test-project"},
        "client": [{
            "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:0123456789abcdef",
                "android_client_info": {"package_name": "com.vladimir.messenger"},
            },
            "api_key": [{"current_key": "fake-api-key-for-offline-ci-only"}],
        }],
        "configuration_version": "1",
    })


def write_config(raw, destination):
    if validator.validate(raw) != "valid":
        return False
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = None
    created = False
    try:
        fd = os.open(destination, flags, 0o600)
        created = True
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            fd = None
            out.write(raw)
        return True
    except OSError:
        if fd is not None:
            os.close(fd)
        if created:
            try:
                destination.unlink()
            except OSError:
                # The runner is ephemeral and reports failure below; never log file contents.
                pass
        return False


def main(args):
    if args not in ([], ["--fake"]):
        print("Expected no arguments or --fake.", file=sys.stderr)
        return 2
    raw = fake_configuration() if args == ["--fake"] else os.environ.pop(validator.ENV_NAME, "")
    if not write_config(raw, DESTINATION):
        print("Firebase client configuration could not be validated or installed safely.", file=sys.stderr)
        return 1
    print("Firebase client configuration installed for this CI build only.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
