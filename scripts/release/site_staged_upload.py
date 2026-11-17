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
import time
import uuid
from pathlib import Path, PurePosixPath

from site_release_files import MAX_METADATA_BYTES, SITE_NAMES, SiteFile, tracked_fingerprint, validate_inputs

# Сокет ждёт данных не дольше этого: залипание на 180 секунд ничего не
# выигрывало, только прятало обрыв (2026-11-17, два прогона подряд упали
# `TimeoutError` на крупном файле). Короче сторож — быстрее повтор.
FTP_TIMEOUT_SECONDS = 120
FTP_BLOCK_BYTES = 256 * 1024
# Повторы — на новой сессии и с уже выложенных файлов: повторная заливка
# многомегабайтного APK только увеличивала шанс снова не успеть.
FTP_MAX_ATTEMPTS = 4
FTP_RETRY_BACKOFF_SECONDS = 10
# Дозаливка одного файла (FTP REST): 78 МБ APK на медленном канале не обязано
# пройти с первого раза, но каждый удачный кусок уже принят сервером.
FTP_STAGE_ATTEMPTS = 4


class CheckedReader:
    """Stream while detecting a local file modified after input validation.

    `start` — сколько байт сервер уже принял в прошлой попытке: ровно столько
    проглатывается и попадает в контрольную сумму, чтобы итоговая сверка
    по-прежнему считалась по всему файлу.
    """
    def __init__(self, source, spec: SiteFile, start: int = 0):
        if start < 0 or start > spec.size:
            raise ValueError("invalid staging offset")
        self.source = source
        self.spec = spec
        self.start = start
        self.size = start
        self.digest = hashlib.sha256()
        if start:
            # Читаем префикс с начала: это и есть продолжение с `start`, а курсор
            # после него уже стоит где нужно.
            remaining = start
            while remaining > 0:
                block = source.read(min(FTP_BLOCK_BYTES, remaining))
                if not block:
                    raise ValueError("staged prefix is longer than the local file")
                self.digest.update(block)
                remaining -= len(block)

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


def staged_size(ftp, name: str) -> int:
    """Сколько байт уже принято во временный файл (0, если его нет)."""
    try:
        size = ftp.size(name)
    except ftplib.error_perm:
        return 0
    return int(size or 0)


def new_stage_name(ftp, spec: SiteFile, token_factory=uuid.uuid4) -> str:
    """Свежее уникальное имя временного файла рядом с публичным."""
    suffix = Path(spec.name).suffix
    if suffix not in {".zip", ".json", ".apk"}:
        raise ValueError("unsafe staging extension")
    token = token_factory().hex
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("invalid staging token")
    name = f"apu-stage-{token}{suffix}"
    # NLST cannot guarantee exclusive creation against an unrelated external FTP
    # writer, so use a fresh UUID4 as well and serialize our jobs. Never
    # overwrite or delete a pre-existing name, even on an error path.
    if name.casefold() in _remote_names(ftp):
        raise FileExistsError("staging name already exists")
    return name


def _remote_names(ftp) -> set[str]:
    # Some FTP servers return paths in NLST rather than bare filenames. Treat
    # case variants as collisions too, including on case-insensitive servers.
    return {PurePosixPath(name).name.casefold() for name in ftp.nlst()}


def stage_bytes(ftp, directory: Path, spec: SiteFile, temp_name: str, log=print,
                attempts: int | None = None) -> None:
    """Дозалить временный файл, продолжая с уже принятого размера (FTP REST).

    Крупный файл на медленном канале не обязан пройти целиком с первого раза.
    `REST` просит сервер продолжить запись с текущего размера временного файла,
    поэтому каждая удачная часть остаётся принятой, а не пропадает. Итоговая
    сверка по-прежнему идёт по всему файлу: `CheckedReader` домешивает в
    контрольную сумму уже принятый префикс.
    """
    attempts = FTP_STAGE_ATTEMPTS if attempts is None else attempts
    offset = staged_size(ftp, temp_name)
    if offset > spec.size:
        raise ValueError("staged file is larger than the local file")
    for attempt in range(1, attempts + 1):
        if offset >= spec.size:
            return
        with (directory / spec.name).open("rb") as source:
            reader = CheckedReader(source, spec, offset)
            log(f"Staging {spec.name} from {offset} of {spec.size} bytes (attempt {attempt})")
            try:
                ftp.storbinary(
                    f"STOR {temp_name}", reader, blocksize=FTP_BLOCK_BYTES, rest=offset
                )
            except (EOFError, OSError, ftplib.Error):
                if attempt == attempts:
                    raise
                # Сколько сервер успел принять — решает, с чего продолжать.
                # Мёртвое соединение здесь не выяснить: отдаём наверх, где
                # сессию пересоздадут и вернутся к этому же временному имени.
                resumed = staged_size(ftp, temp_name)
                if resumed <= offset:
                    if offset == 0:
                        raise
                    # Сервер не умеет REST или отдаёт чужой размер: начать
                    # заново честнее, чем дозаливать поверх неизвестного.
                    log(f"Staging {spec.name} cannot resume; restarting from 0 bytes")
                    try:
                        ftp.delete(temp_name)
                    except (EOFError, OSError, ftplib.Error):
                        raise
                    offset = 0
                    continue
                offset = resumed
                log(f"Staging {spec.name} interrupted; resuming from {offset} bytes")
                continue
            if not reader.matches():
                raise ValueError("local file changed during transfer")
            offset = spec.size


def publish_one(ftp, directory: Path, spec: SiteFile, still_current, log=print,
                token_factory=uuid.uuid4, stage_name: str | None = None) -> None:
    """Stage, size-check and rename one file; keep the destination on failure.

    `stage_name` передаётся внешним повтором: то же имя нужно и на новой сессии,
    иначе дозаливка начнётся с нуля.
    """
    # Restrict both the destination and the extension before constructing FTP
    # commands. The shorter names were tested against uCoz's FTP name rules.
    if spec.name not in SITE_NAMES:
        raise ValueError("unsafe site filename")
    suffix = Path(spec.name).suffix
    if suffix not in {".zip", ".json", ".apk"}:
        raise ValueError("unsafe staging extension")
    if stage_name is None:
        temp_name = new_stage_name(ftp, spec, token_factory)
    else:
        temp_name = stage_name
        if not re.fullmatch(r"apu-stage-[0-9a-f]{32}\.(zip|json|apk)", temp_name):
            raise ValueError("unsafe staging name")
        if not temp_name.endswith(suffix):
            raise ValueError("staging name does not match the file type")
    cleanup = False
    try:
        log(f"Staging {spec.name} ({spec.size} bytes)")
        cleanup = True  # A failed STOR may leave a partial temporary file.
        stage_bytes(ftp, directory, spec, temp_name, log)
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


def publish_with_retry(
    connect,
    directory: Path,
    files: list[SiteFile],
    still_current,
    log=print,
    max_attempts: int = FTP_MAX_ATTEMPTS,
    sleep=time.sleep,
) -> None:
    """Выложить файлы, переживая обрыв соединения.

    `publish_one` безопасен для повтора: публичное имя меняется только после
    совпадения размера, а неудачная попытка оставляет лишь уникальный временный
    файл. Поэтому повтор начинается с РАЗРЫВА, а не с начала: уже выложенные
    файлы не заливаются заново (это и было самой долгой частью).

    `ValueError` (в том числе «Latest сменился») сюда не относится и наружу
    выходит сразу: повторять публикацию устаревшего релиза нельзя.
    """
    # `validate_inputs` supplies packs, their manifest, APK, then latest.json.
    if tuple(spec.name for spec in files) != SITE_NAMES:
        raise ValueError("invalid publication order")
    pending = list(files)
    last_error = None
    for attempt in range(1, max_attempts + 1):
        ftp = None
        try:
            ftp = connect()
            while pending:
                publish_one(ftp, directory, pending[0], still_current, log)
                done = pending.pop(0)
                log(f"Published {done.name}; left to publish: {len(pending)}")
            return
        except ValueError:
            raise
        except (EOFError, OSError, ftplib.Error) as error:
            last_error = error
            log(
                f"FTP attempt {attempt}/{max_attempts} interrupted "
                f"({type(error).__name__}); left to publish: {len(pending)}"
            )
            if attempt == max_attempts:
                break
            sleep(FTP_RETRY_BACKOFF_SECONDS * attempt)
        finally:
            if ftp is not None:
                try:
                    ftp.quit()
                except Exception:
                    # Оборванная сессия уже ничего не публикует.
                    pass
    raise last_error if last_error is not None else OSError("upload interrupted")


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
        def connect():
            ftp = ftplib.FTP()
            ftp.connect(host, timeout=FTP_TIMEOUT_SECONDS)
            ftp.login(user, password)
            ftp.cwd(remote)
            return ftp

        # A failed QUIT does not change an already completed rename.
        publish_with_retry(connect, directory, files, still_current)
    except (OSError, EOFError, ValueError, TypeError, KeyError, ftplib.Error) as error:
        # Do not print the exception text: it can contain a host or credentials.
        print(f"::error::Staged site upload failed: {type(error).__name__}")
        return 1
    print("Site staging and promotion completed; independent byte verification is still required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
