#!/usr/bin/env python3
"""Pin notice source wiring; real policy tests run via check-chat-history.sh.

This is not a phone/rendering test. It also protects atomic quota enforcement.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def source(path):
    return (MAIN / path).read_text()


class PinNoticeWiringTest(unittest.TestCase):
    def test_topics_list_suppresses_only_pin_limit_notice(self):
        screen = source("ui/screens/groups/GroupChatScreen.kt")
        self.assertIn("uiState.error, uiState.pinned.size, inMessageFeed = !showTopicsList", screen)
        self.assertIn("if (displayedError != null)", screen)
        self.assertNotIn("if (uiState.error != null)", screen)

    def test_live_topic_pins_clear_resolved_error_in_state(self):
        vm = source("ui/screens/groups/GroupChatViewModel.kt")
        observer = vm.split("private fun observePinned(topicId:", 1)[1].split("fun selectTopic", 1)[0]
        self.assertIn("state.selectedTopicId != topicId", observer)
        self.assertIn("error = MessagePinPolicy.visibleError(state.error, list.size)", observer)
        self.assertIn("pinned = list", observer)

    def test_both_manual_and_automatic_topic_changes_clear_stale_warning(self):
        vm = source("ui/screens/groups/GroupChatViewModel.kt")
        self.assertIn("error = if (changed) MessagePinPolicy.visibleError(state.error, 0) else state.error", vm)
        manual = vm.split("fun selectTopic(", 1)[1].split("suspend fun inviteQrLink", 1)[0]
        self.assertIn("error = MessagePinPolicy.visibleError(it.error, 0)", manual)
        self.assertIn("pinned = emptyList()", manual)

    def test_delayed_pin_failure_cannot_leak_into_another_topic(self):
        vm = source("ui/screens/groups/GroupChatViewModel.kt")
        action = vm.split("fun togglePin(", 1)[1].split("fun deleteMessageForMe", 1)[0]
        self.assertIn("val topicAtRequest = _uiState.value.selectedTopicId", action)
        self.assertIn("state.selectedTopicId != topicAtRequest", action)
        self.assertIn("error.message == MessagePinPolicy.LIMIT_REACHED_MESSAGE", action)

    def test_personal_chat_and_channel_follow_the_same_live_policy(self):
        chat = source("ui/screens/chat/ChatDetailViewModel.kt")
        self.assertIn("error = MessagePinPolicy.visibleError(it.error, pinned.size)", chat)
        channel = source("ui/screens/channels/ChannelViewModel.kt")
        self.assertIn("error = MessagePinPolicy.visibleError(it.error, snapshot.posts.count { post -> post.isPinned })", channel)
        channel_ui = source("ui/screens/channels/ChannelScreen.kt")
        self.assertIn("MessagePinPolicy.visibleError(uiState.error, uiState.pinnedPostIds.size)?.let", channel_ui)

    def test_personal_snackbar_cancels_when_notice_becomes_ineligible(self):
        screen = source("ui/screens/chat/ChatDetailScreen.kt")
        self.assertIn("val displayedError = MessagePinPolicy.visibleError(uiState.error, uiState.pinned.size)", screen)
        self.assertIn("LaunchedEffect(displayedError)", screen)
        self.assertNotIn("LaunchedEffect(uiState.error)", screen)

    def test_pin_sends_do_not_block_the_ui_thread(self):
        for path, method, following in (
            ("ui/screens/groups/GroupChatViewModel.kt", "fun togglePin(", "fun deleteMessageForMe"),
            ("ui/screens/chat/ChatDetailViewModel.kt", "fun togglePin(", "private fun loadMessages"),
            ("ui/screens/channels/ChannelViewModel.kt", "fun togglePostPin(", "private fun observeReactions"),
        ):
            action = source(path).split(method, 1)[1].split(following, 1)[0]
            self.assertIn("viewModelScope.launch(Dispatchers.IO)", action)

    def test_quota_and_permission_checks_remain_in_repository_and_transaction(self):
        policy = source("data/local/MessagePinPolicy.kt")
        self.assertIn("const val MAX_PINNED_PER_SCOPE = 10", policy)
        repository = source("data/group/GroupRepository.kt")
        action = repository.split("suspend fun setPinned(", 1)[1].split("suspend fun peekTopicUnread", 1)[0]
        self.assertIn("GroupPermissions.canPinMessages", action)
        self.assertIn("messageDao.updatePinnedWithinTopicLimit", action)
        self.assertIn("MessagePinMutation.LIMIT_REACHED", action)
        dao = source("data/local/dao/MessageDao.kt")
        self.assertIn("@Transaction\n    suspend fun updatePinnedWithinTopicLimit", dao)
        self.assertIn("@Transaction\n    suspend fun updatePinnedWithinChatLimit", dao)
        self.assertIn("@Transaction\n    suspend fun updatePinnedChannelPostWithinLimit", dao)


if __name__ == "__main__":
    unittest.main(verbosity=2)
