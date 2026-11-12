#!/usr/bin/env python3
"""Publish verified site files via FTP staging and same-directory rename.

The old public filename is never opened for a write. A failed, interrupted, or
wrong-size upload leaves only its uniquely named staging file; the script tries
to remove that file but never deletes the existing destination. FTP RNTO and
SIZE must be supported by the site server. Several files cannot be swapped as
one transaction, so clients must still verify their expected checksums.
"""
import ftplib
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path, PurePosixPath

from site_release_files import MAX_METADATA_BYTES, SITE_NAMES, SiteFile, tracked_fingerprint, validate_inputs

FTP_TIMEOUT_SECONDS = 180
FTP_BLOCK_BYTES = 256 * 1024


class CheckedReader:
    """Stream while detecting a local file modified after input validation."""
    def __init__(self, source, spec: SiteFile):
        self.source = source
        self.spec = spec
        self.size = 0
        self.digest = hashlib.sha256()

    def read(self, size=-1):
        # ftplib calls with a fixed block size; do not permit an unbounded read.
        if size < 0 or size > FTP_BLOCK_BYTES:
            size = FTP_BLOCK_BYTES
        block = self.source.read(size)
        self.size += len(block)
        if self.size > self.spec.size:
            raise ValueError("local file grew during transfer")
        self.digest.update(block)
        return block

    def matches(self) -> bool:
        return self.size == self.spec.size and self.digest.hexdigest() == self.spec.digest


def remote_directory(raw: str) -> str:
    remote = (raw or "").strip() or "/downloads"
    if not re.fullmatch(r"/(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*)(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*", remote):
        raise ValueError("unsafe FTP directory")
    return remote


def normalize_host(raw: str) -> str:
    host = raw.strip()
    for prefix in ("ftp://", "FTP://"):
        if host.startswith(prefix):
            host = host[len(prefix):]
    host = host.rstrip("/")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host):
        raise ValueError("invalid FTP host")
    return host


def _remote_names(ftp) -> set[str]:
    # Some FTP servers return paths in NLST rather than bare filenames. Treat
    # case variants as collisions too, including on case-insensitive servers.
    return {PurePosixPath(name).name.casefold() for name in ftp.nlst()}


def publish_one(ftp, directory: Path, spec: SiteFile, still_current, log=print, token_factory=uuid.uuid4) -> None:
    """Stage, size-check and rename one file; keep the destination on failure."""
    # Restrict both the destination and the extension before constructing FTP
    # commands. The shorter names were tested against uCoz's FTP name rules.
    if spec.name not in SITE_NAMES:
        raise ValueError("unsafe site filename")
    suffix = Path(spec.name).suffix
    if suffix not in {".zip", ".json", ".apk"}:
        raise ValueError("unsafe staging extension")
    token = token_factory().hex
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("invalid staging token")
    temp_name = f"apu-stage-{token}{suffix}"
    # NLST cannot guarantee exclusive creation against an unrelated external
    # FTP writer, so use a fresh UUID4 as well and serialize our jobs.
    # Never overwrite or delete a pre-existing name, even on an error path.
    if temp_name.casefold() in _remote_names(ftp):
        raise FileExistsError("staging name already exists")
    cleanup = False
    try:
        with (directory / spec.name).open("rb") as source:
            reader = CheckedReader(source, spec)
            log(f"Staging {spec.name} ({spec.size} bytes)")
            cleanup = True  # A failed STOR may leave a partial temporary file.
            ftp.storbinary(f"STOR {temp_name}", reader, blocksize=FTP_BLOCK_BYTES)
            if not reader.matches():
                raise ValueError("local file changed during transfer")
        ftp.voidcmd("TYPE I")
        remote_size = ftp.size(temp_name)
        if remote_size != spec.size:
            raise ValueError("staged FTP size mismatch or unavailable")
        if not still_current():
            raise ValueError("Latest release or verified assets changed during upload")
        # Never delete the old destination to make a server accept RNTO. If it
        # cannot replace an existing file, fail without exposing partial bytes.
        ftp.rename(temp_name, spec.name)
        cleanup = False
        log(f"Promoted {spec.name}")
    finally:
        if cleanup:
            try:
                if temp_name.casefold() in _remote_names(ftp):
                    ftp.delete(temp_name)
                    if temp_name.casefold() in _remote_names(ftp):
                        raise OSError("staging file remains after cleanup")
            except Exception as error:
                # An interrupted connection can leave a uniquely named orphan;
                # this is visible but is not the public release filename.
                log(f"Staging cleanup failed: {type(error).__name__}")


def publish_all(ftp, directory: Path, files: list[SiteFile], still_current, log=print) -> None:
    # `validate_inputs` supplies packs, their manifest, APK, then latest.json.
    if tuple(spec.name for spec in files) != SITE_NAMES:
        raise ValueError("invalid publication order")
    for spec in files:
        publish_one(ftp, directory, spec, still_current, log)


def repository_is_safe(repository: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+", repository)) and repository.rsplit("/", 1)[-1] not in {".", ".."}


def current_release_matches(repository: str, tag: str, expected: tuple) -> bool:
    if not repository_is_safe(repository):
        return False
    try:
        process = subprocess.run(["gh", "api", f"repos/{repository}/releases/latest"],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 check=True, timeout=30)
        if len(process.stdout) > MAX_METADATA_BYTES:
            return False
        release = json.loads(process.stdout)
        return tracked_fingerprint(release, tag) == expected
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        return False


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    tag = os.environ.get("APU_RELEASE_TAG", "")
    if not repository_is_safe(repository):
        print("::error::Invalid GitHub repository")
        return 1
    try:
        host = normalize_host(os.environ.get("APU_FTP_HOST", ""))
        user = os.environ.get("APU_FTP_USER", "")
        password = os.environ.get("APU_FTP_PASSWORD", "")
        if not user or not password or any(c in user + password for c in "\r\n\x00"):
            raise ValueError("missing or invalid FTP credentials")
        remote = remote_directory(os.environ.get("APU_FTP_REMOTE_DIR", ""))
        directory = Path("site-files")
        with Path("site-release.json").open("rb") as source:
            metadata = source.read(MAX_METADATA_BYTES + 1)
        if len(metadata) > MAX_METADATA_BYTES:
            raise ValueError("release metadata too large")
        release = json.loads(metadata)
        files = validate_inputs(directory, release, tag)
        expected = tracked_fingerprint(release, tag)
        still_current = lambda: current_release_matches(repository, tag, expected)
        if not still_current():
            raise ValueError("release is no longer stable Latest")
        ftp = ftplib.FTP()
        try:
            ftp.connect(host, timeout=FTP_TIMEOUT_SECONDS)
            ftp.login(user, password)
            ftp.cwd(remote)
            publish_all(ftp, directory, files, still_current)
        finally:
            try:
                ftp.quit()
            except Exception as error:
                # A failed QUIT does not change an already completed rename.
                print(f"FTP disconnect failed: {type(error).__name__}")
    except (OSError, EOFError, ValueError, TypeError, KeyError, ftplib.Error) as error:
        # Do not print the exception text: it can contain a host or credentials.
        print(f"::error::Staged site upload failed: {type(error).__name__}")
        return 1
    print("Site staging and promotion completed; independent byte verification is still required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
