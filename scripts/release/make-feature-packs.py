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


def build_packs(apk_path: Path, out_dir: Path, version: str = "") -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        for pack_id, libs in PACKS.items():
            for abi in ABIS:
                members = []
                for lib in libs:
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
    if len(argv) not in (3, 4):
        print(__doc__)
        return 2
    apk = Path(argv[1])
    if not apk.is_file():
        print(f"apk not found: {apk}")
        return 2
    build_packs(apk, Path(argv[2]), argv[3] if len(argv) == 4 else "")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
