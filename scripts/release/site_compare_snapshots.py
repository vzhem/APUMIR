#!/usr/bin/env python3
"""Read-only comparison of every public site file with two verified snapshots.

This is a bounded HTTP observation, not a transactional snapshot. It never
writes to FTP, and classifies a mixed or unavailable result as unverified.
The caller supplies two release snapshots already downloaded into the local
workspace; both are validated again before any public URL is read.
"""
import hashlib
import http.client
import json
import os
import sys
import time
import urllib.error
from pathlib import Path

from site_release_files import (
    MAX_METADATA_BYTES, MAX_TRANSFER_BYTES, SHA_RE, SITE_NAMES, SiteFile,
    tracked_fingerprint, validate_inputs,
)
from site_staged_upload import current_release_matches
from site_verify_http import BLOCK, open_site


def _capture(name: str, limit: int, fetch, sleeper) -> tuple:
    """Hash one bounded response; retry transport failures, never wrong bytes."""
    for attempt in range(3):
        digest, size = hashlib.sha256(), 0
        try:
            with fetch(name) as response:
                while True:
                    block = response.read(min(BLOCK, limit - size + 1))
                    if not block:
                        break
                    size += len(block)
                    if size > limit:
                        return ("oversized", None, None)
                    digest.update(block)
        except urllib.error.HTTPError as error:
            if error.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                return ("error", f"HTTP {error.code}", None)
        except (OSError, TimeoutError, http.client.HTTPException) as error:
            if attempt == 2:
                return ("error", type(error).__name__, None)
        except ValueError:
            # A rejected redirect must not leak a URL or response detail.
            return ("error", "request rejected", None)
        else:
            return ("bytes", size, digest.hexdigest())
        sleeper(1 if attempt == 0 else 3)
    raise AssertionError("unreachable retry state")


def compare_all(old: list[SiteFile], new: list[SiteFile], fetch=open_site, sleeper=time.sleep, log=print) -> str:
    if tuple(spec.name for spec in old) != SITE_NAMES or tuple(spec.name for spec in new) != SITE_NAMES:
        raise ValueError("invalid comparison input order")
    for spec in (*old, *new):
        if (type(spec.size) is not int or not 0 < spec.size <= MAX_TRANSFER_BYTES
                or not isinstance(spec.digest, str) or not SHA_RE.fullmatch(spec.digest)):
            raise ValueError("invalid comparison input")
    old_map = {spec.name: spec for spec in old}
    new_map = {spec.name: spec for spec in new}
    order = ("latest.json", "feature-packs.json", "app-release.apk", *SITE_NAMES[:-3],
             "feature-packs.json", "latest.json")
    labels = []
    seen = {}
    pointers_changed = False
    for name in order:
        before, after = old_map[name], new_map[name]
        snapshot = _capture(name, max(before.size, after.size), fetch, sleeper)
        if snapshot[0] == "error":
            label = "unavailable"
            detail = f" ({snapshot[1]})"
        elif snapshot[0] == "oversized":
            label, detail = "neither", " (oversized)"
        else:
            actual = (snapshot[1], snapshot[2])
            is_old = actual == (before.size, before.digest)
            is_new = actual == (after.size, after.digest)
            label = "both" if is_old and is_new else "old" if is_old else "new" if is_new else "neither"
            detail = ""
        if name in seen and snapshot != seen[name]:
            pointers_changed = True
        seen[name] = snapshot
        labels.append(label)
        log(f"::notice title=Site snapshot comparison::{name}: {label}{detail}")
    if pointers_changed:
        log("::warning title=Site snapshot comparison::A pointer changed while files were read")
        return "mixed or unverified"
    if all(label in {"old", "both"} for label in labels):
        return "all old"
    if all(label in {"new", "both"} for label in labels):
        return "all new"
    return "mixed or unverified"


def _load_release(path: Path) -> dict:
    with path.open("rb") as source:
        data = source.read(MAX_METADATA_BYTES + 1)
    if len(data) > MAX_METADATA_BYTES:
        raise ValueError("release metadata too large")
    return json.loads(data)


def main() -> int:
    try:
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        old_tag = os.environ.get("APU_REFERENCE_TAG", "")
        new_tag = os.environ.get("APU_RELEASE_TAG", "")
        old_release = _load_release(Path("site-reference.json"))
        new_release = _load_release(Path("site-release.json"))
        old = validate_inputs(Path("reference-files"), old_release, old_tag)
        new = validate_inputs(Path("site-files"), new_release, new_tag)
        fingerprint = tracked_fingerprint(new_release, new_tag)
        before = current_release_matches(repository, new_tag, fingerprint)
        result = compare_all(old, new)
        after = current_release_matches(repository, new_tag, fingerprint)
        if not before or not after:
            print("::warning title=Site snapshot comparison::Latest could not be confirmed throughout the comparison")
        print(f"::notice title=Site snapshot comparison::Overall: {result}; this is not an atomic snapshot")
        return 0 if before and after and result in {"all old", "all new"} else 1
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"::error title=Site snapshot comparison::Comparison failed: {type(error).__name__}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
