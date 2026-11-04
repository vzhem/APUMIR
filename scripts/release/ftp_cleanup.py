#!/usr/bin/env python3
"""Уборка главной папки FTP после неудачной загрузки не в тот каталог.

Один запуск по ошибке положил файлы релиза в корневую папку FTP вместо
`/downloads` (пустая переменная каталога). Сайт смотрит в `/downloads`,
а в корне остались двойники. Этот скрипт удаляет из корня **только**
известные имена файлов релиза — ничего другого не трогает:

  feature-pack-<пакет>-<abi>.zip (9), feature-packs.json,
  app-release.apk, latest.json, SHA256SUMS.txt

Что нашёл и удалил — печатает в лог. Доступы — из того же окружения,
что у загрузки (APU_FTP_HOST/USER/PASSWORD); пароль в лог не попадает.
"""
import ftplib
import os
import sys

PACK_IDS = ("translate", "voice-text", "face-effects")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
FIXED_NAMES = ("feature-packs.json", "app-release.apk", "latest.json", "SHA256SUMS.txt")


def known_root_junk() -> list[str]:
    names = [f"feature-pack-{pack}-{abi}.zip" for pack in PACK_IDS for abi in ABIS]
    return names + list(FIXED_NAMES)


def cleanup(ftp, log=print) -> list[str]:
    """Удалить из текущего (корневого) каталога известные имена релиза."""
    try:
        present = set(ftp.nlst())
    except ftplib.error_perm:
        present = set()
    removed = []
    for name in known_root_junk():
        if name in present:
            ftp.delete(name)
            removed.append(name)
            log(f"корень FTP: удалён двойник {name}")
    if not removed:
        log("корень FTP: двойников релиза нет")
    return removed


def main() -> int:
    host = os.environ.get("APU_FTP_HOST", "").strip()
    for prefix in ("ftp://", "FTP://", "ftps://"):
        if host.startswith(prefix):
            host = host[len(prefix):]
    host = host.rstrip("/")
    user = os.environ.get("APU_FTP_USER", "")
    password = os.environ.get("APU_FTP_PASSWORD", "")
    if not host or not user or not password:
        print("не заданы APU_FTP_HOST / APU_FTP_USER / APU_FTP_PASSWORD")
        return 2
    ftp = ftplib.FTP()
    ftp.connect(host, timeout=120)
    try:
        ftp.login(user, password)
        cleanup(ftp)
    finally:
        try:
            ftp.quit()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
