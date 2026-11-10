#!/usr/bin/env python3
"""Контракт раздела «Настройка IPv6» и рекомендаций по покупке IPv6.

Запуск: python3 scripts/ci/check-ipv6-proxy-settings.py
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
RUST = ROOT / "rust-core/src"

SHOP_URL = "https://ru.shopproxy.net/buy-proxy/ipv6/"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class Ipv6ProxySettingsContract(unittest.TestCase):
    def test_pure_jvm_model_and_runtime_have_no_android_sdk_imports(self):
        for rel in ("data/swarm/Ipv6ProxyConfig.kt", "data/swarm/Ipv6ProxyRuntime.kt"):
            src = read(JAVA / rel)
            self.assertNotRegex(
                src,
                r"^import\s+android\.",
                f"{rel} must stay pure JVM so CI runner tests execute without Android stubs",
            )

    def test_recommendation_url_and_any_provider_choice_present(self):
        cfg = read(JAVA / "data/swarm/Ipv6ProxyConfig.kt")
        ui = read(JAVA / "ui/screens/settings/Ipv6NetworkSettingsCard.kt")
        ru = read(RES / "values/strings.xml")
        en = read(RES / "values-en/strings.xml")

        self.assertIn(f'const val IPV6_RECOMMENDED_SHOP_URL = "{SHOP_URL}"', cfg)
        self.assertIn("IPV6_RECOMMENDED_SHOP_URL", ui)
        self.assertIn("R.string.section_ipv6_network", ui)
        self.assertIn("R.string.section_ipv6_recommendations", ui)
        self.assertIn("R.string.ipv6_rec_any_provider_note", ui)
        self.assertIn(SHOP_URL, ru)
        self.assertIn(SHOP_URL, en)
        self.assertIn("любой другой ресурс", ru)
        self.assertIn("any other provider", en)

    def test_settings_screen_renders_section_inside_network_category(self):
        settings = read(JAVA / "ui/screens/settings/SettingsScreen.kt")
        match = re.search(
            r"if \(section == SettingsSection\.Network\)\s*\{(.*?)if \(section == SettingsSection\.Files\)",
            settings,
            re.S,
        )
        self.assertIsNotNone(match, "Network section block not found in SettingsScreen.kt")
        self.assertIn("Ipv6NetworkSettingsSection()", match.group(1))

    def test_startup_autopilot_and_rust_engine_hooks_are_wired(self):
        app = read(JAVA / "MessengerApplication.kt")
        mon = read(JAVA / "service/NetworkMonitor.kt")
        autopilot = read(JAVA / "service/ProxyAutopilot.kt")
        vm = read(JAVA / "ui/screens/settings/SettingsViewModel.kt")
        reset = read(JAVA / "data/settings/SettingsResetStore.kt")

        self.assertIn("Ipv6NetworkSettings.applyToCore(this)", app)
        self.assertIn("Ipv6NetworkSettings.applyToCore(context)", mon)
        self.assertGreaterEqual(autopilot.count("Ipv6NetworkSettings.isActive(appContext)"), 2)
        self.assertIn("Ipv6NetworkSettings.isActive(context)", vm)
        self.assertIn("Ipv6NetworkSettings.setEnabled(context, false)", reset)

        lib_rs = read(RUST / "lib.rs")
        multi_rs = read(RUST / "network/multi_broker.rs")
        mqtt_rs = read(RUST / "network/mqtt_transport.rs")
        socks_rs = read(RUST / "network/socks5.rs")

        self.assertGreaterEqual(lib_rs.count("note_broker_settings_changed()"), 2)
        self.assertIn("mqtt_socks5_proxy_config()", multi_rs)
        self.assertIn("!has_proxy", mqtt_rs)
        self.assertIn("Ipv6Addr", socks_rs)

    def test_proxy_reapplication_does_not_masquerade_as_a_network_change(self):
        settings = read(JAVA / "data/swarm/Ipv6NetworkSettings.kt")
        runtime = read(JAVA / "data/swarm/Ipv6ProxyRuntime.kt")
        self.assertIn("RustBridge.onForegroundRecovery()", settings)
        self.assertNotIn("RustBridge.onNetworkAvailable()", settings)
        self.assertIn("if (changed) sink.wakeNetwork()", runtime)
        self.assertIn("if (changed || !reuseBridge) sink.wakeNetwork()", runtime)
        self.assertIn("sink.setSocks5Proxy(config.host, config.port", runtime)

    def test_unit_test_file_exists(self):
        test_src = read(TEST / "data/swarm/Ipv6NetworkProxyTest.kt")
        self.assertIn("httpConnectBridgeTunnelsSocks5TrafficWithProxyAuthorization", test_src)
        self.assertIn("parsesAllCommonShopFormatsIncludingIpv6AndComplexPasswords", test_src)
        self.assertIn("reapplyingSocksProxyKeepsConnectionsButStillSetsTheNativeProxy", test_src)
        self.assertIn("reapplyingHttpProxyReusesBridgeWithoutWakingConnections", test_src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
