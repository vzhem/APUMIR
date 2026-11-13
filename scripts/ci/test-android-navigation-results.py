#!/usr/bin/env python3
"""Offline tests for the Android navigation report's fail-closed CI guard."""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "check-android-navigation-results.py"
spec = importlib.util.spec_from_file_location("navigation_results", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
EXPECTED = module.EXPECTED


class NavigationResultGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.results = self.directory / "results"
        self.results.mkdir()
        self.log = self.directory / "gradle.log"
        self.log.write_text("e: example compile error\n", encoding="utf-8")
        self.suite = ET.Element("testsuite")
        for name, tests in EXPECTED.items():
            for case_name in sorted(tests):
                ET.SubElement(self.suite, "testcase", {
                    "classname": f"com.vladimir.messenger.ui.navigation.{name}",
                    "name": case_name,
                })

    def run_guard(self, gradle_status=0):
        ET.ElementTree(self.suite).write(self.results / "report.xml", encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.results), str(self.log), str(gradle_status)],
            text=True, capture_output=True, check=False,
        )

    def test_complete_suite_passes(self):
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("BackButtonsInstrumentedTest=3/3", result.stdout)

    def test_missing_case_does_not_pass(self):
        missing = self.suite[-1].get("name")
        self.suite.remove(self.suite[-1])
        # A repeated report for a different case must not hide the omission.
        self.suite.append(ET.fromstring(ET.tostring(self.suite[-1])))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"{missing}: expected test did not execute", result.stdout)

    def test_failed_and_skipped_cases_are_reported(self):
        for element in ("failure", "error", "skipped"):
            with self.subTest(element=element):
                bad = ET.SubElement(self.suite[0], element, {"message": "not passed"})
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"{element}: not passed", result.stdout)
                self.suite[0].remove(bad)

    def test_compile_failure_is_visible_even_if_no_report_exists(self):
        result = self.run_guard(gradle_status=1)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("e: example compile error", result.stdout)

    def test_malformed_xml_does_not_pass(self):
        (self.results / "broken.xml").write_text("<testsuite", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Invalid Android test report", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
