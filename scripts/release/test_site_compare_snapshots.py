#!/usr/bin/env python3
"""Offline tests for read-only, bounded comparison of two site snapshots."""
import hashlib
import io
import json
import os
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import site_compare_snapshots as compare
from site_release_files import MAX_METADATA_BYTES, SITE_NAMES, SiteFile
from test_site_staged_upload import asset, fixture


def specs(data):
    return [SiteFile(name, len(data[name]), hashlib.sha256(data[name]).hexdigest()) for name in SITE_NAMES]


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        # "old" and "new" have the same size, so a size-only comparison fails.
        self.old = {name: f"old {name}".encode() for name in SITE_NAMES}
        self.new = {name: f"new {name}".encode() for name in SITE_NAMES}
        self.old_specs = specs(self.old)
        self.new_specs = specs(self.new)
        self.messages = []
        self.sleeps = []
        self.calls = []

    def fetch(self, data):
        def get(name):
            self.calls.append(name)
            return io.BytesIO(data[name])
        return get

    def run_compare(self, fetch):
        return compare.compare_all(self.old_specs, self.new_specs, fetch, self.sleeps.append, self.messages.append)

    def test_all_old_and_all_new_recheck_both_pointers(self):
        for data, expected in ((self.old, "all old"), (self.new, "all new")):
            with self.subTest(expected=expected):
                self.calls.clear()
                self.assertEqual(self.run_compare(self.fetch(data)), expected)
                self.assertEqual(self.calls.count("latest.json"), 2)
                self.assertEqual(self.calls.count("feature-packs.json"), 2)
                self.assertEqual(set(self.calls), set(SITE_NAMES))

    def test_mixed_same_size_files_are_identified_by_hash(self):
        mixed = dict(self.old)
        name = SITE_NAMES[3]
        mixed[name] = self.new[name]
        self.assertEqual(self.run_compare(self.fetch(mixed)), "mixed or unverified")
        self.assertTrue(any(f"{name}: new" in message for message in self.messages))
        self.assertTrue(any(f"{SITE_NAMES[0]}: old" in message for message in self.messages))

    def test_both_is_reported_for_identical_old_and_new_bytes(self):
        name = SITE_NAMES[0]
        self.new[name] = self.old[name]
        self.new_specs = specs(self.new)
        self.assertEqual(self.run_compare(self.fetch(self.old)), "all old")
        self.assertTrue(any(f"{name}: both" in message for message in self.messages))

    def test_wrong_truncated_and_oversized_files_are_neither(self):
        name = SITE_NAMES[0]
        for content in (b"X" * len(self.old[name]), self.old[name][:-1], self.old[name] + b"extra"):
            with self.subTest(size=len(content)):
                self.messages.clear()
                data = dict(self.old)
                data[name] = content
                self.assertEqual(self.run_compare(self.fetch(data)), "mixed or unverified")
                self.assertTrue(any(f"{name}: neither" in message for message in self.messages))

    def test_pointer_change_while_scanning_is_not_a_consistent_snapshot(self):
        counts = {}
        def fetch(name):
            counts[name] = counts.get(name, 0) + 1
            content = self.new[name] if name == "latest.json" and counts[name] == 2 else self.old[name]
            return io.BytesIO(content)
        self.assertEqual(self.run_compare(fetch), "mixed or unverified")
        self.assertTrue(any("pointer changed" in message for message in self.messages))

    def test_transient_network_error_retries_from_beginning(self):
        attempts = {}
        def fetch(name):
            attempts[name] = attempts.get(name, 0) + 1
            if name == SITE_NAMES[0] and attempts[name] < 3:
                raise TimeoutError("private detail")
            return io.BytesIO(self.old[name])
        self.assertEqual(self.run_compare(fetch), "all old")
        self.assertEqual(attempts[SITE_NAMES[0]], 3)
        self.assertEqual(self.sleeps, [1, 3])
        self.assertNotIn("private detail", "\n".join(self.messages))

    def test_forbidden_http_and_rejected_redirect_are_safe_unavailable(self):
        for error in (urllib.error.HTTPError("https://private.invalid/secret", 403, "SECRET", None, None),
                      ValueError("https://private.invalid/secret")):
            with self.subTest(error=type(error).__name__):
                self.messages.clear()
                self.calls.clear()
                def fetch(name):
                    self.calls.append(name)
                    if name == SITE_NAMES[0]:
                        raise error
                    return io.BytesIO(self.old[name])
                self.assertEqual(self.run_compare(fetch), "mixed or unverified")
                self.assertEqual(self.calls.count(SITE_NAMES[0]), 1)
                self.assertTrue(any(f"{SITE_NAMES[0]}: unavailable" in message for message in self.messages))
                self.assertNotIn("SECRET", "\n".join(self.messages))
                self.assertNotIn("private.invalid", "\n".join(self.messages))

    def test_malformed_specs_fail_before_fetch(self):
        calls = []
        for bad in (list(reversed(self.old_specs)),
                    [SiteFile(SITE_NAMES[0], 0, "a" * 64), *self.old_specs[1:]],
                    [SiteFile(SITE_NAMES[0], 1, "invalid"), *self.old_specs[1:]]):
            with self.subTest(bad=bad[0]), self.assertRaises(ValueError):
                compare.compare_all(bad, self.new_specs, lambda name: calls.append(name))
        self.assertEqual(calls, [])

    def test_reads_are_bounded_to_fixed_blocks_and_expected_maximum(self):
        class CheckedStream(io.BytesIO):
            def read(self, size=-1):
                if not 0 < size <= compare.BLOCK:
                    raise AssertionError("unbounded read")
                return super().read(size)
        self.assertEqual(self.run_compare(lambda name: CheckedStream(self.old[name])), "all old")


class MainTest(unittest.TestCase):
    def test_main_validates_both_snapshots_and_checks_latest_twice(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old_dir, new_dir = root / "reference-files", root / "site-files"
            old_dir.mkdir()
            new_dir.mkdir()
            old_tag, old_release = fixture(old_dir)
            _, new_release = fixture(new_dir)
            new_tag = "v11.74.509"
            new_release["tag_name"] = new_tag
            manifest_path = new_dir / "feature-packs.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["version"] = new_tag
            manifest_bytes = json.dumps(manifest).encode()
            manifest_path.write_bytes(manifest_bytes)
            new_release["assets"] = [asset("feature-packs.json", manifest_bytes) if a["name"] == "feature-packs.json" else a
                                     for a in new_release["assets"]]
            latest_path = new_dir / "latest.json"
            latest = json.loads(latest_path.read_text())
            latest["version"] = new_tag
            latest_path.write_text(json.dumps(latest))
            (root / "site-reference.json").write_text(json.dumps(old_release))
            (root / "site-release.json").write_text(json.dumps(new_release))
            old_cwd = Path.cwd()
            try:
                os.chdir(root)
                env = {"GITHUB_REPOSITORY": "owner/repo", "APU_REFERENCE_TAG": old_tag,
                       "APU_RELEASE_TAG": new_tag}
                with patch.dict(os.environ, env), patch.object(compare, "current_release_matches", return_value=True) as guard, \
                        patch.object(compare, "compare_all", return_value="all old") as comparison, redirect_stdout(io.StringIO()):
                    self.assertEqual(compare.main(), 0)
                    self.assertEqual(guard.call_count, 2)
                    self.assertEqual(comparison.call_count, 1)
                with patch.dict(os.environ, env), patch.object(compare, "current_release_matches", return_value=False), \
                        patch.object(compare, "compare_all", return_value="all old"), redirect_stdout(io.StringIO()):
                    self.assertEqual(compare.main(), 1)
                latest_path.write_text('{"version":"v11.74.500"}')
                with patch.dict(os.environ, env), patch.object(compare, "compare_all") as comparison, redirect_stdout(io.StringIO()):
                    self.assertEqual(compare.main(), 1)
                    comparison.assert_not_called()
            finally:
                os.chdir(old_cwd)

    def test_oversized_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "release.json"
            path.write_bytes(b" " * (MAX_METADATA_BYTES + 1))
            with self.assertRaises(ValueError):
                compare._load_release(path)


class RecoveryWorkflowContractTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2]
        self.workflow = (root / '.github/workflows/recover-site-v509.yml').read_text()

    def test_explicit_fixed_repository_branch_marker_and_tags(self):
        text = self.workflow
        self.assertIn("branches: ['arena/1266946d-apumir']", text)
        self.assertIn("github.repository == 'vzhem/APUMIR'", text)
        self.assertIn("github.ref == 'refs/heads/arena/1266946d-apumir'", text)
        self.assertIn("contains(github.event.head_commit.message, '[publish-site-509]')", text)
        self.assertIn('APU_RELEASE_TAG: v11.74.509', text)
        self.assertIn('APU_REFERENCE_TAG: v11.74.508', text)
        self.assertIn('group: site-publish', text)
        self.assertNotIn('workflow_dispatch:', text)
        self.assertNotIn('contents: write', text)

    def test_verified_baseline_precedes_upload_and_full_http_check(self):
        text = self.workflow
        self.assertLess(text.index('id: prepare'), text.index('id: baseline'))
        self.assertLess(text.index('id: baseline'), text.index('id: upload'))
        self.assertLess(text.index('id: upload'), text.index('id: verify'))
        self.assertIn('verify_all(files, guard)', text)
        self.assertIn('tracked_fingerprint(release, tag)', text)
        self.assertIn('python3 scripts/release/site_staged_upload.py', text)
        self.assertIn('python3 scripts/release/site_verify_http.py', text)
        self.assertNotIn('continue-on-error', text)
        self.assertNotIn('ftp_cleanup.py', text)

    def test_failure_comparison_is_read_only_and_runs_after_any_guarded_failure(self):
        text = self.workflow
        self.assertIn("steps.prepare.outcome == 'success'", text)
        for step in ('baseline', 'upload', 'verify'):
            self.assertIn(f"steps.{step}.outcome == 'failure'", text)
        self.assertIn('python3 scripts/release/site_compare_snapshots.py', text)
        self.assertIn('python3 scripts/release/test_site_compare_snapshots.py', text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
