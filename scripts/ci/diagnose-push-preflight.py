#!/usr/bin/env python3
"""Extract only a fixed error category from one previously approved job.

This one-off diagnostic runs without the protected environment. It reads the
single old job log with actions:read, keeps at most 2 MiB in memory, and never
prints log lines, provider responses, credentials or exception details. It
cannot be pointed at an arbitrary job and does not rerun the secret-bearing job.
"""

import os
import re
import selectors
import subprocess
import sys
import time

JOB_ID = "130629589837"
REPOSITORY = "vzhem/APUMIR"
REF = "refs/heads/arena/1266946d-apumir"
MAX_LOG_BYTES = 2 * 1024 * 1024
LOG_TIMEOUT_SECONDS = 45

# Exact, fixed messages from the script at the approved commit. Never emit
# matched lines or provider responses, even when parsing fails.
ERRORS = {
    "invocation": "This check can run only from the designated GitHub Actions branch.",
    "debug": "Disable GitHub Actions debug logging before running this preflight.",
    "environment": "The GitHub environment is absent or does not have the required reviewer and branch protection.",
    "config_missing": "One or more required GitHub environment secrets or the existing Android client configuration are missing.",
    "config_invalid": "The Cloudflare or Firebase service-account configuration is malformed.",
    "client_invalid": "The existing Firebase Android client configuration is missing or invalid.",
    "project_mismatch": "The Firebase service account and Android client configuration belong to different projects.",
    "key_invalid": "The Firebase service-account RSA private key could not be checked.",
    "cloudflare": "Cloudflare could not verify the token or read Workers in this account; no deployment was attempted.",
    "unavailable": "A required verification service is unavailable or returned an invalid response.",
}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
LOG_LINE = re.compile(r"^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z (.*)$")


class LogReadFailure(Exception):
    """The old log is unavailable, oversized or cannot be uniquely parsed."""


def classify(log):
    """Return one known category, or None; never return log text."""
    if not isinstance(log, str):
        return None
    by_message = {message: code for code, message in ERRORS.items()}
    found = set()
    for line in log.splitlines():
        match = LOG_LINE.fullmatch(ANSI.sub("", line))
        if match and match.group(1) in by_message:
            found.add(by_message[match.group(1)])
    return next(iter(found)) if len(found) == 1 else None


def capture_bounded(command, env, *, limit=MAX_LOG_BYTES, timeout=LOG_TIMEOUT_SECONDS):
    """Read stdout without using gh's run-log cache; discard stderr and cap memory."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
    data = bytearray()
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining):
                    raise LogReadFailure()
                chunk = os.read(process.stdout.fileno(), min(65_536, limit + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > limit:
                    raise LogReadFailure()
        remaining = deadline - time.monotonic()
        if remaining <= 0 or process.wait(timeout=remaining) != 0:
            raise LogReadFailure()
        return bytes(data)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()


def read_job_log():
    # `gh api` GETs the log of the fixed job directly, unlike `gh run view`,
    # which downloads and caches the entire run. No debug/proxy variables or
    # other environment credentials are inherited by the child.
    env = {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": os.environ.get("HOME", "/home/runner"),
        "GH_TOKEN": os.environ["GH_TOKEN"],
        "GH_HOST": "github.com",
        "GH_PROMPT_DISABLED": "1",
        "GH_NO_UPDATE_NOTIFIER": "1",
        "NO_COLOR": "1",
    }
    return capture_bounded(["gh", "api", f"repos/{REPOSITORY}/actions/jobs/{JOB_ID}/logs"], env)


def main():
    if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY or
            os.environ.get("GITHUB_REF") != REF or os.environ.get("GITHUB_EVENT_NAME") != "push" or
            not os.environ.get("GH_TOKEN") or os.environ.get("RUNNER_DEBUG") == "1"):
        print("::warning title=Push diagnostic::Diagnostic is unavailable in this context.")
        return 1
    try:
        log = read_job_log()
        code = classify(log.decode("utf-8", errors="replace"))
        if code is None:
            print("::warning title=Push diagnostic::No unique known error category was found.")
            return 1
        # The only variable emitted is a key from ERRORS. Never print raw log,
        # gh stderr, environment variables, URLs, or exception details.
        print(f"::notice title=Push preflight diagnostic::reason={code}")
        return 0
    except (LogReadFailure, OSError, subprocess.SubprocessError):
        print("::warning title=Push diagnostic::The earlier log could not be read safely.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
