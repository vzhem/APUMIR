#!/usr/bin/env python3
"""Source wiring guards for identity, presence and process-local wake recovery.

The behavioral tests run on the actual Kotlin/Rust implementations. These
checks catch integration regressions; they do not replace two-phone testing.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RUST = ROOT / "rust-core/src"


def read(root, name):
    return (root / name).read_text(encoding="utf-8")


def section(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]


class PresenceWiring(unittest.TestCase):
    def test_original_observation_time_reaches_the_contact_and_rating(self):
        service = read(APP, "service/CoreServerService.kt")
        handler = section(service, '"peer_discovered" ->', '"peer_lost" ->')
        self.assertIn("presence.observe(peerId, existing?.id, event.timestamp)", handler)
        self.assertIn("nowMs = observation.seenAtMs", handler)
        self.assertIn("presence.start(serviceScope)", service)
        ffi = read(RUST, "lib.rs")
        self.assertIn("ffi.timestamp = Some(observed_at_ms)", ffi)
        dao = read(APP, "data/local/dao/ContactDao.kt")
        self.assertIn("seenAtMs > COALESCE(lastSeenAtMs, 0)", dao)
        kotlin = read(APP, "service/PresenceRecovery.kt")
        rust = read(RUST, "network/presence_scope.rs")
        self.assertIn("const val TTL_MS = 200_000L", kotlin)
        self.assertIn("pub const LIVE_PRESENCE_TTL_MS: i64 = 200_000", rust)
        self.assertIn("const val MAX_CLOCK_SKEW_MS = 60_000L", kotlin)
        self.assertIn("pub const MAX_FUTURE_SKEW_MS: i64 = 60_000", rust)

    def test_gossip_invite_and_old_broker_cache_are_not_online(self):
        core = read(RUST, "engine/core.rs")
        invite = section(core, "pub fn connect_via_invite", "fn replay_file_session_ingress")
        self.assertNotIn("CoreEvent::PeerDiscovered", invite)
        gossip = section(core, 'evt.topic.starts_with("p2pm2/gossip/broadcast")',
                         'evt.topic.starts_with("p2pm2/msg/")')
        self.assertNotIn("CoreEvent::PeerDiscovered", gossip)
        observe = read(RUST, "network/presence_observation.rs")
        self.assertIn("if !live { return PresenceOutcome::Candidate; }", observe)
        self.assertIn("remember_locked(&mut map, book", observe)

    def test_audience_tracks_fingerprints_and_failed_seed_retries(self):
        service = read(APP, "service/CoreServerService.kt")
        self.assertIn("PresenceAudienceObserver.start(", service)
        self.assertIn("RustBridge.setPresenceAudienceOrThrow(ids)", service)
        observer = read(APP, "service/PresenceAudienceObserver.kt")
        for marker in ("it.id to it.fingerprint", "distinctUntilChanged()", "collectLatest", "delay(retryMs)"):
            self.assertIn(marker, observer)
        directory = read(APP, "data/swarm/SwarmPeerDirectory.kt")
        self.assertIn('contact.fingerprint.takeIf { it.startsWith("pk_") }', directory)
        bridge = read(APP, "data/RustBridge.kt")
        setter = section(bridge, "internal fun setPresenceAudienceOrThrow", "fun importAddressBookJson")
        self.assertIn("checkNotNull(engine)", setter)
        self.assertNotIn("ids.isEmpty()", setter)

    def test_sidecar_and_engine_start_share_a_guard_and_preserve_saved_route(self):
        bridge = read(APP, "data/RustBridge.kt")
        startup = section(bridge, "private fun initializeLocked(", "fun shutdown()")
        self.assertIn("publicationLock = IdentitySigningKeyStore", bridge)
        for marker in ("engineSlot.startNew(", "matchesInstalledRoute(existingPublicKey)",
                       "matchesRunning(existingPublicKey, actual)"):
            self.assertIn(marker, startup)
        slot = read(APP, "data/CoreEngineSlot.kt")
        self.assertLess(slot.index("synchronized(publicationLock)"), slot.index("!allow()"))
        self.assertLess(slot.index("!allow()"), slot.index("starting = true"))
        signing = read(APP, "data/security/IdentitySigningKeyStore.kt")
        install = section(signing, "fun installIntoCore(", "fun existingVerifiedBinding(")
        self.assertIn("RustBridge.signingRouteCompatible(legacyRoutingNodeId)", install)
        self.assertIn("installedRoutingId = legacyRoutingNodeId", install)
        self.assertRegex(signing, r"@Synchronized\s+fun installIntoCore\(")
        service = read(APP, "service/CoreServerService.kt")
        worker = read(APP, "worker/RelayWakeWorker.kt")
        self.assertIn("ExistingRoutingIdentity.resolve(", service)
        self.assertIn("ExistingRoutingIdentity.resolve(", worker)
        self.assertIn('.putString("existing_private_key", existingPrivKey)', service)

    def test_same_name_merges_require_pairwise_proof_and_a_history_destination(self):
        contacts = read(APP, "data/repository/ContactRepository.kt")
        merge = section(contacts, "private suspend fun mergeDuplicateContacts()", "// ── р234:")
        self.assertIn("VerifiedContactGroups.partition", merge)
        self.assertIn(".areSameVerifiedDevice(appContext, a, b)", merge)
        self.assertIn("if (!destinationReady) continue", merge)
        self.assertLess(merge.index("chatRepository.absorbChatOf"), merge.index("contactDao.deleteContact"))
        self.assertNotIn("chatRepository.deleteChatsOf(dup.id)", merge)
        nickname = read(APP, "data/repository/NicknameIdentity.kt")
        migration = section(nickname, "// Without an existing destination contact", "\n    }\n}")
        self.assertIn("if (contact != null)", migration)
        self.assertIn("filter { it != contact.id }", migration)
        self.assertLess(migration.index("chatRepository.getOrCreateChat"), migration.index("chatRepository.absorbChatOf"))
        self.assertLess(migration.index("chatRepository.absorbChatOf"), migration.index("contactRepository.deleteContact"))
        chats = read(APP, "data/repository/ChatRepository.kt")
        absorb = section(chats, "suspend fun absorbChatOf(", "/** Удалить чат")
        self.assertIn("checkNotNull(chatDao.getChatByContactId(newContactId))", absorb)
        service = read(APP, "service/CoreServerService.kt")
        self.assertIn("error != null && contactRepository.getContactByFingerprint(peerId) == null", service)
        device = read(APP, "data/security/DeviceIdentityCoordinator.kt")
        self.assertIn("error != null && contactRepository.getContactByFingerprint(newOwnerId) == null", device)
        self.assertIn("seenReplacements.remove(dedupKey)", device)

    def test_wake_uses_process_liveness_not_old_history(self):
        keep = read(APP, "service/EmergencyKeepAlive.kt")
        worker = read(APP, "worker/ServiceWatchdogWorker.kt")
        self.assertIn("DeliveryAssurance.currentProcessLastAliveMs()", keep)
        self.assertIn("DeliveryAssurance.currentProcessLastAliveMs()", worker)
        self.assertNotIn("shouldWakeNow(status.conditions, status.lastAliveMs", keep + worker)
        stopped = section(keep, "fun onServiceStopped", "fun onTaskRemoved")
        self.assertIn("if (!DeliveryAssurance.serviceStopped(owner))", stopped)
        self.assertIn("DeliveryWakePolicy.afterRefusal(", stopped)
        receiver = section(keep, "class EmergencyKeepAliveReceiver", "object EmergencyKeepAlive")
        self.assertNotIn("invalidateLastAlive()", receiver)

    def test_network_monitor_uses_default_network_and_serialized_actions(self):
        monitor = read(APP, "service/NetworkMonitor.kt")
        self.assertIn("registerDefaultNetworkCallback(networkCallback)", monitor)
        self.assertNotIn("registerNetworkCallback(request", monitor)
        self.assertIn("DefaultNetworkState<Network>()", monitor)
        self.assertIn("DefaultNetworkActions(callbackScope", monitor)
        self.assertIn("currentCoroutineContext().ensureActive()", monitor)
        self.assertIn("actions.stop()", monitor)
        worker = read(APP, "service/DefaultNetworkActions.kt")
        self.assertIn("Channel.CONFLATED", worker)
        self.assertIn("for (signal in wake)", worker)
        self.assertIn("pending.also { pending = null }", worker)
        self.assertIn("recordFailure(error)", worker)
        self.assertIn("DefaultNetworkState.Action.POLICY", monitor)
        manifest = read(ROOT / "android-app/app/src/main", "AndroidManifest.xml")
        self.assertNotIn(".service.NetworkChangeReceiver", manifest)

    def test_foreground_hint_is_bounded_and_non_destructive(self):
        activity = read(APP, "MainActivity.kt")
        recovery = read(APP, "service/ForegroundRecovery.kt")
        self.assertIn("ForegroundRecovery.onBackground()", activity)
        self.assertIn("ForegroundRecovery.onForeground(lifecycleScope)", activity)
        self.assertIn("SystemClock.elapsedRealtime()", recovery)
        self.assertIn("minimumBackgroundMs: Long = 60_000L", recovery)
        self.assertIn("Dispatchers.IO", recovery)
        self.assertIn("RustBridge.onForegroundRecovery()", recovery)
        self.assertNotIn("onNetworkAvailable", recovery)
        native = read(RUST, "engine/foreground_recovery.rs")
        self.assertIn("AnnounceAfterIdle", native)
        self.assertIn("allow_resume_beacon", native)
        self.assertNotIn("network_changed()", native)
        core = read(RUST, "engine/core.rs")
        self.assertIn("if resume { None } else", core)
        self.assertIn("boolean on_foreground_recovery();", read(RUST, "lib.udl"))
        broker = read(RUST, "engine/broker_settings.rs")
        self.assertIn("if changed {", broker)
        self.assertIn("notify_changed();", broker)
        self.assertIn("eq_ignore_ascii_case", broker)
        self.assertIn("self.set_own_broker_with(text", core)
        multicast = read(APP, "service/CoreMulticastLock.kt")
        self.assertIn("DeliveryCadence.foregroundChanges.collect", multicast)
        self.assertIn("if (closed) return", multicast)
        self.assertIn("closed = true", multicast)

    def test_dispatch_is_separate_bounded_and_mdns_round_is_not_persistent(self):
        dispatch = read(RUST, "network/presence_dispatch.rs")
        for marker in ("fn spawn_periodic(", "MAX_OWN_PER_ROUND", "PARALLEL_SENDS: usize = 4",
                       "struct BatchGuard", "tokio::time::timeout"):
            self.assertIn(marker, dispatch)
        core = read(RUST, "engine/core.rs")
        self.assertIn("PresenceDispatcher::spawn_periodic(", core)
        mdns = read(RUST, "network/mdns.rs")
        discover = section(mdns, "pub async fn discover(", "pub async fn unpublish(")
        self.assertIn("DiscoveryRound::default()", discover)
        self.assertIn("Ok(discovered.finish())", discover)
        self.assertNotIn("self.discovered", discover)


if __name__ == "__main__":
    unittest.main(verbosity=2)
