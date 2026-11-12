#!/usr/bin/env python3
"""Offline tests for read-only site-publication diagnostics."""
import contextlib
import io
import json
import os
import unittest
from unittest.mock import patch

import inspect_site_publication as inspect


class SiteMetadataTest(unittest.TestCase):
    def test_accepts_expected_version_and_digest(self):
        data = json.dumps({"version": "v11.74.508", "sha256": "a" * 64}).encode()
        self.assertEqual(inspect.parse_latest(data), ("v11.74.508", "a" * 64))

    def test_rejects_injected_version_and_malformed_hash(self):
        for version, digest in (("v11.74.508\n::warning::oops", "a" * 64),
                                ("v11.74.508", "A" * 64),
                                ("v11.74.508", "x" * 64),
                                (1, "a" * 64)):
            with self.subTest(version=version, digest=digest):
                with self.assertRaises(ValueError):
                    inspect.parse_latest(json.dumps({"version": version, "sha256": digest}).encode())

    def test_blocks_redirects_outside_https_site(self):
        handler = inspect.SiteRedirectsOnly()
        for url in ("http://apu.clan.su/downloads/latest.json", "https://example.com/latest.json",
                    "https://apu.clan.su.example.org/downloads/latest.json"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                handler.redirect_request(None, None, 302, "", {}, url)


class SafeLogTest(unittest.TestCase):
    def test_only_whitelisted_real_output_lines_count(self):
        log = b'''2026-11-12T17:22:00.0000000Z   echo "FTP -> $REMOTE_DIR/$name ($(stat -c %s "$file") B)"
2026-11-12T17:22:01.0000000Z FTP -> /downloads/feature-pack-translate-arm64-v8a.zip (123 B)
2026-11-12T17:22:02.0000000Z FTP -> /downloads/unknown-secret (999 B)
2026-11-12T17:22:03.0000000Z FTP -> /downloads/app-release.apk (456 B)
'''
        self.assertEqual(inspect.last_uploaded_name(log), "app-release.apk")

    def test_does_not_treat_printed_shell_as_progress(self):
        self.assertIsNone(inspect.last_uploaded_name(
            b'2026-11-12T17:22:00.0000000Z   echo "FTP -> $REMOTE_DIR/$name (10 B)"\n'
        ))

    def test_rejects_non_whitelisted_or_non_timestamped_lines(self):
        self.assertIsNone(inspect.last_uploaded_name(
            b'FTP -> /downloads/app-release.apk (10 B)\n'
            b'2026-11-12T17:22:00Z FTP -> /downloads/latest.json/unknown (10 B)\n'
            b'2026-11-12T17:22:00Z FTP -> /downloads/\xff (10 B)\n'
        ))


class EndToEndTest(unittest.TestCase):
    def test_reports_mismatch_and_safe_progress_without_printing_log(self):
        tag = "v11.74.508"
        released = {"tag_name": tag, "assets": [
            {"name": "app-release.apk", "digest": "sha256:" + "a" * 64},
            {"name": "feature-packs.json", "digest": "sha256:" + "b" * 64},
        ]}
        runs = {"workflow_runs": [{"head_branch": tag, "event": "release", "id": 123, "status": "in_progress"}]}
        jobs = {"jobs": [{"name": "Upload release files to apu.clan.su", "id": 456,
                          "steps": [{"status": "in_progress", "name": "Upload to apu.clan.su via FTP"}]}]}
        metadata = json.dumps({"version": "v11.74.507", "sha256": "c" * 64}).encode()
        log = b'2026-11-12T17:22:01Z FTP -> /downloads/app-release.apk (456 B)\nSECRET-DO-NOT-PRINT\n'
        out = io.StringIO()
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
             patch.object(inspect, "gh_json", side_effect=[released, runs, jobs]), \
             patch.object(inspect, "read_site", side_effect=[metadata, b"{}"]), \
             patch.object(inspect, "running_log_tail", return_value=log), contextlib.redirect_stdout(out):
            self.assertEqual(inspect.main(), 0)
        text = out.getvalue()
        self.assertIn("matches Latest: False", text)
        self.assertIn("feature-packs.json SHA-256 matches release: False", text)
        self.assertIn("Last observed FTP file started: app-release.apk", text)
        self.assertNotIn("SECRET-DO-NOT-PRINT", text)

    def test_invalid_repository_never_calls_network(self):
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "../bad"}), \
             patch.object(inspect, "gh_json") as api, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(inspect.main(), 1)
            api.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
