#!/usr/bin/env python3
"""Hash the actual public site files after a staged publication.

This is an HTTP snapshot, not an atomic transaction or a physical device test.
Failure is fatal to the publisher; the script never writes to the site.
"""
import hashlib
import json
import os
import sys
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
    return opener.open(request, timeout=30)


def verify_one(spec: SiteFile, fetch=open_site) -> None:
    digest, size = hashlib.sha256(), 0
    with fetch(spec.name) as response:
        while True:
            block = response.read(min(BLOCK, spec.size - size + 1))
            if not block:
                break
            size += len(block)
            if size > spec.size:
                raise ValueError(f"site file too large: {spec.name}")
            digest.update(block)
    if size != spec.size or digest.hexdigest() != spec.digest:
        raise ValueError(f"site file size or digest mismatch: {spec.name}")


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
    except (OSError, ValueError, TypeError, KeyError) as error:
        # HTTP failures can contain URLs; expose only the error type.
        print(f"::error::Site HTTP verification failed: {type(error).__name__}")
        return 1
    print("::notice::All public site file bytes and metadata match the verified release snapshot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
