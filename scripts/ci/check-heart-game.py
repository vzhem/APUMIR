#!/usr/bin/env python3
"""Wiring contracts supplement, not replace, compiled policy/signature tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'android-app/app/src/main/java/com/vladimir/messenger'

class HeartGameContracts(unittest.TestCase):
    def test_one_global_overlay_and_three_receive_paths(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertEqual(main.count('HeartGameOverlay(heartNetwork)'), 1)
        core = (SRC / 'service/CoreServerService.kt').read_text()
        self.assertEqual(core.count('heartGameNetwork.routeIncoming(text)'), 3)

    def test_foreground_only_and_single_click(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        for text in ('repeatOnLifecycle(Lifecycle.State.RESUMED)', 'finally', 'policy.cancel()', 'enabled = !busy',
                     'HeartGameStore.catchHeart', 'rotationZ', 'alpha = (1f - progress.value)', '"+1"'):
            self.assertIn(text, ui)
        self.assertNotIn('pointerInput', ui)  # No full-screen gesture interception.

    def test_plus_one_keeps_original_size_then_grows_and_floats_up(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        self.assertIn('val scale = 1f + .42f * grow', ui)
        self.assertIn('translationY = -32.dp.toPx() * progress', ui)
        self.assertIn('scaleX = scale', ui)
        self.assertIn('scaleY = scale', ui)

    def test_main_header_shows_score_and_leaderboard_place_under_rank(self):
        chat_list = (SRC / 'ui/screens/chat/ChatListScreen.kt').read_text()
        self.assertIn('HeartGameHeaderStatus(', chat_list)
        self.assertIn('modifier = Modifier.padding(start = 32.dp)', chat_list)
        header = (SRC / 'ui/components/HeartGameSettings.kt').read_text()
        self.assertIn('if (!enabled) return', header)
        self.assertIn('heart_game_header_place', header)
        self.assertIn('HeartGameStore.board(context, id)', header)
        self.assertIn('HeartGameStore.score(context, id)', header)

    def test_private_account_scoped_score_and_opt_in(self):
        store = (SRC / 'data/heartgame/HeartGameStore.kt').read_text()
        self.assertIn('heart-game-v1-$id', store)
        self.assertIn('getBoolean("enabled", false)', store)
        self.assertIn('policy.claim(', store)
        self.assertIn('.commit()', store)
        self.assertNotIn('HeartRepository', store)
        self.assertNotIn('ReferralRankStore', store)

    def test_bounded_signed_network_not_fake_global_table(self):
        network = (SRC / 'data/heartgame/HeartGameNetwork.kt').read_text()
        for text in ('verifyIdentitySigningBinding', 'MAX_PACKET', 'MAX_RECORDS', 'MAX_TARGETS_PER_ROUND',
                     'HeartSyncPolicy.isDue', 'HeartSyncPrefs.markGlobal', 'received > 60', 'canSendNow()'):
            self.assertIn(text, network)
        # Синхронизация не зависит от включённой игры и не держит отсчёт в памяти.
        self.assertNotIn('HeartGameStore.enabled', network)
        self.assertNotIn('lastSend', network)

    def test_sync_runs_when_game_disabled_and_rarely(self):
        policy = (SRC / 'data/heartgame/HeartSyncPolicy.kt').read_text()
        self.assertIn('const val SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000', policy)
        self.assertIn('fun isPhoneFree(', policy)
        self.assertIn('fun newcomerAllowed(', policy)
        prefs = (SRC / 'data/heartgame/HeartSyncPrefs.kt').read_text()
        self.assertIn('last_global_ms', prefs)
        self.assertIn('getSharedPreferences(PREFS', prefs)
        core = (SRC / 'service/CoreServerService.kt').read_text()
        self.assertIn('heartGameNetwork.broadcastIfNeeded()', core)
        self.assertIn('heartGameNetwork.syncWithNewcomer(existing.id)', core)

    def test_overlay_no_longer_broadcasts_every_minute(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        self.assertNotIn('broadcastIfNeeded', ui)
        self.assertNotIn('delay(60_000)', ui)
        wire = (SRC / 'data/heartgame/HeartGameWire.kt').read_text()
        self.assertIn('Ed25519.verify', wire)
        self.assertIn('thenBy { it.keyId }', wire)
        ephemeral = (SRC / 'data/security/EphemeralControl.kt').read_text()
        self.assertIn('text.startsWith("APUTYP1|APUGAME1|")', ephemeral)
        mirror = (SRC / 'data/mirror/MirrorSync.kt').read_text()
        self.assertIn('text.startsWith(com.vladimir.messenger.data.heartgame.HeartGameWire.PREFIX)', mirror)

if __name__ == '__main__':
    unittest.main(verbosity=2)
