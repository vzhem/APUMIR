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
        method = vm.split("private fun showLocalMessages(", 1)[1].split("private fun ownVip(", 1)[0]
        for forbidden in ("reportRead(", "RustBridge.", "getChatById(", "withContext(", "launch("):
            self.assertNotIn(forbidden, method)
        self.assertIn("historyPaging.showLatest(messages)", method)
        self.assertIn("messages = merged", method)
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

    def test_bounded_chat_history_and_mapping_stay_off_the_ui_thread(self):
        history = read("data/repository/DirectChatHistoryStore.kt")
        self.assertIn("observeRecentMessages(chatId, limit)", history)
        self.assertIn("getOlderMessages(chatId, beforeTimestamp, beforeId, limit)", history)
        self.assertEqual(1, history.count(".flowOn(Dispatchers.Default)"))
        self.assertNotIn("RustBridge.", history)
        mapping = read("data/repository/MessageEntityMapping.kt")
        self.assertIn("recipientId = recipientId", mapping)

    def test_chat_entry_window_is_bounded_and_older_pages_are_on_demand(self):
        dao = read("data/local/dao/MessageDao.kt")
        self.assertIn("ORDER BY timestamp DESC, id DESC LIMIT :limit", dao)
        self.assertIn("suspend fun getOlderMessages", dao)
        use_case = read("domain/usecase/GetMessagesUseCase.kt")
        self.assertIn("limit: Int = 100", use_case)
        screen = read("ui/screens/chat/ChatHistoryPagingEffect.kt")
        self.assertIn("firstVisible == 0 && scrolling", screen)
        paging = read("ui/screens/chat/DirectChatHistoryPaging.kt")
        self.assertIn("CHAT_HISTORY_PAGE_SIZE = 100", read("ui/screens/chat/ChatHistoryObserver.kt"))
        self.assertIn("loadOlder(fetch)", paging)

    def test_init_never_touches_properties_declared_after_it(self):
        # Регрессия v11.74.479: init{} синхронно звал refreshAttachmentRights(),
        # а поле rankRights стояло НИЖЕ init. Свойства Kotlin инициализируются
        # по порядку объявления, поэтому открытие любого личного чата падало
        # с NullPointerException до первого кадра. Компилятор и JVM-тесты
        # этого не видят - только порядок строк в файле.
        vm = read("ui/screens/chat/ChatDetailViewModel.kt")
        self.assertLess(
            vm.index("private val rankRights"),
            vm.index("init {"),
            "rankRights must be declared before init: init calls refreshAttachmentRights() synchronously",
        )
        init_block = vm.split("init {", 1)[1].split("}", 1)[0]
        self.assertIn("refreshAttachmentRights()", init_block)

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
        # Плашка «Повторить» вынесена из экрана отдельным файлом по потолку
        # god-файлов (ChatHistoryError.kt); проверяется тот же текст и стиль.
        retry = read("ui/screens/chat/ChatHistoryError.kt")
        self.assertIn("ApuBubbleCard", retry)
        self.assertIn("ApuBubbleTextColor", retry)
        self.assertIn("ApuBubbleMutedColor", retry)
        # Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».
        # Кнопка «Повторить» — фирменная ApuTextAction, а не стоковый TextButton.
        self.assertIn("ApuTextAction(", retry)
        self.assertIn("label = stringResource(R.string.action_retry)", retry)
        self.assertNotIn("TextButton(", retry)
        self.assertNotIn("TextButtonDefaults", retry)


if __name__ == "__main__":
    unittest.main(verbosity=2)
