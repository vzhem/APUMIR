#!/usr/bin/env python3
"""Source contracts for local-first chat opening; not a phone latency benchmark.

The companion check-chat-history.sh runs the actual Kotlin coroutine unit tests.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def read(path):
    return (MAIN / path).read_text()


class ChatStartupTest(unittest.TestCase):
    def test_local_publication_does_not_call_network_or_wait_for_metadata(self):
        vm = read("ui/screens/chat/ChatDetailViewModel.kt")
        method = vm.split("private fun showLocalMessages(", 1)[1].split("private fun isSelfChat(", 1)[0]
        for forbidden in ("reportRead(", "RustBridge.", "getChatById(", "withContext(", "launch("):
            self.assertNotIn(forbidden, method)
        self.assertIn("messages = messages", method)
        self.assertIn("isLoading = false", method)
        self.assertIn("historyError = null", method)
        self.assertIn("onMessages = ::showLocalMessages", vm)

    def test_local_publication_precedes_receipt_request(self):
        observer = read("ui/screens/chat/ChatHistoryObserver.kt")
        collect = observer.split("messages().collect { rows ->", 1)[1].split("catch (cancelled:", 1)[0]
        self.assertLess(collect.index("onMessages(rows)"), collect.index("latestIncoming.value"))
        self.assertNotIn("reportRead(", collect)

    def test_only_one_bounded_serial_receipt_worker_exists(self):
        observer = read("ui/screens/chat/ChatHistoryObserver.kt")
        self.assertIn("MutableStateFlow<String?>(null)", observer)
        self.assertIn("latestIncoming.filterNotNull().collect", observer)
        self.assertNotIn("collectLatest", observer)
        self.assertNotIn("Channel.UNLIMITED", observer)
        vm = read("ui/screens/chat/ChatDetailViewModel.kt")
        self.assertEqual(1, vm.count("readReceipts.reportRead(chatId)"))
        local_mark = vm.split("private fun markAsRead()", 1)[1].split("fun onInputTextChanged", 1)[0]
        self.assertNotIn("reportRead(", local_mark)
        self.assertIn("launch(Dispatchers.IO)", local_mark)

    def test_screen_disposal_is_not_swallowed_as_network_failure(self):
        observer = read("ui/screens/chat/ChatHistoryObserver.kt")
        self.assertEqual(2, observer.count("throw cancelled"))
        repository = read("data/receipt/ReadReceiptRepository.kt")
        self.assertIn("if (it is CancellationException) throw it", repository)

    def test_local_history_and_pins_are_mapped_off_the_ui_thread(self):
        repository = read("data/repository/ChatRepository.kt")
        history = repository.split("fun observeMessages(", 1)[1].split("suspend fun setMessagePinned(", 1)[0]
        self.assertEqual(2, history.count(".flowOn(Dispatchers.Default)"))
        self.assertNotIn("RustBridge.", history)
        mapping = repository.split("private fun MessageEntity.toDomain()", 1)[1].split("suspend fun getMessageById", 1)[0]
        self.assertIn("recipientId = recipientId", mapping)

    def test_database_error_ends_loading_and_can_be_retried(self):
        vm = read("ui/screens/chat/ChatDetailViewModel.kt")
        self.assertIn("it.copy(isLoading = false, historyError =", vm)
        self.assertIn("fun retryMessages() = loadMessages()", vm)
        observer = read("ui/screens/chat/ChatHistoryObserver.kt")
        self.assertIn("historyJob?.cancel()", observer)
        self.assertIn("onLoadError(error)", observer)
        screen = read("ui/screens/chat/ChatDetailScreen.kt")
        self.assertIn("uiState.isLoading && uiState.messages.isEmpty()", screen)
        self.assertIn("uiState.historyError != null && uiState.messages.isEmpty()", screen)
        self.assertEqual(2, screen.count("onRetry = viewModel::retryMessages"))

    def test_retry_uses_shared_bubble_style(self):
        screen = read("ui/screens/chat/ChatDetailScreen.kt")
        retry = screen.split("private fun ChatHistoryError(", 1)[1]
        self.assertIn("ApuBubbleCard", retry)
        self.assertIn("ApuBubbleTextColor", retry)
        self.assertIn("ApuBubbleMutedColor", retry)
        # Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».
        # Кнопка «Повторить» — фирменная ApuTextAction, а не стоковый TextButton.
        self.assertIn("ApuTextAction(", retry)
        self.assertIn('label = "Повторить"', retry)
        self.assertNotIn("TextButton(", retry)
        self.assertNotIn("TextButtonDefaults", retry)


if __name__ == "__main__":
    unittest.main(verbosity=2)
