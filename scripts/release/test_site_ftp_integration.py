#!/usr/bin/env python3
"""Loopback FTP integration tests; requires the test-only pyftpdlib package.

Example: PYTHONPATH=target/ftp-test python3 scripts/release/test_site_ftp_integration.py
No remote FTP server or credentials are used by these tests.
"""
import ftplib
import hashlib
import logging
import tempfile
import threading
import unittest
from pathlib import Path

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

from site_release_files import SiteFile
from site_staged_upload import publish_one
from site_ftp_probe import probe
from site_ftp_fault_probe import probe as fault_probe

logging.getLogger("pyftpdlib").setLevel(logging.ERROR)


class LocalFtpIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "server"
        self.local = Path(self.tmp.name) / "local"
        self.downloads = self.root / "downloads"
        self.downloads.mkdir(parents=True)
        self.local.mkdir()
        self.name = "app-release.apk"
        self.old = b"complete previous APK"
        self.new = bytes(range(256)) * 2500
        (self.downloads / self.name).write_bytes(self.old)
        (self.local / self.name).write_bytes(self.new)
        self.spec = SiteFile(self.name, len(self.new), hashlib.sha256(self.new).hexdigest())
        self.server = None
        self.thread = None
        self.ftp = None
        self.addCleanup(self.stop_server)

    def start_server(self, reject_overwrite=False, reject_long_names=False):
        authorizer = DummyAuthorizer()
        authorizer.add_user("fixture", "fixture", str(self.root), perm="elradfmwMT")
        class Handler(FTPHandler):
            pass
        Handler.authorizer = authorizer
        if reject_overwrite:
            def rename(handler, path):
                if Path(path).exists():
                    handler.respond("550 Overwriting is disabled for this fixture")
                    return
                return FTPHandler.ftp_RNTO(handler, path)
            Handler.ftp_RNTO = rename
        if reject_long_names:
            def store(handler, path, mode="w"):
                if len(Path(path).name) > 64:
                    handler.respond("550 Fixture disallows long filenames")
                    return
                return FTPHandler.ftp_STOR(handler, path, mode)
            Handler.ftp_STOR = store
        self.server = FTPServer(("127.0.0.1", 0), Handler)
        port = self.server.socket.getsockname()[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"timeout": 0.1, "blocking": True, "handle_exit": False},
                                       daemon=True)
        self.thread.start()
        self.ftp = ftplib.FTP()
        self.ftp.connect("127.0.0.1", port, timeout=5)
        self.ftp.login("fixture", "fixture")
        self.ftp.cwd("/downloads")

    def stop_server(self):
        if self.ftp is not None:
            try:
                self.ftp.quit()
            except (OSError, EOFError, ftplib.Error):
                pass  # Test server may already have closed the control connection.
        if self.server is not None:
            self.server.close_all()
        if self.thread is not None:
            self.thread.join(timeout=5)

    def test_real_ftp_store_size_and_rename_replace_complete_file(self):
        self.start_server()
        publish_one(self.ftp, self.local, self.spec, lambda: True, lambda _: None)
        self.assertEqual((self.downloads / self.name).read_bytes(), self.new)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])

    def test_real_ftp_cancelled_guard_keeps_old_file_and_cleans_stage(self):
        self.start_server()
        with self.assertRaises(ValueError):
            publish_one(self.ftp, self.local, self.spec, lambda: False, lambda _: None)
        self.assertEqual((self.downloads / self.name).read_bytes(), self.old)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])

    def test_real_ftp_refused_overwrite_keeps_old_file_and_cleans_stage(self):
        self.start_server(reject_overwrite=True)
        with self.assertRaises(ftplib.error_perm):
            publish_one(self.ftp, self.local, self.spec, lambda: True, lambda _: None)
        self.assertEqual((self.downloads / self.name).read_bytes(), self.old)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])

    def test_real_ftp_capability_probe_does_not_touch_public_file(self):
        self.start_server()
        probe(self.ftp, log=lambda _: None)
        self.assertEqual((self.downloads / self.name).read_bytes(), self.old)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])

    def test_real_ftp_fault_probe_cleans_all_names_and_preserves_public_file(self):
        self.start_server()
        self.assertTrue(fault_probe(self.ftp, large=self.local / self.name, log=lambda _: None))
        self.assertEqual((self.downloads / self.name).read_bytes(), self.old)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])

    def test_real_ftp_fault_probe_distinguishes_refused_long_name(self):
        self.start_server(reject_long_names=True)
        messages = []
        self.assertFalse(fault_probe(self.ftp, log=messages.append))
        self.assertTrue(any("production-zip: STOR failed (error_perm, FTP code 550)" in m for m in messages))
        self.assertTrue(any("short-zip: STOR and SIZE succeeded" in m for m in messages))
        self.assertEqual((self.downloads / self.name).read_bytes(), self.old)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), [self.name])


if __name__ == "__main__":
    unittest.main(verbosity=2)
