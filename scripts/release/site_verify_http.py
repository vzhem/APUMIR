#!/usr/bin/env python3
"""Hash the actual public site files after a staged publication.

This is an HTTP snapshot, not an atomic transaction or a physical device test.
Failure is fatal to the publisher; the script never writes to the site.
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from site_release_files import MAX_METADATA_BYTES, SITE_NAMES, SiteFile, tracked_fingerprint, validate_inputs
from site_staged_upload import current_release_matches

SITE = "https://apu.clan.su/downloads"
BLOCK = 256 * 1024


class SiteRedirectsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlparse(newurl)
        if target.scheme != "https" or target.netloc.lower() != "apu.clan.su":
            raise ValueError("unexpected site redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_site(name: str):
    if name not in SITE_NAMES:
        raise ValueError("unexpected site filename")
    opener = urllib.request.build_opener(SiteRedirectsOnly())
    request = urllib.request.Request(f"{SITE}/{name}", headers={"Cache-Control": "no-cache"})
    return opener.open(request, timeout=60)


class SiteFileError(ValueError):
    def __init__(self, name: str, reason: str):
        super().__init__("site file verification failed")
        self.name = name
        self.reason = reason


def verify_one(spec: SiteFile, fetch=open_site, sleeper=time.sleep) -> None:
    # Network failures can be transient; retry from the beginning. A complete
    # response with the wrong bytes is not a transport retry or a success.
    for attempt in range(3):
        digest, size = hashlib.sha256(), 0
        try:
            with fetch(spec.name) as response:
                while True:
                    block = response.read(min(BLOCK, spec.size - size + 1))
                    if not block:
                        break
                    size += len(block)
                    if size > spec.size:
                        raise SiteFileError(spec.name, "size mismatch")
                    digest.update(block)
        except urllib.error.HTTPError as error:
            if error.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                raise SiteFileError(spec.name, f"HTTP {error.code}") from error
        except (OSError, TimeoutError) as error:
            if attempt == 2:
                raise SiteFileError(spec.name, type(error).__name__) from error
        else:
            if size != spec.size or digest.hexdigest() != spec.digest:
                raise SiteFileError(spec.name, "size or SHA-256 mismatch")
            return
        sleeper(1 if attempt == 0 else 3)


def verify_all(files: list[SiteFile], still_current, fetch=open_site, log=print) -> None:
    if tuple(spec.name for spec in files) != SITE_NAMES:
        raise ValueError("invalid verification input order")
    if not still_current():
        raise ValueError("release changed before site verification")
    by_name = {spec.name: spec for spec in files}
    # Check pointers first, then every file, then pointers again to detect a
    # change while the potentially large downloads were being read.
    order = ("latest.json", "feature-packs.json", "app-release.apk", *SITE_NAMES[:-3],
             "feature-packs.json", "latest.json")
    for name in order:
        verify_one(by_name[name], fetch)
        log(f"Site HTTP verified: {name}")
    if not still_current():
        raise ValueError("release changed during site verification")


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    tag = os.environ.get("APU_RELEASE_TAG", "")
    try:
        with Path("site-release.json").open("rb") as source:
            data = source.read(MAX_METADATA_BYTES + 1)
        if len(data) > MAX_METADATA_BYTES:
            raise ValueError("release metadata too large")
        release = json.loads(data)
        files = validate_inputs(Path("site-files"), release, tag)
        expected = tracked_fingerprint(release, tag)
        verify_all(files, lambda: current_release_matches(repository, tag, expected))
    except SiteFileError as error:
        # Both values are generated from a validated filename or a fixed reason;
        # never print raw URL/error text from the HTTP response.
        print(f"::error title=Site HTTP verification failed::{error.name}: {error.reason}")
        return 1
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"::error::Site HTTP verification failed: {type(error).__name__}")
        return 1
    print("::notice::All public site file bytes and metadata match the verified release snapshot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
