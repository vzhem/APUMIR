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
        for text in ('verifyIdentitySigningBinding', 'MAX_PACKET', 'MAX_RECORDS', 'take(16)', '300_000', 'received > 60'):
            self.assertIn(text, network)
        wire = (SRC / 'data/heartgame/HeartGameWire.kt').read_text()
        self.assertIn('Ed25519.verify', wire)
        self.assertIn('thenBy { it.keyId }', wire)
        ephemeral = (SRC / 'data/security/EphemeralControl.kt').read_text()
        self.assertIn('text.startsWith("APUTYP1|APUGAME1|")', ephemeral)
        mirror = (SRC / 'data/mirror/MirrorSync.kt').read_text()
        self.assertIn('text.startsWith(com.vladimir.messenger.data.heartgame.HeartGameWire.PREFIX)', mirror)

if __name__ == '__main__':
    unittest.main(verbosity=2)
