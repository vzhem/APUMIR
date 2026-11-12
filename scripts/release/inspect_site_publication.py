#!/usr/bin/env python3
"""Read-only, bounded diagnostics for the latest release's site publication.

Runs on a GitHub-hosted runner: no FTP credentials, writes, or response bodies
are printed. Only known filenames, validated version numbers and digests are
reported. A streamed APK hash is a single HTTP snapshot, not a guarantee about
concurrent uploads or any feature pack. The job-log API may not expose a
running job's log; that is not an indication that an upload completed or failed.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

SITE = "https://apu.clan.su/downloads"
MAX_METADATA = 1024 * 1024
MAX_LOG_TAIL = 512 * 1024
VERSION = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
PACKS = ("translate", "voice-text", "face-effects")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
SAFE_NAMES = {f"feature-pack-{pack}-{abi}.zip" for pack in PACKS for abi in ABIS}
SAFE_NAMES.update({"feature-packs.json", "app-release.apk", "latest.json"})


def parse_latest(data: bytes) -> tuple[str, str]:
    parsed = json.loads(data)
    version, digest = parsed["version"], parsed["sha256"]
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError("invalid version")
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ValueError("invalid SHA-256")
    return version, digest


def last_uploaded_name(log: bytes) -> str | None:
    # The log contains the printed shell script as well as its output. Match
    # only a real timestamped runner line, then whitelist the final basename.
    pattern = re.compile(rb"^\d{4}-\d\d-\d\dT[^ ]+ FTP -> ([^\s]+) \([0-9]+ ")
    last = None
    for line in log.splitlines():
        match = pattern.match(line)
        if match:
            try:
                name = match.group(1).decode("ascii").rsplit("/", 1)[-1]
            except UnicodeDecodeError:
                continue
            if name in SAFE_NAMES:
                last = name
    return last


class SiteRedirectsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlparse(newurl)
        if target.scheme != "https" or target.netloc.lower() != "apu.clan.su":
            raise ValueError("unexpected site redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_site(name: str):
    if name not in {"latest.json", "feature-packs.json", "app-release.apk"}:
        raise ValueError("unexpected site file")
    opener = urllib.request.build_opener(SiteRedirectsOnly())
    request = urllib.request.Request(f"{SITE}/{name}", headers={"Cache-Control": "no-cache"})
    return opener.open(request, timeout=20)


def read_site(name: str) -> bytes:
    if name not in {"latest.json", "feature-packs.json"}:
        raise ValueError("unexpected metadata file")
    with open_site(name) as response:
        data = response.read(MAX_METADATA + 1)
    if len(data) > MAX_METADATA:
        raise ValueError("metadata too large")
    return data


def hash_site_apk(maximum: int) -> tuple[int, str]:
    if not 0 < maximum <= 512 * 1024 * 1024:
        raise ValueError("invalid APK size limit")
    digest, size = hashlib.sha256(), 0
    with open_site("app-release.apk") as response:
        while True:
            block = response.read(min(256 * 1024, maximum - size + 1))
            if not block:
                return size, digest.hexdigest()
            size += len(block)
            if size > maximum:
                raise ValueError("site APK exceeds limit")
            digest.update(block)


def gh_json(endpoint: str):
    # GitHub API metadata is bounded; errors are never echoed because an HTTP
    # redirect or client error can contain temporary signed URLs.
    process = subprocess.run(["gh", "api", endpoint], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, timeout=30, check=True)
    if len(process.stdout) > MAX_METADATA:
        raise ValueError("GitHub metadata too large")
    return json.loads(process.stdout)


def job_log_tail(endpoint: str) -> bytes | None:
    # Avoid retaining the entire log in memory and never output it verbatim.
    api = subprocess.Popen(["gh", "api", endpoint], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        assert api.stdout is not None
        tail = subprocess.run(["tail", "-c", str(MAX_LOG_TAIL)], stdin=api.stdout,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=True)
        api.stdout.close()
        if api.wait(timeout=5) != 0:
            return None
        return tail.stdout
    except (OSError, subprocess.SubprocessError):
        return None
    finally:
        if api.poll() is None:
            api.kill()
        api.wait()
        if api.stdout is not None:
            api.stdout.close()


def report(message: str) -> None:
    # Only static text and validated values reach this function. Annotations
    # make the outcome visible without exposing the underlying runner log.
    print(f"::notice title=Site publication inspection::{message}")


def main() -> int:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+", repo) or repo.rsplit("/", 1)[1] in {".", ".."}:
        report("Missing or invalid GitHub repository")
        return 1
    try:
        release = gh_json(f"repos/{repo}/releases/latest")
        tag = release["tag_name"]
        assets = {a["name"]: a for a in release["assets"]}
        apk_asset = assets["app-release.apk"]
        apk = apk_asset["digest"].removeprefix("sha256:")
        apk_size = apk_asset["size"]
        manifest = assets["feature-packs.json"]["digest"].removeprefix("sha256:")
        if (not isinstance(tag, str) or not VERSION.fullmatch(tag) or not SHA256.fullmatch(apk) or
                not SHA256.fullmatch(manifest) or not isinstance(apk_size, int) or
                not 0 < apk_size <= 512 * 1024 * 1024):
            raise ValueError("invalid release metadata")
    except (AttributeError, KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        report("Latest release metadata unavailable or invalid")
        return 1
    report(f"GitHub Latest: {tag}; APK SHA-256 {apk}")
    metadata_apk = None
    for name in ("latest.json", "feature-packs.json"):
        try:
            data = read_site(name)
            if name == "latest.json":
                version, metadata_apk = parse_latest(data)
                report(f"Site latest.json: {version}; APK SHA-256 {metadata_apk}; matches Latest: {version == tag and metadata_apk == apk}")
            else:
                site_hash = hashlib.sha256(data).hexdigest()
                report(f"Site feature-packs.json SHA-256 matches release: {site_hash == manifest}")
        except (ValueError, KeyError, TypeError, json.JSONDecodeError, urllib.error.URLError, OSError):
            report(f"Site {name}: unavailable or invalid")
    try:
        # The old release may have been slightly larger than the new one.
        limit = min(512 * 1024 * 1024, max(apk_size, 100 * 1024 * 1024))
        site_size, site_hash = hash_site_apk(limit)
        report(f"Site APK snapshot: {site_size} bytes; matches Latest: {site_size == apk_size and site_hash == apk}; "
               f"matches site metadata: {site_hash == metadata_apk if metadata_apk else 'unknown'}")
    except (ValueError, urllib.error.URLError, OSError):
        report("Site APK snapshot: unavailable or exceeds limit")
    try:
        runs = gh_json(f"repos/{repo}/actions/workflows/site-publish.yml/runs?per_page=30")
        candidates = [run for run in runs["workflow_runs"] if run["head_branch"] == tag and run["event"] == "release"]
        if not candidates:
            report("No release-triggered site publication found for Latest")
            return 0
        run = candidates[0]
        run_id = int(run["id"])
        status = run["status"] if run["status"] in {"queued", "in_progress", "completed", "waiting", "pending"} else "unknown"
        report(f"Site publication run {run_id}: {status}")
        jobs = gh_json(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
        for job in jobs["jobs"]:
            if job["name"] != "Upload release files to apu.clan.su":
                continue
            step = next((s for s in job["steps"] if s["name"] == "Upload to apu.clan.su via FTP"), None)
            if not step:
                continue
            if step["status"] == "in_progress":
                report("FTP upload step is still in progress; this does not establish progress or success")
            elif step["status"] == "completed":
                conclusion = step.get("conclusion")
                if conclusion not in {"success", "failure", "cancelled", "skipped", "timed_out"}:
                    conclusion = "unknown"
                report(f"FTP upload step completed: {conclusion}")
            log = job_log_tail(f"repos/{repo}/actions/jobs/{int(job['id'])}/logs")
            name = last_uploaded_name(log) if log is not None else None
            report(f"Last observed FTP file started: {name}" if name else "Job log/progress not available")
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        report("Site publication run details unavailable")
    return 0


if __name__ == "__main__":
    sys.exit(main())
