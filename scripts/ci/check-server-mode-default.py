#!/usr/bin/env python3
"""Guard the opt-in server-mode default and its diagnostic/reset wiring."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def source(relative: str) -> str:
    return (MAIN / relative).read_text()


class ServerModeDefaultContract(unittest.TestCase):
    def test_server_mode_defaults_off_without_replacing_saved_choice(self):
        policy = source("data/swarm/ServerModePolicy.kt")
        store = source("data/swarm/ServerMode.kt")
        self.assertRegex(policy, r"const val DEFAULT_ENABLED\s*=\s*false")
        self.assertIn("fun enabled(storedValue: Boolean?): Boolean = storedValue ?: DEFAULT_ENABLED", policy)
        self.assertIn("private val _enabled = MutableStateFlow(ServerModePolicy.DEFAULT_ENABLED)", store)
        self.assertIn("preferences.contains(KEY)", store)
        self.assertIn("ServerModePolicy.enabled(stored)", store)
        self.assertNotIn("prefs(context).edit().clear()", store)

    def test_reset_and_diagnostics_share_the_opt_in_default(self):
        reset = source("data/settings/SettingsResetStore.kt")
        diagnostics = source("data/diagnostics/TransferDiagnostics.kt")
        self.assertIn("ServerMode.set(context, ServerModePolicy.DEFAULT_ENABLED)", reset)
        self.assertIn("getOrDefault(ServerModePolicy.DEFAULT_ENABLED)", diagnostics)

    def test_online_helper_remains_an_independent_setting(self):
        helper = source("data/swarm/OnlineHelperSettings.kt")
        self.assertIn("Independent opt-out", helper)
        self.assertIn("never changes ServerMode", helper)
        self.assertIn('private const val KEY = "online_helper_enabled"', helper)


if __name__ == "__main__":
    unittest.main(verbosity=2)
