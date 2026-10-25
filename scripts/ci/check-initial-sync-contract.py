#!/usr/bin/env python3
"""Guard the pairing -> initial-sync hand-off.

The first synchronization is deliberately started from the authenticated pairing
completion paths, not from a later heartbeat.  This source contract catches a
regression where pairing still reports success while the initial request is
silently removed or moved behind the UI's ``connected`` state.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RUST_CORE = ROOT / "rust-core/src/engine/core.rs"
RUST = RUST_CORE.read_text()
PAIRING = (JAVA / "data/pairing/DevicePairingController.kt").read_text()
MIRROR = (JAVA / "data/mirror/MirrorSync.kt").read_text()
SERVICE = (JAVA / "service/CoreServerService.kt").read_text()
DEVICES = (JAVA / "ui/screens/devices/DevicesScreen.kt").read_text()


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[begin:finish]


class InitialSyncContract(unittest.TestCase):
    def test_primary_confirmation_starts_initial_sync_before_success_is_reported(self):
        body = section(PAIRING, "fun confirmPrimary()", "private fun sendSyncBootstrap")
        self.assertIn("primaryConfirmPairing(", body)
        self.assertIn("sendSyncBootstrap(", body)
        self.assertIn("MirrorHub.startInitialSync()", body)
        self.assertLess(body.index("sendSyncBootstrap("), body.index("MirrorHub.startInitialSync()"))
        self.assertLess(body.index("MirrorHub.startInitialSync()"), body.index("primaryDoneMutable.value = true"))

    def test_joiner_confirmation_starts_initial_sync_after_authenticated_grant(self):
        body = section(PAIRING, "private fun acceptGrant(", "/** New device: start pairing")
        self.assertIn("JoinerPhase.DONE", body)
        self.assertIn("MirrorHub.startInitialSync()", body)
        self.assertLess(body.index("JoinerPhase.DONE"), body.index("MirrorHub.startInitialSync()"))

    def test_hub_replays_a_pairing_request_when_service_binds_late(self):
        body = section(MIRROR, "object MirrorHub", "    @Volatile\n    @PublishedApi")
        self.assertIn("initialSyncRequested = true", body)
        self.assertIn("initialSyncTracker.begin()", body)
        self.assertIn("current.startInitialSync()", body)
        self.assertIn("if (initialSyncRequested) newChannel.startInitialSync()", body)

    def test_service_starts_channel_before_binding_it(self):
        body = section(SERVICE, "private fun startMirrorChannel(", "private fun mirrorBridge()")
        self.assertLess(body.index("channel.start()"), body.index("MirrorHub.bind(channel)"))

    def test_retry_resends_pairing_bootstrap_before_initial_request(self):
        body = section(PAIRING, "fun retryInitialSync()", "/** \"A1B2-C3D4\"")
        self.assertIn("if (primaryDoneMutable.value) resendGrant()", body)
        self.assertIn("MirrorHub.startInitialSync()", body)

    def test_initial_sync_is_not_blocked_by_swarm_first_fallback_policy(self):
        transport = section(MIRROR, "private fun transportAllowed()", "private fun connect()")
        self.assertIn("initialSyncRequested || pairingGraceActive()", transport)
        self.assertIn("RustBridge.externalTransportAllowed()", transport)
        # Every WebSocket gate must use the pairing-aware decision. Otherwise a
        # healthy direct chat peer can still make initial sync time out.
        self.assertEqual(MIRROR.count("!transportAllowed()"), 5)
        self.assertNotIn("!RustBridge.externalTransportAllowed() || wsRef", MIRROR)
        self.assertNotIn("wsRef.get() !== webSocket || !RustBridge.externalTransportAllowed()", MIRROR)

    def test_initial_sync_has_authenticated_direct_swarm_frames_with_ack_retry(self):
        self.assertIn("setPairingPeer(accountNode, primaryDevice)", PAIRING)
        self.assertIn("setPairingPeer(accountNode, request.joinerDeviceId)", PAIRING)
        self.assertIn("routePairingFrame(text)", PAIRING)
        self.assertIn("RustBridge.liveSwarmPeerIds()", MIRROR)
        self.assertIn("sendPairingPayload(node, device, packet)", MIRROR)
        self.assertIn("pair_ack", MIRROR)
        self.assertIn("PAIRING_FRAME_RETRIES", MIRROR)
        self.assertIn("wsRef.get()?.send(JSONObject(waiting.frame.toString())", MIRROR)
        self.assertIn('"_pid"', MIRROR)

    def test_direct_snapshot_chunks_can_resume_without_finishing_on_first_chunk(self):
        self.assertIn('.put("more", if (more) 1 else 0)', MIRROR)
        self.assertIn('body.optInt("more", 0) != 1', MIRROR)
        self.assertIn("initialContactsSeen", MIRROR)
        self.assertIn("initialFilesSeen", MIRROR)

    def test_pairing_route_is_available_without_a_live_swarm_peer(self):
        self.assertIn("private fun pairingTransportAvailable()", MIRROR)
        send = section(MIRROR, "private fun sendJson", "private fun sendPairingFrame")
        self.assertIn("pairingTransportAvailable()", send)
        self.assertIn("sendPairingFrame(obj)", send)
        self.assertIn("RustBridge.liveSwarmPeerIds()", MIRROR)
        # The native pairing route must not reuse the ordinary external-transport
        # gate: direct discovery is exactly what may be unavailable here.
        pairing = section(RUST, "pub fn send_pairing_payload(", "pub fn send_file_chunk(")
        self.assertIn("send_message_mqtt_oneshot", pairing)
        self.assertNotIn("self.send_message_mqtt(&recipient_node_id, &payload)", pairing)

    def test_pairing_frame_size_fits_the_mqtt_packet_budget(self):
        marker = "private const val MIRROR_PAIRING_MAX_CHARS = "
        value = MIRROR.split(marker, 1)[1].split("\n", 1)[0].strip()
        # 60 KiB leaves room below the native 64 KiB MQTT client limit for
        # packet/topic overhead; larger frames are recursively chunked.
        self.assertEqual(value, "60 * 1024")
        self.assertIn("if (packet.length > PAIRING_FRAME_MAX_CHARS)", MIRROR)
        self.assertIn("sendInitialSnapshotChunk", MIRROR)

    def test_initial_sync_retries_after_timeout_and_waits_for_bootstrap(self):
        self.assertIn("initialSyncRetryJob", MIRROR)
        self.assertIn("initialSyncTimedOut", MIRROR)
        self.assertIn("initialSyncWaitingForBootstrap", MIRROR)
        self.assertIn("scheduleSyncBootstrapFallback", PAIRING)
        # A grant arriving before the sealed bootstrap must not open the old room
        # and report a false transport failure.
        joiner = section(PAIRING, "private fun acceptGrant(", "/** New device: start pairing")
        self.assertIn("initialSyncWaitingForBootstrap()", joiner)
        self.assertNotIn("?: com.vladimir.messenger.data.mirror.MirrorHub.startInitialSync()", joiner)

    def test_fallback_route_checks_pairing_before_deferred_delivery(self):
        body = section(SERVICE, "private suspend fun routeIncomingEnvelope(", "    private suspend fun")
        self.assertLess(body.index("isPairingText"), body.index("deferredDelivery.routeIncoming"))

    def test_ui_exposes_non_connected_initial_sync_states(self):
        for marker in (
            "val initialSync by MirrorHub.initialSync.collectAsStateWithLifecycle()",
            "MirrorInitialSyncPhase.STARTING",
            "MirrorInitialSyncPhase.ERROR",
            "MirrorInitialSyncPhase.NO_DATA",
            "devices_sync_retry",
            "DevicePairingController.retryInitialSync()",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, DEVICES)


if __name__ == "__main__":
    unittest.main(verbosity=2)
