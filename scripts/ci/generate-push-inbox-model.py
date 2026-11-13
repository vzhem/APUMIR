#!/usr/bin/env python3
"""Extract the real inbox result types for JVM tests without the Android SDK.

This contains no SQLite implementation. Android instrumented tests exercise the
actual store; the standalone harness uses fake queues/schedulers but compiles
against the same nested result/claim declarations as the production source.
"""
import sys
from pathlib import Path

START = "    enum class EnqueueResult"
END = "    /** Only canonical route hashes;"
REQUIRED = (
    "enum class EnqueueResult",
    "enum class Completion",
    "sealed interface ClaimResult",
    "sealed interface PendingResult",
    "class Claim internal constructor(",
)


def generate(source: str) -> str:
    if source.count(START) != 1 or source.count(END) != 1:
        raise ValueError("Cannot identify the production push inbox model")
    start, end = source.index(START), source.index(END)
    if start >= end:
        raise ValueError("Push inbox model markers are out of order")
    model = source[start:end]
    if any(model.count(name) != 1 for name in REQUIRED):
        raise ValueError("Production push inbox result types changed; update the harness")
    return (
        "package com.vladimir.messenger.data.push\n\n"
        "// Extracted from PushWakeInbox.kt; this JVM stub has no SQLite implementation.\n"
        "internal class PushWakeInbox {\n" + model + "}\n"
    )


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate-push-inbox-model.py INPUT.kt OUTPUT.kt")
    source = Path(sys.argv[1]).read_text(encoding="utf-8")
    output = Path(sys.argv[2])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(generate(source), encoding="utf-8")


if __name__ == "__main__":
    main()
