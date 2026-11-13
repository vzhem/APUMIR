#!/usr/bin/env python3
"""Tests use fabricated public-looking identifiers; no real Firebase account is needed."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-firebase-client-config.py")
spec = importlib.util.spec_from_file_location("firebase_client_config", SCRIPT)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
install_spec = importlib.util.spec_from_file_location("firebase_config_installer", SCRIPT.with_name("install-firebase-client-config.py"))
installer = importlib.util.module_from_spec(install_spec)
install_spec.loader.exec_module(installer)


def fixture():
    return {
        "project_info": {"project_number": "123456789012", "project_id": "example-project"},
        "client": [{
            "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:0123456789abcdef",
                "android_client_info": {"package_name": "com.vladimir.messenger"},
            },
            "api_key": [{"current_key": "fake-api-key-only-for-test"}],
        }],
        "configuration_version": "1",
    }


class FirebaseConfigValidationTest(unittest.TestCase):
    def check(self, value):
        return validator.validate(json.dumps(value))

    def test_valid_client_and_other_platform_entries(self):
        value = fixture()
        self.assertEqual("valid", self.check(value))
        other = copy.deepcopy(value["client"][0])
        other["client_info"]["android_client_info"]["package_name"] = "com.example.other"
        value["client"].insert(0, other)
        self.assertEqual("valid", self.check(value))

    def test_missing_or_malformed_data_fails_closed(self):
        self.assertEqual("missing", validator.validate(""))
        for text in ("{", "null", "[]", "\ud800", "x" * (validator.MAX_CONFIG_BYTES + 1)):
            with self.subTest(text=text[:5]):
                self.assertEqual("invalid", validator.validate(text))

    def test_must_be_unique_correct_package(self):
        value = fixture()
        value["client"][0]["client_info"]["android_client_info"]["package_name"] = "com.example.other"
        self.assertEqual("invalid", self.check(value))
        value = fixture()
        value["client"].append(copy.deepcopy(value["client"][0]))
        self.assertEqual("invalid", self.check(value))

    def test_app_id_must_match_project_and_api_key_must_exist(self):
        for mutation in ("wrong_number", "bad_id", "missing_key", "empty_key", "missing_project"):
            value = fixture()
            if mutation == "wrong_number":
                value["project_info"]["project_number"] = "999999"
            elif mutation == "bad_id":
                value["client"][0]["client_info"]["mobilesdk_app_id"] = "bad"
            elif mutation == "missing_key":
                del value["client"][0]["api_key"]
            elif mutation == "empty_key":
                value["client"][0]["api_key"] = [{"current_key": ""}]
            else:
                del value["project_info"]["project_id"]
            with self.subTest(mutation=mutation):
                self.assertEqual("invalid", self.check(value))

    def test_service_account_and_private_fields_are_never_accepted(self):
        for key in ("private_key", "private_key_id", "client_email"):
            value = fixture()
            value["client"][0][key] = "DO-NOT-PRINT-THIS"
            with self.subTest(key=key):
                self.assertEqual("invalid", self.check(value))
        value = fixture()
        value["type"] = "service_account"
        self.assertEqual("invalid", self.check(value))

    def test_duplicate_keys_and_non_json_constants_are_rejected(self):
        self.assertEqual("invalid", validator.validate('{"client":[],"client":[]}'))
        value = json.dumps(fixture())[:-1] + ',"extra": NaN}'
        self.assertEqual("invalid", validator.validate(value))

    def test_cli_outputs_only_fixed_messages_and_does_not_echo_input(self):
        marker = "CLIENT-CONFIG-CONTENT-MUST-NOT-APPEAR"
        value = fixture()
        value["client"][0]["api_key"][0]["current_key"] = marker
        env = dict(os.environ, APU_FIREBASE_ANDROID_CONFIG=json.dumps(value))
        result = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode)
        self.assertNotIn(marker, result.stdout + result.stderr)
        env["APU_FIREBASE_ANDROID_CONFIG"] = '{"private_key":"' + marker
        result = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(marker, result.stdout + result.stderr)

    def test_client_count_is_bounded(self):
        value = fixture()
        value["client"] *= validator.MAX_CLIENTS + 1
        self.assertEqual("invalid", self.check(value))

    def test_installer_is_exclusive_private_and_rejects_invalid_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "google-services.json"
            self.assertFalse(installer.write_config('{"private_key":"not-a-client"}', path))
            self.assertFalse(path.exists())
            raw = installer.fake_configuration()
            self.assertTrue(installer.write_config(raw, path))
            self.assertEqual("valid", validator.validate(path.read_text()))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertFalse(installer.write_config(json.dumps(fixture()), path))
            self.assertEqual(raw, path.read_text(), "never overwrite an existing file")

    def test_installer_refuses_a_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "target"
            target.write_text("original")
            path = Path(tmp) / "google-services.json"
            path.symlink_to(target)
            self.assertFalse(installer.write_config(installer.fake_configuration(), path))
            self.assertEqual("original", target.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
