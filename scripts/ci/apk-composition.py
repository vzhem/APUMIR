#!/usr/bin/env python3
"""Состав релизного APK: что именно занимает место внутри одного файла.

Зачем: владелец решил вынести тяжёлый функционал в докачиваемые пакеты
(лайт-база + раздел «что докачать» в настройках). Резать наугад нельзя:
сначала нужен замер — сколько весит каждая нативная библиотека по ABI,
сколько dex, ресурсы и assets. Этот скрипт печатает разбор в лог сборки и
короткими ::notice аннотациями, которые видны без скачивания APK.

Запуск: python3 scripts/ci/apk-composition.py <путь-к-apk>
"""
import sys
import zipfile
from collections import defaultdict
from pathlib import Path


def mb(n: int) -> str:
    return f"{n / 1024 / 1024:.1f} MB"


def notice(title: str, message: str) -> None:
    escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title={title}::{escaped}")


def main() -> int:
    apk = Path(sys.argv[1])
    if not apk.is_file():
        print(f"apk not found: {apk}")
        return 2
    per_abi: dict[str, int] = defaultdict(int)
    per_lib: dict[str, int] = defaultdict(int)
    groups: dict[str, int] = defaultdict(int)
    biggest: list[tuple[int, str]] = []
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            size = info.compress_size
            name = info.filename
            biggest.append((size, name))
            if name.startswith("lib/"):
                parts = name.split("/", 2)
                if len(parts) == 3:
                    per_abi[parts[1]] += size
                    per_lib[parts[2]] += size
                groups["lib (native, all ABIs)"] += size
            elif name.endswith(".dex"):
                groups["dex (code)"] += size
            elif name == "resources.arsc":
                groups["resources.arsc"] += size
            elif name.startswith("res/"):
                groups["res/"] += size
            elif name.startswith("assets/"):
                groups["assets/"] += size
            elif name.startswith("kotlin/"):
                groups["kotlin/"] += size
            else:
                groups["other"] += size
    total_file = apk.stat().st_size
    print(f"APK file: {mb(total_file)} ({total_file} bytes)")
    for title, bucket in (("by group", groups), ("per ABI (lib/)", per_abi), ("per native lib", per_lib)):
        print(f"--- {title} ---")
        for key, size in sorted(bucket.items(), key=lambda kv: -kv[1]):
            print(f"{mb(size):>10}  {key}")
    print("--- biggest entries ---")
    for size, name in sorted(biggest, reverse=True)[:25]:
        print(f"{mb(size):>10}  {name}")

    notice("APK composition: total", f"file {mb(total_file)}; " +
           "; ".join(f"{k} {mb(v)}" for k, v in sorted(groups.items(), key=lambda kv: -kv[1])))
    notice("APK composition: per ABI", "; ".join(f"{k} {mb(v)}" for k, v in sorted(per_abi.items())))
    top_libs = sorted(per_lib.items(), key=lambda kv: -kv[1])[:12]
    notice("APK composition: top native libs", "; ".join(f"{k} {mb(v)}" for k, v in top_libs))
    top_entries = sorted(biggest, reverse=True)[:12]
    notice("APK composition: biggest entries", "; ".join(f"{n} {mb(s)}" for s, n in top_entries))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
