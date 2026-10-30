#!/usr/bin/env python3
"""Контракт прочтения темы группы.

Владелец 2026-10-30 (скрин списка тем с SOS): «только начал смотреть темы, а
сообщения сразу отмечаются прочитанными, хотя внутрь темы ещё не заходил».

Причина: при показе списка тем модель сама выбирала первую тему (General),
запускала её ленту и на каждом обновлении снимала непрочитанное.

Правило теперь: прочитанным считается только то, чья лента открыта на экране
и приложение на переднем плане (`TopicReadingPolicy`). Список тем, свёрнутое
приложение и автовыбор темы ничего не прочитывают.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GROUPS = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/groups"


def read(path):
    return path.read_text(encoding="utf-8")


class TopicReadContractTest(unittest.TestCase):
    def setUp(self):
        self.vm = read(GROUPS / "GroupChatViewModel.kt")
        self.screen = read(GROUPS / "GroupChatScreen.kt")
        self.policy = read(GROUPS / "TopicReadingPolicy.kt")

    def test_the_rule_lives_in_a_pure_verifiable_file(self):
        self.assertIn("object TopicReadingPolicy", self.policy)
        self.assertIn("fun shouldMarkRead(", self.policy)
        for android_import in ("import android.", "import androidx."):
            self.assertNotIn(android_import, self.policy)

    def test_the_feed_does_not_mark_anything_read_without_visible_topic(self):
        collect = self.vm.split("observeTopicMessages(groupId, topicId).collect", 1)[1]
        guard = collect.index("feedVisibility.canMarkRead(topicId)")
        mark = collect.index("groupRepository.markRead(groupId, topicId)")
        self.assertLess(guard, mark, "проверка видимости обязана идти ДО прочтения")
        self.assertIn("return@collect", collect[:mark])

    def test_the_screen_reports_what_the_person_really_sees(self):
        reporter = read(GROUPS / "TopicFeedVisibilityReporter.kt")
        self.assertIn(
            "TopicFeedVisibilityReporter(viewModel, showTopicsList, uiState.selectedTopicId)",
            self.screen,
        )
        self.assertIn("LocalLifecycleOwner.current", reporter)
        self.assertIn("Lifecycle.State.RESUMED", reporter)
        self.assertIn("topicsListOnScreen || !resumed", reporter)
        self.assertIn("onDispose { viewModel.setFeedVisible(null, resumed = false) }", reporter)

    def test_the_automatic_topic_pick_cannot_clear_unread(self):
        # Автовыбор остаётся (лента нужна для отправки), но прочтение от него
        # больше не зависит: решает видимость, а не selectedTopicId.
        self.assertIn("observeMessages(selected)", self.vm)
        self.assertIn("private val feedVisibility = TopicFeedVisibility()", self.vm)
        self.assertIn("class TopicFeedVisibility", read(GROUPS / "TopicFeedVisibility.kt"))

    def test_no_other_path_marks_topics_read(self):
        repo = read(
            ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/group/GroupRepository.kt"
        )
        vm_marks = self.vm.count("groupRepository.markRead(")
        self.assertEqual(1, vm_marks, "прочтение темы должно быть в одном месте")

    def test_the_rule_has_a_unit_test(self):
        test = read(
            ROOT
            / "android-app/app/src/test/java/com/vladimir/messenger/ui/screens/groups/TopicReadingPolicyTest.kt"
        )
        self.assertIn("the topics list alone never marks anything read", test)
        self.assertIn("an auto selected topic is not read while the list is on screen", test)

    def test_the_visibility_state_has_a_unit_test(self):
        test = read(
            ROOT
            / "android-app/app/src/test/java/com/vladimir/messenger/ui/screens/groups/TopicFeedVisibilityTest.kt"
        )
        self.assertIn("`the topics list alone reads nothing`", test)
        self.assertIn("`opening a topic asks the feed to restart`", test)
        self.assertIn("`returning from the background reads the open topic`", test)

    def test_contract_is_wired_into_release_build(self):
        workflow = read(ROOT / ".github/workflows/build-release.yml")
        self.assertIn("python3 scripts/ci/check-topic-read-contract.py", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
