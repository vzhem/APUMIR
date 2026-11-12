#!/usr/bin/env python3
"""Check FTP staging/overwrite support using only two unique temporary files.

The probe never modifies an existing release file. It lists the destination to
avoid collisions and attempts to remove its own temporary files on all paths.
A failed cleanup can leave a uniquely named probe file and is reported as an
error. Neither credentials, host nor FTP exception text are printed.
"""
import ftplib
import io
import os
import sys
import uuid
from pathlib import PurePosixPath

from site_staged_upload import FTP_TIMEOUT_SECONDS, normalize_host, remote_directory

OLD = b'{"apumirProbe":"old"}\n'
NEW = b'{"apumirProbe":"new-and-longer"}\n'


def probe(ftp, token_factory=uuid.uuid4, log=print) -> None:
    token = token_factory().hex
    if len(token) != 32 or any(c not in "0123456789abcdef" for c in token):
        raise ValueError("invalid probe token")
    before = {PurePosixPath(name).name for name in ftp.nlst()}
    old_name = f"apumir-probe-{token}-a.json"
    new_name = f"apumir-probe-{token}-b.json"
    if old_name in before or new_name in before:
        raise ValueError("probe filename collision")
    owned = set()
    cleanup_errors = []
    try:
        for name, content in ((old_name, OLD), (new_name, NEW)):
            # STOR can create a partial file before throwing.
            owned.add(name)
            ftp.storbinary(f"STOR {name}", io.BytesIO(content))
        ftp.voidcmd("TYPE I")
        if ftp.size(old_name) != len(OLD) or ftp.size(new_name) != len(NEW):
            raise ValueError("FTP SIZE unavailable or incorrect")
        # This is the only overwrite: both names were created by this probe.
        ftp.rename(new_name, old_name)
        owned.remove(new_name)
        if ftp.size(old_name) != len(NEW):
            raise ValueError("FTP rename did not replace the temporary file")
        received = bytearray()
        def collect(block):
            if len(received) + len(block) > 1024:
                raise ValueError("probe response too large")
            received.extend(block)
        try:
            ftp.retrbinary(f"RETR {old_name}", collect)
        except ftplib.error_perm:
            log("FTP rename overwrite and SIZE succeeded; RETR is unavailable")
        else:
            if bytes(received) != NEW:
                raise ValueError("FTP renamed bytes differ")
            log("FTP rename overwrite, SIZE and RETR succeeded on temporary files")
    finally:
        for name in sorted(owned):
            try:
                ftp.delete(name)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
                log(f"Probe cleanup failed: {type(error).__name__}")
        if cleanup_errors:
            raise OSError("one or more temporary probe files could not be removed")


def main() -> int:
    try:
        host = normalize_host(os.environ.get("APU_FTP_HOST", ""))
        user = os.environ.get("APU_FTP_USER", "")
        password = os.environ.get("APU_FTP_PASSWORD", "")
        if not user or not password or any(c in user + password for c in "\r\n\x00"):
            raise ValueError("invalid FTP credentials")
        remote = remote_directory(os.environ.get("APU_FTP_REMOTE_DIR", ""))
        ftp = ftplib.FTP()
        try:
            ftp.connect(host, timeout=FTP_TIMEOUT_SECONDS)
            ftp.login(user, password)
            ftp.cwd(remote)
            probe(ftp)
        finally:
            try:
                ftp.quit()
            except Exception as error:
                print(f"FTP disconnect failed: {type(error).__name__}")
    except (OSError, EOFError, ValueError, TypeError, ftplib.Error) as error:
        print(f"::error title=FTP staging probe failed::Temporary-file probe failed: {type(error).__name__}; cleanup was attempted")
        return 1
    print("::notice title=FTP staging probe::Temporary-file rename overwrite and SIZE are supported; probe files were removed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
