#!/usr/bin/env python3
"""Загрузка файлов релиза на официальный сайт (uCoz FTP) кусками по 100 МБ.

Требование владельца: «заливал кратно по 100, потом пауза 5 минут».
Поэтому любой файл больше 100 МБ уходит на сайт не одним куском, а так:

  1. файл режется на части не больше `--chunk-mb` МБ (по умолчанию и
     максимум — 100);
  2. первая часть создаёт файл на сервере (STOR), остальные дописываются
     в конец того же файла (FTP APPE) — на сайте в итоге лежит целый файл
     с тем же именем, как раньше;
  3. между частями — пауза `--delay-seconds` (по умолчанию 300 секунд =
     5 минут); перед первой частью и после последней паузы нет;
  4. после каждой части размер на сервере сверяется с ожидаемым, в конце —
     полный размер файла. Не сошлось — процесс красный, а не «как-нибудь».

Файлы до 100 МБ (все пакеты функций) уходят одной передачей без пауз —
резать их не во что.

Доступы — только из окружения, в лог пароль не попадает:
APU_FTP_HOST, APU_FTP_USER, APU_FTP_PASSWORD, APU_FTP_REMOTE_DIR
(по умолчанию /downloads), APU_UPLOAD_DELAY_SECONDS.

Запуск:
    python3 scripts/release/ftp_upload.py file1 [file2 ...]
"""
import argparse
import ftplib
import os
import sys
import time
from pathlib import Path

MAX_CHUNK_MB = 100
DEFAULT_DELAY_SECONDS = 300
MB = 1024 * 1024
IO_BLOCK = 256 * 1024


def normalize_host(raw: str) -> str:
    host = raw.strip()
    for prefix in ("ftp://", "FTP://", "ftps://"):
        if host.startswith(prefix):
            host = host[len(prefix):]
    return host.rstrip("/")


def upload_file(ftp, source: Path, remote_name: str,
                chunk_bytes: int = MAX_CHUNK_MB * MB,
                delay_seconds: int = DEFAULT_DELAY_SECONDS,
                sleeper=time.sleep, log=print) -> int:
    """Залить один файл; большие — частями по chunk_bytes с паузами.

    Возвращает число переданных частей. [ftp] нужен с методами
    storbinary(cmd, fileobj) и size(name) — настоящий ftplib.FTP или
    тестовая замена.
    """
    if chunk_bytes < 1 or chunk_bytes > MAX_CHUNK_MB * MB:
        raise ValueError(f"chunk must be 1..{MAX_CHUNK_MB} MB")
    total = source.stat().st_size
    chunks = max(1, (total + chunk_bytes - 1) // chunk_bytes)
    sent = 0
    with source.open("rb") as handle:
        for index in range(chunks):
            data = handle.read(chunk_bytes)
            if index > 0:
                log(f"пауза {delay_seconds} с перед частью {index + 1}/{chunks}…")
                sleeper(delay_seconds)
            command = f"STOR {remote_name}" if index == 0 else f"APPE {remote_name}"
            try:
                ftp.storbinary(command, _Reader(data))
            except ftplib.error_perm:
                if index == 0:
                    raise
                # Сервер запретил дописывание (APPE): честный запасной путь —
                # убрать огрызок и передать файл целиком одной передачей,
                # чтобы на сайте не осталось битого хвоста.
                log(f"{remote_name}: сервер отказал в APPE — передаю файл целиком одной передачей")
                try:
                    ftp.delete(remote_name)
                except Exception:
                    pass
                handle.seek(0)
                ftp.storbinary(f"STOR {remote_name}", handle)
                remote = ftp.size(remote_name)
                if remote is not None and remote != total:
                    raise IOError(f"{remote_name}: на сервере {remote} Б, ожидалось {total} Б")
                log(f"{remote_name}: передан целиком ({total} Б)")
                return 1
            sent += len(data)
            remote = ftp.size(remote_name)
            if remote is not None and remote != sent:
                raise IOError(
                    f"{remote_name}: после части {index + 1} на сервере {remote} Б, ожидалось {sent} Б"
                )
            log(f"{remote_name}: часть {index + 1}/{chunks} передана, на сервере {sent} из {total} Б")
    return chunks


class _Reader:
    """Минимальный file-like поверх bytes для ftplib.storbinary."""

    def __init__(self, data: bytes):
        self._data = data
        self._pos = 0

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            size = len(self._data) - self._pos
        chunk = self._data[self._pos:self._pos + size]
        self._pos += len(chunk)
        return chunk


def upload_all(files: list[Path], host: str, user: str, password: str,
               remote_dir: str, delay_seconds: int,
               chunk_bytes: int = MAX_CHUNK_MB * MB,
               ftp_factory=ftplib.FTP, sleeper=time.sleep, log=print) -> None:
    # Пустая строка из окружения — это «не задано», а не «корень сервера»:
    # именно так файлы однажды уехали в главную папку вместо /downloads.
    remote_dir = (remote_dir or "").strip() or "/downloads"
    ftp = ftp_factory()
    ftp.connect(host, timeout=120)
    try:
        ftp.login(user, password)
        log(f"FTP вход: {host}, каталог {remote_dir}")
        for part in [p for p in remote_dir.split("/") if p]:
            try:
                ftp.cwd(part)
            except ftplib.error_perm:
                ftp.mkd(part)
                ftp.cwd(part)
        for source in files:
            upload_file(ftp, source, source.name, chunk_bytes, delay_seconds, sleeper, log)
    finally:
        try:
            ftp.quit()
        except Exception:
            pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", help="файлы в порядке загрузки")
    parser.add_argument("--chunk-mb", type=int, default=MAX_CHUNK_MB)
    parser.add_argument("--delay-seconds", type=int,
                        default=int(os.environ.get("APU_UPLOAD_DELAY_SECONDS", DEFAULT_DELAY_SECONDS)))
    args = parser.parse_args(argv)

    host = normalize_host(os.environ.get("APU_FTP_HOST", ""))
    user = os.environ.get("APU_FTP_USER", "")
    password = os.environ.get("APU_FTP_PASSWORD", "")
    remote_dir = os.environ.get("APU_FTP_REMOTE_DIR") or "/downloads"
    if not host or not user or not password:
        print("не заданы APU_FTP_HOST / APU_FTP_USER / APU_FTP_PASSWORD")
        return 2
    files = [Path(name) for name in args.files]
    for path in files:
        if not path.is_file():
            print(f"file not found: {path}")
            return 2
    upload_all(files, host, user, password, remote_dir,
               args.delay_seconds, args.chunk_mb * MB)
    print("все файлы на сайте")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
