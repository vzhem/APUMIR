#!/usr/bin/env python3
"""Contract: voice messages start only from an explicit user tap.

Android MediaPlayer.setPlaybackParams() can start a prepared/paused player on
some devices. The UI must not push playback params while idle, and must not
start playback from composition, visibility, preparation or speed changes.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
VOICE = MAIN / "ui/components/VoiceMessage.kt"


class VoicePlaybackContract(unittest.TestCase):
    def test_speed_effect_is_gated_by_active_playback(self):
        text = VOICE.read_text(encoding="utf-8")
        self.assertIn("LaunchedEffect(player, playing, playbackSpeed)", text)
        self.assertIn("VoicePlaybackPolicy.shouldApplyPlaybackParams(playing)", text)
        self.assertNotIn("LaunchedEffect(player, playbackSpeed)", text)
        self.assertIn("if (!player.isPlaying) return@runCatching", text)

    def test_start_is_only_in_the_play_button_handler(self):
        text = VOICE.read_text(encoding="utf-8")
        starts = [m.start() for m in re.finditer(r"\bp\.start\(\)", text)]
        self.assertEqual(1, len(starts), "voice playback must have exactly one explicit start")
        click = text.index("Modifier.clickable(enabled = player != null)")
        self.assertGreater(starts[0], click)
        # The start must happen before applying speed, never the other way around.
        speed = text.index("applyVoicePlaybackSpeed(p, playbackSpeed)")
        self.assertLess(starts[0], speed)
        for forbidden in ("autoPlay", "autoplay", "startWhenVisible", "playWhenReady"):
            self.assertNotIn(forbidden, text)

    def test_policy_has_jvm_tests(self):
        test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/voice/VoicePlaybackPolicyTest.kt").read_text()
        self.assertIn("onlyAnExplicitUserTapMayStartPlayback", test)
        self.assertIn("playbackParamsAreAppliedOnlyWhileAlreadyPlaying", test)


if __name__ == "__main__":
    unittest.main(verbosity=2)
