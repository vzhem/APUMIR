#!/usr/bin/env python3
"""Собрать докачиваемые пакеты функций из готового релизного APK.

Владелец решил: приложение полноценное, но тяжёлые нативные библиотеки
уезжают в пакеты (docs/FEATURE_PACKS.md). Этот скрипт — сборочная половина:
он достаёт из APK нативные библиотеки по каталогу пакетов и кладёт рядом
9 zip-файлов (3 пакета × 3 архитектуры) + манифест `feature-packs.json`,
который читает приложение (официальный сайт, `apu.clan.su/downloads`).

Каталог ниже обязан совпадать с `FeaturePackCatalog.kt` в приложении —
совпадение сторожит `scripts/ci/check-site-publishing.py`, а не память.

Запуск:
    python3 scripts/release/make-feature-packs.py <app-release.apk> <out-dir> [version]

Zip содержит `.so` в корне: приложение распаковывает пакет как есть
в `filesDir/feature_packs/`.
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

# id пакета -> нативные библиотеки (синхронно с FeaturePackCatalog.kt).
PACKS: dict[str, list[str]] = {
    "translate": ["libxeno_native.so", "libtranslate_jni.so", "liblanguage_id_l2c_jni.so"],
    "voice-text": ["libvosk.so"],
    "face-effects": ["libface_detector_v2_jni.so"],
}

ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pack_file_name(pack_id: str, abi: str) -> str:
    return f"feature-pack-{pack_id}-{abi}.zip"


def build_packs(apk_path: Path, out_dir: Path, version: str = "",
                native_libs_dir: Path | None = None) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        for pack_id, libs in PACKS.items():
            for abi in ABIS:
                members = []
                for lib in libs:
                    # Лёгкая база: в APK этих библиотек больше нет — берём из
                    # каталога собранных нативных библиотек Gradle
                    # (<dir>/<abi>/<lib> или <dir>/lib/<abi>/<lib>).
                    disk_file = None
                    if native_libs_dir is not None:
                        for candidate in (native_libs_dir / abi / lib,
                                          native_libs_dir / "lib" / abi / lib):
                            if candidate.is_file():
                                disk_file = candidate
                                break
                    if disk_file is not None:
                        members.append((lib, disk_file.read_bytes()))
                        continue
                    entry = f"lib/{abi}/{lib}"
                    if entry not in names:
                        raise SystemExit(f"в APK нет {entry}: каталог пакетов разошёлся со сборкой")
                    members.append((lib, apk.read(entry)))
                file_name = pack_file_name(pack_id, abi)
                target = out_dir / file_name
                with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
                    for lib, data in members:
                        z.writestr(lib, data)
                blob = target.read_bytes()
                entries.append(
                    {
                        "id": pack_id,
                        "abi": abi,
                        "file": file_name,
                        "size": len(blob),
                        "sha256": sha256_bytes(blob),
                    }
                )
                print(f"{file_name}: {len(blob)} B sha256={entries[-1]['sha256'][:16]}…")
    manifest = {"version": version, "packs": entries}
    (out_dir / "feature-packs.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"feature-packs.json: {len(entries)} пакетов, version={version or '-'}")
    return manifest


def main(argv: list[str]) -> int:
    raw = argv[1:]
    args: list[str] = []
    native_libs_dir: Path | None = None
    index = 0
    while index < len(raw):
        item = raw[index]
        if item.startswith("--native-libs-dir="):
            native_libs_dir = Path(item.split("=", 1)[1])
        elif item == "--native-libs-dir" and index + 1 < len(raw):
            index += 1
            native_libs_dir = Path(raw[index])
        else:
            args.append(item)
        index += 1
    if len(args) not in (2, 3):
        print(__doc__)
        return 2
    apk = Path(args[0])
    if not apk.is_file():
        print(f"apk not found: {apk}")
        return 2
    build_packs(apk, Path(args[1]), args[2] if len(args) == 3 else "", native_libs_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
