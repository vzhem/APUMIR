#!/usr/bin/env python3
"""Diagnose a failed staged upload without modifying public site files.

Uses randomly named temporary files only, checks for a prior name collision and
removes each owned file. Error reports contain only an operation, exception
class and three-digit FTP code, never server response text or credentials. The
large optional sample is a verified release feature pack and is never promoted
into a public name. FTP is not a transaction: if cleanup fails, a temporary
file may remain and the caller must treat the probe as failed.
"""
import ftplib
import io
import json
import os
import re
import sys
import uuid
import zipfile
from pathlib import Path, PurePosixPath

from site_release_files import (
    MAX_METADATA_BYTES, MAX_TRANSFER_BYTES, SITE_NAMES, _asset_spec, _file_digest,
    release_assets, tracked_fingerprint,
)
from site_staged_upload import FTP_BLOCK_BYTES, FTP_TIMEOUT_SECONDS, current_release_matches, normalize_host, remote_directory

PACK_NAME = "feature-pack-translate-arm64-v8a.zip"
TOKEN = re.compile(r"[0-9a-f]{32}\Z")
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
STAGE_NAME = re.compile(r"apumir-stage-[0-9a-f]{32}-[A-Za-z0-9._-]+\Z")


def _names(ftp) -> set[str]:
    return {PurePosixPath(name).name for name in ftp.nlst()}


def _code(error: Exception) -> str:
    match = re.match(r"^([1-5][0-9]{2})(?:\s|$)", str(error))
    return match.group(1) if match else "unknown"


def _failure(log, label: str, phase: str, error: Exception) -> None:
    log(f"::notice title=FTP temporary probe::{label}: {phase} failed "
        f"({type(error).__name__}, FTP code {_code(error)})")


def _tiny_zip() -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("probe.txt", b"temporary probe, not a release file\n")
    return output.getvalue()


def _one(ftp, label: str, name: str, content, size: int, log) -> tuple[bool, bool]:
    if not SAFE_NAME.fullmatch(name) or name in SITE_NAMES:
        raise ValueError("unsafe temporary filename")
    before = _names(ftp)
    if name in before:
        log(f"::notice title=FTP temporary probe::{label}: pre-existing name; no write or cleanup")
        return False, True
    ok = False
    clean = True
    phase = "STOR"
    try:
        ftp.storbinary(f"STOR {name}", content, blocksize=FTP_BLOCK_BYTES)
        phase = "TYPE"
        ftp.voidcmd("TYPE I")
        phase = "SIZE"
        if ftp.size(name) != size:
            raise ValueError("temporary file size mismatch")
        ok = True
        log(f"::notice title=FTP temporary probe::{label}: STOR and SIZE succeeded")
    except Exception as error:
        _failure(log, label, phase, error)
    finally:
        # Only this collision-checked random name is eligible for deletion.
        # A denied STOR often leaves no file, so do not call DELETE blindly.
        try:
            if name in _names(ftp):
                ftp.delete(name)
                if name in _names(ftp):
                    raise OSError("temporary file remains after cleanup")
            log(f"::notice title=FTP temporary probe::{label}: no temporary file remains in listing")
        except Exception as error:
            clean = False
            _failure(log, label, "cleanup", error)
    return ok, clean


def probe(ftp, large: Path | None = None, token_factory=uuid.uuid4, log=print) -> bool:
    before = _names(ftp)
    old_stages = sum(bool(STAGE_NAME.fullmatch(name)) for name in before)
    log(f"::notice title=FTP temporary probe::Existing staged-name count in listing: {old_stages}")

    tiny_zip = _tiny_zip()
    cases = (
        ("production-zip", lambda t: f"apumir-stage-{t}-{PACK_NAME}", tiny_zip),
        ("short-zip", lambda t: f"apu-probe-{t}.zip", tiny_zip),
        ("production-json", lambda t: f"apumir-stage-{t}-feature-pack-translate-arm64-v8a.json", b'{}\n'),
        ("production-apk", lambda t: f"apumir-stage-{t}-app-release.apk", b"temporary apk probe\n"),
        ("short-apk", lambda t: f"apu-probe-{t}.apk", b"temporary apk probe\n"),
    )
    results = {}
    all_clean = True
    for label, name_for, data in cases:
        token = token_factory().hex
        if not TOKEN.fullmatch(token):
            raise ValueError("invalid probe token")
        ok, clean = _one(ftp, label, name_for(token), io.BytesIO(data), len(data), log)
        results[label] = ok
        all_clean = all_clean and clean
        # Do not create further temporary files when cleanup is uncertain.
        if not clean:
            return False

    if large is not None and results["production-zip"]:
        if large.is_symlink() or not large.is_file() or not 0 < large.stat().st_size <= MAX_TRANSFER_BYTES:
            raise ValueError("unsafe large probe sample")
        token = token_factory().hex
        if not TOKEN.fullmatch(token):
            raise ValueError("invalid probe token")
        with large.open("rb") as source:
            ok, clean = _one(ftp, "release-sized-zip", f"apumir-stage-{token}-{PACK_NAME}",
                             source, large.stat().st_size, log)
        results["release-sized-zip"] = ok
        all_clean = all_clean and clean
    elif large is not None:
        log("::notice title=FTP temporary probe::Release-sized sample skipped because production-shaped ZIP was denied")
    return all_clean and all(results.values())


def main() -> int:
    phase = "inputs"
    ftp = None
    try:
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        tag = os.environ.get("APU_PROBE_TAG", "")
        with Path("site-release.json").open("rb") as source:
            data = source.read(MAX_METADATA_BYTES + 1)
        if len(data) > MAX_METADATA_BYTES:
            raise ValueError("release metadata too large")
        release = json.loads(data)
        assets = release_assets(release, tag)
        _file_digest(Path("probe-files"), PACK_NAME, *_asset_spec(assets, PACK_NAME))
        if not current_release_matches(repository, tag, tracked_fingerprint(release, tag)):
            raise ValueError("release is no longer stable Latest")
        host = normalize_host(os.environ.get("APU_FTP_HOST", ""))
        user, password = os.environ.get("APU_FTP_USER", ""), os.environ.get("APU_FTP_PASSWORD", "")
        if not user or not password or any(c in user + password for c in "\r\n\x00"):
            raise ValueError("invalid FTP credentials")
        remote = remote_directory(os.environ.get("APU_FTP_REMOTE_DIR", ""))
        ftp = ftplib.FTP()
        phase = "connect"
        ftp.connect(host, timeout=FTP_TIMEOUT_SECONDS)
        phase = "login"
        ftp.login(user, password)
        phase = "directory"
        ftp.cwd(remote)
        phase = "probe"
        result = probe(ftp, Path("probe-files") / PACK_NAME)
        if not result:
            print("::error title=FTP temporary probe::One or more temporary samples or their cleanup failed")
            return 1
    except (OSError, EOFError, ValueError, TypeError, KeyError, ftplib.Error) as error:
        _failure(print, "setup", phase, error)
        print("::error title=FTP temporary probe::Probe failed; no public filename was intentionally changed")
        return 1
    finally:
        if ftp is not None:
            try:
                ftp.quit()
            except Exception as error:
                _failure(print, "setup", "disconnect", error)
    print("::notice title=FTP temporary probe::All isolated samples were cleaned up")
    return 0


if __name__ == "__main__":
    sys.exit(main())
