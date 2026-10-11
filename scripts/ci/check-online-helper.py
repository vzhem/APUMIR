#!/usr/bin/env python3
"""Source wiring guards; behavior belongs to Rust and real Kotlin tests, not these checks."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'android-app/app/src/main'

def source(name):
    return (APP / 'java/com/vladimir/messenger' / name).read_text()

class OnlineHelperWiring(unittest.TestCase):
    def test_consent_precedes_native_start_in_shared_service_worker_path(self):
        text = source('data/RustBridge.kt')
        body = text[text.index('private fun initializeLocked('):text.index('fun shutdown()')]
        self.assertLess(body.index('applyOnlineHelperPolicy(e)'), body.index('val ok = e.start()'))
        self.assertIn('current.configureOnlineHelper(', text)
        self.assertIn('helperRefreshQueued.compareAndSet(false, true)', text)
        self.assertIn('enqueueLifecycle {', text[text.index('fun refreshOnlineHelperPolicy()'):])

    def test_foreground_service_and_settings_refresh_native_policy(self):
        self.assertIn('RustBridge.refreshOnlineHelperPolicy()', source('service/CoreServerService.kt'))
        self.assertIn('RustBridge.refreshOnlineHelperPolicy()', source('data/swarm/SwarmSettings.kt'))
        self.assertIn('RustBridge.refreshOnlineHelperPolicy()', source('data/swarm/OnlineHelperSettings.kt'))
        self.assertNotIn('ServerMode.', source('data/swarm/OnlineHelperSettings.kt'))
        screen = source('ui/screens/settings/SettingsScreen.kt')
        self.assertIn('OnlineHelperSettings.set(context, it)', screen)
        self.assertNotIn('Text(mode.title,', screen)

    def test_tcp_diagnostics_never_slice_unicode_or_log_payload(self):
        core = (ROOT / 'rust-core/src/engine/core.rs').read_text()
        self.assertNotIn('&text[..text.len().min(100)]', core)
        self.assertIn('tracing::debug!("TCP received {} bytes", n)', core)

    def test_ru_en_strings_and_fail_closed_bindings(self):
        for locale in ['values', 'values-en']:
            names = [node.attrib.get('name') for node in ET.parse(APP / 'res' / locale / 'strings.xml').getroot()]
            for name in ['settings_online_helper', 'settings_online_helper_body', 'swarm_mode_normal',
                         'swarm_mode_economy', 'swarm_mode_unlimited']:
                self.assertEqual(names.count(name), 1)
        workflow = (ROOT / '.github/workflows/build-release.yml').read_text()
        self.assertIn('refusing an APK with stale bindings', workflow)
        self.assertNotIn('git checkout -- "$BIND"', workflow)
        self.assertIn('OnlineHelperPolicyTest', workflow)
        self.assertIn('configure_online_helper(', (ROOT / 'rust-core/src/lib.udl').read_text())

if __name__ == '__main__':
    unittest.main(verbosity=2)
