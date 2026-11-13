#!/usr/bin/env python3
"""Validate the exact small release files before any FTP mutation.

Only the universal APK, nine feature packs, their manifest and latest.json are
published on the site. Older releases without feature packs may have those
packs generated from their already verified APK by the publication workflow.
"""
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

MAX_TRANSFER_BYTES = 100 * 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
PACKS = ("translate", "voice-text", "face-effects")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
PACK_NAMES = tuple(f"feature-pack-{pack}-{abi}.zip" for pack in PACKS for abi in ABIS)
SITE_NAMES = (*PACK_NAMES, "feature-packs.json", "app-release.apk", "latest.json")
VERSION_RE = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")


@dataclass(frozen=True)
class SiteFile:
    name: str
    size: int
    digest: str


def _safe_file(directory: Path, name: str) -> Path:
    if name not in (*SITE_NAMES, "SHA256SUMS.txt"):
        raise ValueError("unexpected site filename")
    path = directory / name
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"missing or unsafe file: {name}")
    return path


def _read_metadata(directory: Path, name: str) -> bytes:
    path = _safe_file(directory, name)
    with path.open("rb") as source:
        data = source.read(MAX_METADATA_BYTES + 1)
    if len(data) > MAX_METADATA_BYTES:
        raise ValueError(f"metadata too large: {name}")
    return data


def _file_digest(directory: Path, name: str, size: int, digest: str) -> SiteFile:
    if type(size) is not int or not 0 < size <= MAX_TRANSFER_BYTES:
        raise ValueError(f"invalid file size: {name}")
    if not isinstance(digest, str) or not SHA_RE.fullmatch(digest):
        raise ValueError(f"invalid file digest: {name}")
    path = _safe_file(directory, name)
    result = hashlib.sha256()
    count = 0
    with path.open("rb") as source:
        while True:
            block = source.read(min(256 * 1024, size - count + 1))
            if not block:
                break
            count += len(block)
            if count > size:
                raise ValueError(f"size mismatch: {name}")
            result.update(block)
    if count != size or result.hexdigest() != digest:
        raise ValueError(f"size or digest mismatch: {name}")
    return SiteFile(name, size, digest)


def release_assets(release: dict, tag: str) -> dict[str, dict]:
    if not isinstance(tag, str) or not VERSION_RE.fullmatch(tag):
        raise ValueError("invalid release tag")
    if release.get("tag_name") != tag or release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError("release is not the requested published stable version")
    if type(release.get("id")) is not int or release["id"] <= 0:
        raise ValueError("invalid release identifier")
    entries = release.get("assets")
    if not isinstance(entries, list):
        raise ValueError("invalid release asset list")
    assets = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("name"), str):
            raise ValueError("invalid release asset")
        name = entry["name"]
        if name in assets:
            raise ValueError("duplicate release asset")
        assets[name] = entry
    return assets


def _asset_spec(assets: dict, name: str) -> tuple[int, str]:
    asset = assets.get(name)
    if not isinstance(asset, dict) or asset.get("state") != "uploaded":
        raise ValueError(f"missing release asset: {name}")
    digest = asset.get("digest")
    if not isinstance(digest, str) or not digest.startswith("sha256:"):
        raise ValueError(f"missing release digest: {name}")
    return asset.get("size"), digest.removeprefix("sha256:")


def validate_inputs(directory: Path, release: dict, tag: str) -> list[SiteFile]:
    """Return verified files in publication order; reject partial/mixed inputs."""
    assets = release_assets(release, tag)
    apk = _file_digest(directory, "app-release.apk", *_asset_spec(assets, "app-release.apk"))
    sums = _file_digest(directory, "SHA256SUMS.txt", *_asset_spec(assets, "SHA256SUMS.txt"))
    lines = _read_metadata(directory, sums.name).decode("utf-8").splitlines()
    apk_lines = [line for line in lines if line.endswith("  app-release.apk")]
    if apk_lines != [f"{apk.digest}  app-release.apk"]:
        raise ValueError("APK checksum asset mismatch")

    manifest_bytes = _read_metadata(directory, "feature-packs.json")
    try:
        manifest = json.loads(manifest_bytes)
    except (UnicodeError, ValueError) as error:
        raise ValueError("invalid feature manifest") from error
    if not isinstance(manifest, dict) or manifest.get("version") != tag or not isinstance(manifest.get("packs"), list):
        raise ValueError("feature manifest has wrong version or shape")
    release_pack_names = set(PACK_NAMES) | {"feature-packs.json"}
    present = release_pack_names.intersection(assets)
    if present and present != release_pack_names:
        raise ValueError("partial feature-pack release assets")
    if present:
        _file_digest(directory, "feature-packs.json", *_asset_spec(assets, "feature-packs.json"))
    manifest_spec = _file_digest(directory, "feature-packs.json", len(manifest_bytes), hashlib.sha256(manifest_bytes).hexdigest())

    files = {}
    for pack in manifest["packs"]:
        if not isinstance(pack, dict):
            raise ValueError("invalid feature pack entry")
        pair = (pack.get("id"), pack.get("abi"))
        if pair[0] not in PACKS or pair[1] not in ABIS:
            raise ValueError("unexpected feature pack entry")
        name = f"feature-pack-{pair[0]}-{pair[1]}.zip"
        if pack.get("file") != name or name in files:
            raise ValueError("duplicate or invalid feature pack filename")
        spec = _file_digest(directory, name, pack.get("size"), pack.get("sha256"))
        if present and _asset_spec(assets, name) != (spec.size, spec.digest):
            raise ValueError(f"release asset mismatch: {name}")
        files[name] = spec
    if set(files) != set(PACK_NAMES):
        raise ValueError("missing feature pack")

    latest_bytes = _read_metadata(directory, "latest.json")
    try:
        latest = json.loads(latest_bytes)
    except (UnicodeError, ValueError) as error:
        raise ValueError("invalid site metadata") from error
    if not isinstance(latest, dict) or latest.get("version") != tag or latest.get("sha256") != apk.digest:
        raise ValueError("site metadata does not match the release APK")
    latest_spec = _file_digest(directory, "latest.json", len(latest_bytes), hashlib.sha256(latest_bytes).hexdigest())
    return [*(files[name] for name in PACK_NAMES), manifest_spec, apk, latest_spec]


def tracked_fingerprint(release: dict, tag: str) -> tuple:
    """Detect Latest or an already verified release asset changing mid-upload."""
    assets = release_assets(release, tag)
    names = ("app-release.apk", "SHA256SUMS.txt", "feature-packs.json", *PACK_NAMES)
    return (release.get("id"), tuple((name, assets[name].get("state"), assets[name].get("size"),
                                     assets[name].get("digest")) if name in assets else (name, None)
                                    for name in names))
