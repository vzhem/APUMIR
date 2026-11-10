#!/usr/bin/env python3
"""Check the GitHub Firebase Android *client* config without printing its contents.

This is only a structural preflight. It does not contact Firebase or enable FCM.
The credential is passed in one step's environment, never as an argument or file.
A service-account JSON or private key must never be used as the Android config.
"""

import json
import os
import re
import sys

ENV_NAME = "APU_FIREBASE_ANDROID_CONFIG"
PACKAGE = "com.vladimir.messenger"
MAX_CONFIG_BYTES = 65_536
MAX_CLIENTS = 100
PRIVATE_FIELDS = frozenset({"private_key", "private_key_id", "client_email"})


class InvalidConfig(ValueError):
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidConfig()
        result[key] = value
    return result


def _invalid_constant(_value):
    raise InvalidConfig()


def _has_private_field(value):
    if isinstance(value, dict):
        return any(
            key in PRIVATE_FIELDS or _has_private_field(child)
            for key, child in value.items()
        ) or value.get("type") == "service_account"
    if isinstance(value, list):
        return any(_has_private_field(child) for child in value)
    return False


def _text(value, maximum):
    return isinstance(value, str) and 0 < len(value) <= maximum and value == value.strip()


def validate(raw):
    """Return a fixed result code; never include values or exception text."""
    if not raw:
        return "missing"
    try:
        if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_CONFIG_BYTES:
            return "invalid"
        config = json.loads(raw, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
        if not isinstance(config, dict) or _has_private_field(config):
            return "invalid"
        project = config.get("project_info")
        clients = config.get("client")
        if not isinstance(project, dict) or not isinstance(clients, list) or not 1 <= len(clients) <= MAX_CLIENTS:
            return "invalid"
        project_id = project.get("project_id")
        number = project.get("project_number")
        if not _text(project_id, 128) or not isinstance(number, str) or not re.fullmatch(r"[0-9]{1,32}", number):
            return "invalid"
        matching = []
        for client in clients:
            if not isinstance(client, dict):
                return "invalid"
            info = client.get("client_info")
            if not isinstance(info, dict):
                return "invalid"
            android = info.get("android_client_info")
            if isinstance(android, dict) and android.get("package_name") == PACKAGE:
                matching.append(client)
        if len(matching) != 1:
            return "invalid"
        selected = matching[0]
        info = selected["client_info"]
        app_id = info.get("mobilesdk_app_id")
        if not isinstance(app_id, str) or not re.fullmatch(r"1:" + re.escape(number) + r":android:[0-9a-fA-F]{8,64}", app_id):
            return "invalid"
        keys = selected.get("api_key")
        if not isinstance(keys, list) or not any(
            isinstance(key, dict) and _text(key.get("current_key"), 256) for key in keys
        ):
            return "invalid"
        return "valid"
    except (ValueError, TypeError, UnicodeError, RecursionError):
        # No error or JSON excerpts: even malformed input may contain secrets.
        return "invalid"


def main():
    # The variable is not inherited by child processes; this process creates none.
    result = validate(os.environ.pop(ENV_NAME, ""))
    if result == "valid":
        print("Firebase Android client configuration has a matching package and required fields; no network test was performed.")
        return 0
    if result == "missing":
        print("Firebase Android client configuration is missing or unavailable to this workflow.", file=sys.stderr)
    else:
        print("Firebase Android client configuration is invalid, has no unique matching package, or contains server credentials.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
