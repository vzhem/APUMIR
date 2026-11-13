#!/usr/bin/env python3
"""Offline tests for the isolated temporary-file FTP capability probe."""
import ftplib
import unittest
import uuid

import site_ftp_probe as probe


class FakeFtp:
    def __init__(self):
        self.files = {"app-release.apk": b"live APK", "latest.json": b"live metadata"}
        self.commands = []
        self.deny_rename = False
        self.deny_retr = False
        self.fail_delete = False
        self.bad_size = False

    def nlst(self):
        return list(self.files)

    def storbinary(self, command, source):
        name = command.removeprefix("STOR ")
        self.commands.append(("store", name))
        self.files[name] = source.read()

    def voidcmd(self, command):
        self.commands.append(("command", command))

    def size(self, name):
        return len(self.files[name]) + (1 if self.bad_size else 0)

    def rename(self, source, destination):
        self.commands.append(("rename", source, destination))
        if self.deny_rename:
            raise ftplib.error_perm("550 denied")
        self.files[destination] = self.files.pop(source)

    def retrbinary(self, command, callback):
        if self.deny_retr:
            raise ftplib.error_perm("550 denied")
        callback(self.files[command.removeprefix("RETR ")])

    def delete(self, name):
        self.commands.append(("delete", name))
        if self.fail_delete:
            raise OSError("cleanup unavailable")
        self.files.pop(name)


class FtpProbeTest(unittest.TestCase):
    def setUp(self):
        self.ftp = FakeFtp()
        self.token = lambda: uuid.UUID(int=17)
        self.messages = []

    def run_probe(self):
        probe.probe(self.ftp, self.token, self.messages.append)

    def assert_live_untouched(self):
        self.assertEqual(self.ftp.files["app-release.apk"], b"live APK")
        self.assertEqual(self.ftp.files["latest.json"], b"live metadata")
        for command in self.ftp.commands:
            self.assertNotIn("app-release.apk", command[1:])
            self.assertNotIn("latest.json", command[1:])

    def test_success_renames_and_cleans_only_temporary_files(self):
        self.run_probe()
        self.assertEqual(set(self.ftp.files), {"app-release.apk", "latest.json"})
        self.assert_live_untouched()
        self.assertIn("FTP rename overwrite, SIZE and RETR succeeded on temporary files", self.messages)

    def test_denied_retr_reports_limitation_but_checks_size(self):
        self.ftp.deny_retr = True
        self.run_probe()
        self.assertEqual(set(self.ftp.files), {"app-release.apk", "latest.json"})
        self.assertIn("FTP rename overwrite and SIZE succeeded; RETR is unavailable", self.messages)
        self.assert_live_untouched()

    def test_denied_rename_or_wrong_size_cleans_temporaries(self):
        for failure in ("deny_rename", "bad_size"):
            with self.subTest(failure=failure):
                self.ftp = FakeFtp()
                setattr(self.ftp, failure, True)
                with self.assertRaises((ValueError, ftplib.error_perm)):
                    self.run_probe()
                self.assertEqual(set(self.ftp.files), {"app-release.apk", "latest.json"})
                self.assert_live_untouched()

    def test_existing_temporary_name_is_not_overwritten_or_deleted(self):
        name = f"apumir-probe-{self.token().hex}-a.json"
        self.ftp.files[name] = b"existing"
        with self.assertRaises(ValueError):
            self.run_probe()
        self.assertEqual(self.ftp.files[name], b"existing")
        self.assertEqual(self.ftp.commands, [])
        self.assert_live_untouched()

    def test_cleanup_error_is_reported(self):
        self.ftp.fail_delete = True
        with self.assertRaises(OSError):
            self.run_probe()
        self.assertIn("Probe cleanup failed: OSError", self.messages)
        self.assert_live_untouched()

    def test_invalid_probe_token_is_rejected_before_listing(self):
        with self.assertRaises(ValueError):
            probe.probe(self.ftp, lambda: type("Bad", (), {"hex": "../../bad"})(), self.messages.append)
        self.assertEqual(self.ftp.commands, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
