#!/usr/bin/env python3
"""Offline safety tests for the isolated failed-publication FTP probe."""
import ftplib
import tempfile
import unittest
import uuid
from pathlib import Path

import site_ftp_fault_probe as probe


class FakeFtp:
    def __init__(self):
        self.files = {
            "app-release.apk": b"public apk",
            "latest.json": b"public metadata",
            probe.PACK_NAME: b"public feature pack",
        }
        self.commands = []
        self.deny_store = lambda _name, _data: False
        self.partial_on_failure = False
        self.fail_delete = False
        self.bad_size = False
        self.fail_listing_after_store = False
        self.stored = False

    def nlst(self):
        if self.fail_listing_after_store and self.stored:
            raise ftplib.error_perm("550 private listing error")
        return list(self.files)

    def storbinary(self, command, source, blocksize=8192):
        name = command.removeprefix("STOR ")
        data = source.read()
        self.commands.append(("store", name))
        self.stored = True
        if self.deny_store(name, data):
            if self.partial_on_failure:
                self.files[name] = data[:1]
            raise ftplib.error_perm("550 private server text SECRET-DO-NOT-PRINT")
        self.files[name] = data

    def voidcmd(self, command):
        self.commands.append(("command", command))

    def size(self, name):
        return len(self.files[name]) + int(self.bad_size)

    def delete(self, name):
        self.commands.append(("delete", name))
        if self.fail_delete:
            raise ftplib.error_perm("550 private cleanup text SECRET-DO-NOT-PRINT")
        self.files.pop(name)


class FaultProbeTest(unittest.TestCase):
    def setUp(self):
        self.ftp = FakeFtp()
        self.public = dict(self.ftp.files)
        self.messages = []
        counter = iter(range(1, 100))
        self.token = lambda: uuid.UUID(int=next(counter))

    def run_probe(self, large=None):
        return probe.probe(self.ftp, large, self.token, self.messages.append)

    def assert_public_untouched(self):
        for name, value in self.public.items():
            self.assertEqual(self.ftp.files[name], value)
        for command in self.ftp.commands:
            if command[0] in {"store", "delete"}:
                self.assertNotIn(command[1], self.public)
        self.assertNotIn("SECRET-DO-NOT-PRINT", "\n".join(self.messages))

    def test_all_samples_are_isolated_and_removed(self):
        self.assertTrue(self.run_probe())
        self.assertEqual(self.ftp.files, self.public)
        self.assertEqual(sum(c[0] == "store" for c in self.ftp.commands), 5)
        self.assert_public_untouched()

    def test_denied_long_zip_is_distinguished_from_short_zip_and_absent_cleanup(self):
        self.ftp.deny_store = lambda name, _data: name.startswith("apumir-stage-") and name.endswith(probe.PACK_NAME)
        self.assertFalse(self.run_probe())
        text = "\n".join(self.messages)
        self.assertIn("production-zip: STOR failed (error_perm, FTP code 550)", text)
        self.assertIn("short-zip: STOR and SIZE succeeded", text)
        failed_name = next(c[1] for c in self.ftp.commands if c[0] == "store")
        self.assertNotIn(("delete", failed_name), self.ftp.commands)
        self.assertEqual(self.ftp.files, self.public)
        self.assert_public_untouched()

    def test_partial_large_sample_is_removed_after_denial(self):
        with tempfile.TemporaryDirectory() as directory:
            large = Path(directory) / "sample.zip"
            large.write_bytes(b"x" * 2048)
            self.ftp.deny_store = lambda _name, data: len(data) > 1000
            self.ftp.partial_on_failure = True
            self.assertFalse(self.run_probe(large))
        self.assertIn("release-sized-zip: STOR failed (error_perm, FTP code 550)", "\n".join(self.messages))
        self.assertEqual(self.ftp.files, self.public)
        self.assert_public_untouched()

    def test_cleanup_failure_is_reported_and_stops_further_stores(self):
        self.ftp.fail_delete = True
        self.assertFalse(self.run_probe())
        self.assertEqual(sum(c[0] == "store" for c in self.ftp.commands), 1)
        self.assertIn("production-zip: cleanup failed (error_perm, FTP code 550)", "\n".join(self.messages))
        self.assert_public_untouched()

    def test_unavailable_post_store_listing_stops_and_reports_cleanup(self):
        self.ftp.fail_listing_after_store = True
        self.assertFalse(self.run_probe())
        self.assertEqual(sum(c[0] == "store" for c in self.ftp.commands), 1)
        self.assertIn("production-zip: cleanup failed", "\n".join(self.messages))
        self.assert_public_untouched()

    def test_size_mismatch_is_a_failure_but_temporary_files_are_removed(self):
        self.ftp.bad_size = True
        self.assertFalse(self.run_probe())
        self.assertIn("production-zip: SIZE failed (ValueError, FTP code unknown)", "\n".join(self.messages))
        self.assertEqual(self.ftp.files, self.public)
        self.assert_public_untouched()

    def test_preexisting_random_name_is_never_written_or_deleted(self):
        fixed = lambda: uuid.UUID(int=17)
        name = f"apumir-stage-{fixed().hex}-{probe.PACK_NAME}"
        self.ftp.files[name] = b"pre-existing"
        self.assertFalse(probe.probe(self.ftp, token_factory=fixed, log=self.messages.append))
        self.assertEqual(self.ftp.files[name], b"pre-existing")
        self.assertNotIn(("store", name), self.ftp.commands)
        self.assertNotIn(("delete", name), self.ftp.commands)
        self.assert_public_untouched()

    def test_invalid_token_and_unsafe_name_fail_before_a_write(self):
        bad = lambda: type("Bad", (), {"hex": "../../bad"})()
        with self.assertRaises(ValueError):
            probe.probe(self.ftp, token_factory=bad, log=self.messages.append)
        with self.assertRaises(ValueError):
            probe._one(self.ftp, "test", "../latest.json", None, 1, self.messages.append)
        self.assertEqual(self.ftp.commands, [])
        self.assert_public_untouched()


class WorkflowContractTest(unittest.TestCase):
    def test_probe_requires_explicit_marker_and_verified_old_site_snapshot(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/diagnose-site-ftp.yml').read_text()
        self.assertIn("branches: ['arena/**']", workflow)
        self.assertIn("contains(github.event.head_commit.message, '[diagnose-ftp-509]')", workflow)
        self.assertIn("group: site-publish", workflow)
        self.assertIn("APU_PROBE_TAG: v11.74.509", workflow)
        self.assertIn("id: baseline", workflow)
        self.assertIn("!cancelled() && steps.baseline.outcome == 'success'", workflow)
        self.assertEqual(2, workflow.count("verify_all(files, lambda: True)"))
        source = (root / 'scripts/release/site_ftp_fault_probe.py').read_text()
        self.assertIn('_file_digest(Path("probe-files"), PACK_NAME', source)
        self.assertIn('current_release_matches(repository, tag, tracked_fingerprint(release, tag))', source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
