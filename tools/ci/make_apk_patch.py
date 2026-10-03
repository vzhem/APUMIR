#!/usr/bin/env python3
"""
Генератор компактного дифф-патча обновления APU (формат APUBSP1).

Задача владельца 2026-09-23: «при скачивании обновление должно качаться
минимального размера». Вместо полного APK (~40 МБ) телефон качает патч:
новый APK собирается из одинаковых 4 КБ блоков прежнего файла + сжатых
новых блоков. Формат байт-в-байт восстанавливает новый APK (финальная
проверка - sha256 релиза), поэтому подпись остаётся верной.

Формат (все числа big-endian):
  magic  "APUBSP1" (7 байт)
  u8     blockSizeBits (12 = 4096)
  u64    newFileSize
  u32    oldBlockCount
  u32    newBlockCount
  oldBlockCount * 16 байт  - усечённые sha256 блоков СТАРОГО файла
  newBlockCount * 5 байт   - дескрипторы: u8 kind (1=REF, 2=RAW) + u32
                             (REF: индекс блока старого файла;
                              RAW: длина deflate-потока блока)
  затем - конкатенация raw-deflate (wbits=-15) блоков RAW

Применение - android-app .../data/update/ApkDiffPatch.kt.
"""
import hashlib
import os
import struct
import sys
import zlib

BLOCK_BITS = 12
BLOCK_SIZE = 1 << BLOCK_BITS
DIGEST_LEN = 16
MAGIC = b"APUBSP1"


def blocks(data: bytes):
    for i in range(0, len(data), BLOCK_SIZE):
        yield data[i:i + BLOCK_SIZE]


def digest(block: bytes) -> bytes:
    return hashlib.sha256(block).digest()[:DIGEST_LEN]


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: make_apk_patch.py <old.apk> <new.apk> <out.patch>", file=sys.stderr)
        return 2
    old_path, new_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(old_path, "rb") as f:
        old = f.read()
    with open(new_path, "rb") as f:
        new = f.read()

    digest_map = {}
    old_block_count = 0
    old_digests = bytearray()
    for idx, block in enumerate(blocks(old)):
        d = digest(block)
        digest_map.setdefault(d, idx)
        old_digests += d
        old_block_count += 1

    descriptors = bytearray()
    raw = bytearray()
    new_block_count = 0
    ref_count = 0
    raw_count = 0
    for block in blocks(new):
        d = digest(block)
        idx = digest_map.get(d)
        if idx is not None:
            descriptors += struct.pack(">B I", 1, idx)
            ref_count += 1
        else:
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            packed = compressor.compress(block) + compressor.flush()
            descriptors += struct.pack(">B I", 2, len(packed))
            raw += packed
            raw_count += 1
        new_block_count += 1

    header = MAGIC + struct.pack(">B Q I I", BLOCK_BITS, len(new), old_block_count, new_block_count)
    tmp = out_path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(header)
        f.write(old_digests)
        f.write(descriptors)
        f.write(raw)
    os.replace(tmp, out_path)

    old_mb = len(old) / (1024.0 * 1024.0)
    new_mb = len(new) / (1024.0 * 1024.0)
    patch_kb = os.path.getsize(out_path) / 1024.0
    print(
        f"APUBSP1: old {old_mb:.1f} MB, new {new_mb:.1f} MB -> "
        f"patch {patch_kb:.0f} KB ({100.0 * patch_kb / 1024.0 / new_mb:.0f}% of new); "
        f"blocks: {ref_count} reused, {raw_count} raw"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
