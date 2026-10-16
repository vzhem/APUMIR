#!/usr/bin/env python3
"""Source wiring checks; renderer and interaction tests require an Android device."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'android-app/app/src/main/java/com/vladimir/messenger'

class HeaderContracts(unittest.TestCase):
    def test_name_wraps_and_header_is_not_fixed_height(self):
        text = (MAIN / 'ui/components/ConversationHeader.kt').read_text()
        self.assertIn('Modifier.weight(1f)', text)
        self.assertIn('softWrap = true', text)
        self.assertNotIn('TextOverflow.Ellipsis', text)
        self.assertNotIn('maxLines =', text)
        self.assertNotIn('TopAppBar(', text)
        self.assertIn('WindowInsets.safeDrawing', text)
        self.assertEqual(2, text.count('ApuHeaderIconBubble('))
        self.assertIn('ApuActionsMenu(expanded = expanded', text)

    def test_existing_actions_and_guards_are_preserved(self):
        text = (MAIN / 'ui/screens/chat/ChatDetailScreen.kt').read_text().split('topBar = {')[1].split('bottomBar = {')[0]
        self.assertIn('ConversationHeader(', text)
        self.assertNotIn('ApuHeaderIconBubble(', text)
        for action in ('action_search', 'menu_call', 'menu_video_call', 'chat_rename',
                       'conversation_wallpaper', 'menu_mute', 'showPeerProfile = true', 'onBack = onBackClick'):
            self.assertIn(action, text)
        self.assertEqual(2, text.count('enabled = canCall'))
        self.assertIn('contactId.isNotBlank()', text)
        self.assertIn('searchQuery = ""', text)
        self.assertIn('uiState.isPeerTyping', text)
        self.assertIn('AvatarStore.avatars', text)

    def test_renderer_regressions_exist(self):
        test = (ROOT / 'android-app/app/src/androidTest/java/com/vladimir/messenger/ui/components/ConversationHeaderInstrumentedTest.kt').read_text()
        for part in ('320.dp', '2f', 'hasVisualOverflow', 'isLineEllipsized', 'enabled = false', 'assertDoesNotExist'):
            self.assertIn(part, test)

if __name__ == '__main__':
    unittest.main(verbosity=2)
