#!/usr/bin/env python3
"""
Применитель дифф-патча APUBSP1 - эталон для проверок.

Зеркалит то, что делает телефон (android-app .../data/update/ApkDiffPatch.kt),
и используется в scripts/ci/ci-core-check.sh для кросс-проверки: патч,
сгенерированный PowerShell-версией (tools/ci/make_update_patch.ps1, им
пользуется владелец), обязан накатываться этим эталоном байт-в-байт.

usage: apply_apk_patch.py <patch.bspatch> <old.file> <out.file>
"""
import hashlib
import sys
import zlib


def u32(p: bytes, o: int) -> int:
    return int.from_bytes(p[o:o + 4], "big")


def u64(p: bytes, o: int) -> int:
    return int.from_bytes(p[o:o + 8], "big")


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: apply_apk_patch.py <patch> <old> <out>", file=sys.stderr)
        return 2
    patch_path, old_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    p = open(patch_path, "rb").read()
    old = open(old_path, "rb").read()

    if p[:7] != b"APUBSP1":
        print("bad magic", file=sys.stderr)
        return 1
    bits = p[7]
    bs = 1 << bits
    size = u64(p, 8)
    old_count = u32(p, 16)
    new_count = u32(p, 20)
    if bits != 12:
        print(f"unexpected block bits {bits}", file=sys.stderr)
        return 1

    off = 24
    digests = {}
    for i in range(old_count):
        digests[p[off + 16 * i: off + 16 * i + 16]] = i
    off += 16 * old_count

    # Как телефон: убедиться, что старый файл действительно тот.
    for i in range(old_count):
        o = i * bs
        if o >= len(old):
            print("old file shorter than patch expects", file=sys.stderr)
            return 1
        d = hashlib.sha256(old[o:o + min(bs, len(old) - o)]).digest()[:16]
        if d not in digests:
            print(f"old block {i} does not match patch", file=sys.stderr)
            return 1

    desc = []
    for i in range(new_count):
        desc.append((p[off], u32(p, off + 1)))
        off += 5

    out = bytearray()
    rpos = off
    for i, (kind, val) in enumerate(desc):
        expected = min(bs, size - len(out))
        if expected <= 0:
            print("patch longer than declared size", file=sys.stderr)
            return 1
        if kind == 1:
            o = val * bs
            if o >= len(old):
                print(f"REF beyond old file (block {i})", file=sys.stderr)
                return 1
            seg = old[o:o + expected]
            if len(seg) != expected:
                print(f"REF block {i} truncated", file=sys.stderr)
                return 1
        elif kind == 2:
            seg = zlib.decompress(p[rpos:rpos + val], -15)
            rpos += val
            if len(seg) != expected:
                print(f"RAW block {i} wrong size", file=sys.stderr)
                return 1
        else:
            print(f"unknown kind {kind}", file=sys.stderr)
            return 1
        out += seg
    if len(out) != size:
        print(f"size mismatch {len(out)} != {size}", file=sys.stderr)
        return 1

    open(out_path, "wb").write(out)
    print("REBUILT ok:", len(out), "bytes, sha256", hashlib.sha256(out).hexdigest())
    return 0


if __name__ == "__main__":
    sys.exit(main())
