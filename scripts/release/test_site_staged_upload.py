#!/usr/bin/env python3
"""Offline tests for validating and safely staging site release files."""
import copy
import ftplib
import hashlib
import io
import json
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import site_release_files as inputs
import site_staged_upload as upload
import site_verify_http as http_verify


def sha(data):
    return hashlib.sha256(data).hexdigest()


def asset(name, data):
    return {"name": name, "state": "uploaded", "size": len(data), "digest": "sha256:" + sha(data)}


def fixture(directory, include_packs=True):
    tag = "v11.74.508"
    apk = b"signed universal APK fixture"
    sums = f"{sha(apk)}  app-release.apk\n{'0' * 64}  app-full.apk\n".encode()
    (directory / "app-release.apk").write_bytes(apk)
    (directory / "SHA256SUMS.txt").write_bytes(sums)
    packs = []
    assets = [asset("app-release.apk", apk), asset("SHA256SUMS.txt", sums)]
    for pack in inputs.PACKS:
        for abi in inputs.ABIS:
            name = f"feature-pack-{pack}-{abi}.zip"
            content = f"fixture {pack} {abi}".encode()
            (directory / name).write_bytes(content)
            packs.append({"id": pack, "abi": abi, "file": name, "size": len(content), "sha256": sha(content)})
            if include_packs:
                assets.append(asset(name, content))
    manifest = json.dumps({"version": tag, "packs": packs}).encode()
    (directory / "feature-packs.json").write_bytes(manifest)
    if include_packs:
        assets.append(asset("feature-packs.json", manifest))
    (directory / "latest.json").write_text(json.dumps({"version": tag, "sha256": sha(apk)}))
    release = {"id": 17, "tag_name": tag, "draft": False, "prerelease": False, "assets": assets}
    return tag, release


class SiteInputsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.tag, self.release = fixture(self.directory)

    def validate(self):
        return inputs.validate_inputs(self.directory, self.release, self.tag)

    def rewrite_manifest(self, edit):
        path = self.directory / "feature-packs.json"
        manifest = json.loads(path.read_text())
        edit(manifest)
        data = json.dumps(manifest).encode()
        path.write_bytes(data)
        self.release["assets"] = [asset("feature-packs.json", data) if x["name"] == "feature-packs.json" else x
                                  for x in self.release["assets"]]

    def test_all_files_are_verified_and_latest_is_last(self):
        files = self.validate()
        self.assertEqual([f.name for f in files], list(inputs.SITE_NAMES))
        self.assertEqual(len(files), 12)
        self.assertEqual(files[-2].name, "app-release.apk")
        self.assertEqual(files[-1].name, "latest.json")

    def test_non_latest_or_prerelease_metadata_fails(self):
        for key, value in (("draft", True), ("prerelease", True), ("tag_name", "v11.74.507"), ("id", 0)):
            with self.subTest(key=key):
                old = self.release[key]
                self.release[key] = value
                with self.assertRaises(ValueError):
                    self.validate()
                self.release[key] = old

    def test_duplicate_release_assets_rejected(self):
        self.release["assets"].append(copy.deepcopy(self.release["assets"][0]))
        with self.assertRaises(ValueError):
            self.validate()

    def test_app_or_checksum_tampering_fails(self):
        for name in ("app-release.apk", "SHA256SUMS.txt"):
            with self.subTest(name=name):
                path = self.directory / name
                before = path.read_bytes()
                path.write_bytes(before + b"tampered")
                with self.assertRaises(ValueError):
                    self.validate()
                path.write_bytes(before)

    def test_bad_checksum_content_rejected_even_when_asset_digest_matches(self):
        path = self.directory / "SHA256SUMS.txt"
        data = b"0" * 64 + b"  app-release.apk\n"
        path.write_bytes(data)
        self.release["assets"] = [asset("SHA256SUMS.txt", data) if x["name"] == "SHA256SUMS.txt" else x
                                  for x in self.release["assets"]]
        with self.assertRaises(ValueError):
            self.validate()

    def test_partial_feature_assets_rejected(self):
        self.release["assets"] = [a for a in self.release["assets"] if a["name"] != inputs.PACK_NAMES[0]]
        with self.assertRaises(ValueError):
            self.validate()

    def test_old_release_without_pack_assets_can_use_generated_packs(self):
        self.tag, self.release = fixture(self.directory, include_packs=False)
        self.assertEqual(len(self.validate()), 12)

    def test_manifest_wrong_tag_or_missing_or_duplicate_pair_rejected(self):
        original = (self.directory / "feature-packs.json").read_bytes()
        original_release = copy.deepcopy(self.release)
        edits = [lambda m: m.update(version="v11.74.507"),
                 lambda m: m["packs"].pop(),
                 lambda m: m["packs"].append(copy.deepcopy(m["packs"][0])),
                 lambda m: m["packs"][0].update(file="../../app-release.apk")]
        for edit in edits:
            with self.subTest(edit=edit):
                (self.directory / "feature-packs.json").write_bytes(original)
                self.release = copy.deepcopy(original_release)
                self.rewrite_manifest(edit)
                with self.assertRaises(ValueError):
                    self.validate()

    def test_pack_bytes_or_release_digest_tampering_fails(self):
        path = self.directory / inputs.PACK_NAMES[0]
        old = path.read_bytes()
        path.write_bytes(b"X" * len(old))
        with self.assertRaises(ValueError):
            self.validate()
        path.write_bytes(old)
        for entry in self.release["assets"]:
            if entry["name"] == inputs.PACK_NAMES[0]:
                entry["digest"] = "sha256:" + "0" * 64
        with self.assertRaises(ValueError):
            self.validate()

    def test_bad_or_symlinked_latest_rejected(self):
        latest = self.directory / "latest.json"
        latest.write_text(json.dumps({"version": self.tag, "sha256": "0" * 64}))
        with self.assertRaises(ValueError):
            self.validate()
        latest.unlink()
        latest.symlink_to(self.directory / "app-release.apk")
        with self.assertRaises(ValueError):
            self.validate()

    def test_oversized_metadata_rejected(self):
        (self.directory / "latest.json").write_bytes(b"x" * (inputs.MAX_METADATA_BYTES + 1))
        with self.assertRaises(ValueError):
            self.validate()

    def test_fingerprint_ignores_unrelated_sync_assets_but_detects_changes(self):
        expected = inputs.tracked_fingerprint(self.release, self.tag)
        self.release["assets"].append(asset("apumir-full.bundle", b"project"))
        self.assertEqual(inputs.tracked_fingerprint(self.release, self.tag), expected)
        self.release["assets"][0]["digest"] = "sha256:" + "0" * 64
        self.assertNotEqual(inputs.tracked_fingerprint(self.release, self.tag), expected)
        self.release["tag_name"] = "v11.74.509"
        with self.assertRaises(ValueError):
            inputs.tracked_fingerprint(self.release, self.tag)


class FakeFtp:
    def __init__(self):
        self.files = {}
        self.commands = []
        self.size_override = "actual"
        self.fail_store = False
        self.fail_rename = False
        self.fail_delete = False

    def storbinary(self, command, reader, blocksize):
        name = command.removeprefix("STOR ")
        self.commands.append(("store", name))
        self.files[name] = b""
        while True:
            block = reader.read(blocksize)
            if not block:
                break
            self.files[name] += block
            if self.fail_store:
                raise OSError("untrusted sensitive server detail")

    def voidcmd(self, command):
        self.commands.append(("command", command))

    def size(self, name):
        return len(self.files[name]) if self.size_override == "actual" else self.size_override

    def rename(self, source, destination):
        self.commands.append(("rename", source, destination))
        if self.fail_rename:
            raise ftplib.error_perm("550 denied")
        self.files[destination] = self.files.pop(source)

    def delete(self, name):
        self.commands.append(("delete", name))
        if self.fail_delete:
            raise OSError("cleanup failed")
        self.files.pop(name, None)


class StagedUploadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.tag, self.release = fixture(self.directory)
        self.files = inputs.validate_inputs(self.directory, self.release, self.tag)
        self.spec = next(f for f in self.files if f.name == "app-release.apk")
        self.ftp = FakeFtp()
        self.ftp.files[self.spec.name] = b"old complete APK"
        self.messages = []
        self.token = lambda: uuid.UUID(int=1)

    def send(self, guard=lambda: True):
        upload.publish_one(self.ftp, self.directory, self.spec, guard, self.messages.append, self.token)

    def test_complete_stage_promotes_once_and_never_stores_public_name(self):
        self.send()
        self.assertEqual(self.ftp.files[self.spec.name], (self.directory / self.spec.name).read_bytes())
        self.assertEqual([c[0] for c in self.ftp.commands], ["store", "command", "rename"])
        self.assertNotEqual(self.ftp.commands[0][1], self.spec.name)
        self.assertFalse(any(k.startswith("apumir-stage-") for k in self.ftp.files))

    def test_failed_or_interrupted_transfer_preserves_old_public_file(self):
        self.ftp.fail_store = True
        with self.assertRaises(OSError):
            self.send()
        self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")
        self.assertEqual(self.ftp.commands[-1][0], "delete")
        self.assertFalse(any(k.startswith("apumir-stage-") for k in self.ftp.files))

    def test_wrong_or_missing_size_never_promotes(self):
        for size in (None, 1, self.spec.size + 1):
            with self.subTest(size=size):
                self.ftp.size_override = size
                with self.assertRaises(ValueError):
                    self.send()
                self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")
                self.assertFalse(any(k.startswith("apumir-stage-") for k in self.ftp.files))

    def test_changed_latest_never_promotes(self):
        with self.assertRaises(ValueError):
            self.send(lambda: False)
        self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")
        self.assertFalse(any(c[0] == "rename" for c in self.ftp.commands))

    def test_failed_overwrite_rename_never_deletes_old_destination(self):
        self.ftp.fail_rename = True
        with self.assertRaises(ftplib.error_perm):
            self.send()
        self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")
        self.assertFalse(any(c == ("delete", self.spec.name) for c in self.ftp.commands))

    def test_changed_local_file_is_never_promoted(self):
        path = self.directory / self.spec.name
        path.write_bytes(b"X" * self.spec.size)
        with self.assertRaises(ValueError):
            self.send()
        self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")

    def test_cleanup_failure_is_visible_without_masking_original_error(self):
        self.ftp.fail_store = True
        self.ftp.fail_delete = True
        with self.assertRaises(OSError):
            self.send()
        self.assertIn("Staging cleanup failed: OSError", self.messages)
        self.assertEqual(self.ftp.files[self.spec.name], b"old complete APK")

    def test_invalid_filename_or_order_performs_no_write(self):
        with self.assertRaises(ValueError):
            upload.publish_one(self.ftp, self.directory, inputs.SiteFile("../evil", 1, "a" * 64), lambda: True)
        with self.assertRaises(ValueError):
            upload.publish_all(self.ftp, self.directory, list(reversed(self.files)), lambda: True)
        with self.assertRaises(ValueError):
            upload.publish_one(self.ftp, self.directory, self.spec, lambda: True,
                               token_factory=lambda: type("Bad", (), {"hex": "evil\r\nDELE"})())
        self.assertEqual(self.ftp.commands, [])

    def test_all_files_use_manifest_then_apk_then_latest(self):
        calls = []
        upload.publish_all(self.ftp, self.directory, self.files, lambda: calls.append(True) or True, self.messages.append)
        renamed = [c[2] for c in self.ftp.commands if c[0] == "rename"]
        self.assertEqual(renamed, list(inputs.SITE_NAMES))
        self.assertEqual(len(calls), 12)
        for spec in self.files:
            self.assertEqual(self.ftp.files[spec.name], (self.directory / spec.name).read_bytes())

    def test_failed_guard_mid_run_keeps_old_latest(self):
        self.ftp.files["latest.json"] = b"old latest"
        remaining = iter([True] * 5 + [False])
        with self.assertRaises(ValueError):
            upload.publish_all(self.ftp, self.directory, self.files, lambda: next(remaining), self.messages.append)
        self.assertEqual(self.ftp.files["latest.json"], b"old latest")

    def test_directory_and_host_validation(self):
        self.assertEqual(upload.remote_directory(""), "/downloads")
        self.assertEqual(upload.remote_directory("/downloads/test-dir"), "/downloads/test-dir")
        self.assertEqual(upload.normalize_host("ftp://ftp.example.org/"), "ftp.example.org")
        for invalid in ("/downloads/../etc", "/downloads\r\nDELE x", "downloads", "/"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                upload.remote_directory(invalid)
        for invalid in ("ftp://host/path", "host\ncommand", "user:password@host"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                upload.normalize_host(invalid)

    def test_latest_query_fails_closed(self):
        expected = inputs.tracked_fingerprint(self.release, self.tag)
        response = type("P", (), {"stdout": json.dumps(self.release).encode()})()
        with patch.object(upload.subprocess, "run", return_value=response):
            self.assertTrue(upload.current_release_matches("owner/repo", self.tag, expected))
            self.assertFalse(upload.current_release_matches("owner/repo", "v11.74.509", expected))
        with patch.object(upload.subprocess, "run", side_effect=OSError("network unavailable")):
            self.assertFalse(upload.current_release_matches("owner/repo", self.tag, expected))


class HttpVerificationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        tag, release = fixture(self.directory)
        self.files = inputs.validate_inputs(self.directory, release, tag)
        self.contents = {f.name: (self.directory / f.name).read_bytes() for f in self.files}
        self.calls = []

    def fetch(self, name):
        self.calls.append(name)
        return io.BytesIO(self.contents[name])

    def test_every_file_and_both_pointers_checked_again(self):
        checks = []
        http_verify.verify_all(self.files, lambda: checks.append(True) or True, self.fetch, lambda _: None)
        self.assertEqual(len(checks), 2)
        self.assertEqual(set(self.calls), set(inputs.SITE_NAMES))
        self.assertEqual(self.calls.count("latest.json"), 2)
        self.assertEqual(self.calls.count("feature-packs.json"), 2)

    def test_truncated_oversized_or_same_size_wrong_content_fail(self):
        name = "app-release.apk"
        original = self.contents[name]
        for data in (original[:-1], original + b"x", b"x" * len(original)):
            with self.subTest(length=len(data)):
                self.contents[name] = data
                with self.assertRaises(ValueError):
                    http_verify.verify_all(self.files, lambda: True, self.fetch, lambda _: None)

    def test_pointer_changed_during_read_is_detected(self):
        def changed(name):
            if name == "latest.json" and self.calls.count(name) == 1:
                self.calls.append(name)
                return io.BytesIO(b"changed")
            return self.fetch(name)
        with self.assertRaises(ValueError):
            http_verify.verify_all(self.files, lambda: True, changed, lambda _: None)

    def test_latest_changed_or_invalid_order_fail(self):
        with self.assertRaises(ValueError):
            http_verify.verify_all(self.files, lambda: False, self.fetch, lambda _: None)
        with self.assertRaises(ValueError):
            http_verify.verify_all(list(reversed(self.files)), lambda: True, self.fetch, lambda _: None)
        self.assertEqual(self.calls, [])

    def test_external_or_http_redirect_rejected(self):
        handler = http_verify.SiteRedirectsOnly()
        for url in ("http://apu.clan.su/downloads/app-release.apk", "https://example.org/app-release.apk"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                handler.redirect_request(None, None, 302, "", {}, url)


if __name__ == "__main__":
    unittest.main(verbosity=2)
