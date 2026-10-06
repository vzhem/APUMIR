#!/usr/bin/env python3
"""Контракты отчёта «Логи»: приватность, разделы, сводка и окно в стиле APU.

Это не скриншот-тест и не компилятор: проверяются ИМЕНА, тексты и вызовы в
исходниках. Зачем отдельная проверка: отчёт уезжает владельцу в чат, поэтому
нельзя молча потерять обещание приватности, раздел «сводка» или кнопку
копирования. Ровно те ошибки уже случались в проекте (см. журнал: сборки
v11.74.171 падали на необъявленных именах) — здесь ловится класс «вызов
исчез из кода», а не «скриншот стал другим».
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
DIAG = MAIN / "data/diagnostics"
SCREEN = MAIN / "ui/screens/settings/SettingsScreen.kt"
VIEW_MODEL = MAIN / "ui/screens/settings/SettingsViewModel.kt"
DIAG_UI = MAIN / "ui/components/ApuDiagnosticsUi.kt"

# Файлы-хуки: без них отчёт снова станет «голословным» (журнал будет пуст).
HOOK_FILES = (
    "data/file/FileTransferSender.kt",
    "data/file/FileTransferReceiver.kt",
    "data/file/FileTransferRouter.kt",
    "data/file/OutgoingFilePreparationService.kt",
    "service/CoreServerService.kt",
    "service/NetworkMonitor.kt",
    "service/NetworkChangeReceiver.kt",
    "MessengerApplication.kt",
)

# Что нельзя передавать в диагностику: имена файлов, содержимое, ключи, адреса.
FORBIDDEN_IN_EVENTS = (
    "displayName",
    "fileName",
    "fileSha256",
    "plaintext",
    "ciphertext",
    "nodeId",
    "peerNodeId",
    "publicKey",
)


def source(path: Path) -> str:
    return path.read_text()


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def call_spans(text: str, name: str) -> list:
    """Аргументы всех вызовов `name(...)` с учётом вложенных скобок и строк."""
    spans = []
    for match in re.finditer(re.escape(name) + r"\s*\(", text):
        index = match.end()
        depth = 1
        start = index
        in_string = False
        escaped = False
        while index < len(text) and depth > 0:
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        spans.append(text[start:index - 1])
    return spans


class DiagnosticsContractsTest(unittest.TestCase):
    def test_report_keeps_the_privacy_promise(self):
        report = source(DIAG / "DiagnosticsReport.kt")
        self.assertIn('const val SCHEMA = "apu-diag/2"', report)
        for part in ("Приватность", "текст переписки", "имена файлов", "ключи", "contact ID"):
            with self.subTest(part=part):
                self.assertIn(part, report)
        # Скрытие contact ID/адресов живёт ровно в одном месте — в чистом
        # DiagnosticsPrivacy (его правила покрыты JVM-тестами на runner).
        for marker in ('"[contact]"', '"[ip]"', '"[ipv6]"', '"[transfer]"', "fun redact"):
            with self.subTest(marker=marker):
                self.assertIn(marker, report)
        collector = source(DIAG / "TransferDiagnostics.kt")
        self.assertIn("DiagnosticsPrivacy.redact", collector)

    def test_report_has_every_section_the_owner_reads(self):
        report = source(DIAG / "DiagnosticsReport.kt")
        for section in (
            "[сводка]",
            "[окружение]",
            "[сеть]",
            "[ядро]",
            "[mqtt]",
            "[передачи]",
            "[сессия]",
            "[журнал]",
            "[лог процесса]",
        ):
            with self.subTest(section=section):
                self.assertIn(section, report)
        # Сводка отвечает на вопросы, за которыми открывают «Логи».
        for title in ("Сеть", "Брокер", "Ядро", "Передачи", "Прямой канал", "Батарея"):
            with self.subTest(title=title):
                self.assertIn('"%s"' % title, report)

    def test_dialog_keeps_house_style_and_actions(self):
        screen = source(SCREEN)
        self.assertIn("ApuSettingsDialog(", screen)
        self.assertNotRegex(screen, r"\bAlertDialog\(")
        for marker in (
            "TransferDiagnostics.collect(settingsContext)",
            "ApuDiagnosticsStatusCard(lines = logsSnapshot.statusLines)",
            "ApuDiagnosticsReportCard(",
            "AppShare.shareText(settingsContext, reportText,",
            "mqttClipboard.setText(",
            "transferLogsRefresh++",
            "Что происходит сейчас",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, screen)
        ui = source(DIAG_UI)
        self.assertIn("ApuSettingsCard(", ui)
        self.assertIn("ApuBubbleMutedColor", ui)
        self.assertIn("SelectionContainer", ui)

    def test_log_lines_lose_payloads_and_keep_the_clock(self):
        """Ошибки первого отчёта владельца (2026-10-06) не должны вернуться."""
        report = source(DIAG / "DiagnosticsReport.kt")
        for marker in (
            "hidePayloadBodies",
            "collapseRepeatedLogLines",
            "payload=[скрыто]",
            "ipv6CompressedRegex",
            "ipv6FullRegex",
            "TransferErrorText",
            # Шапку logcat из счётчика строк убираем: иначе он читается как «#-# #:#:#.#».
            "threadtimePrefixRegex",
            "pluralLines",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, report)
        collector = source(DIAG / "TransferDiagnostics.kt")
        for marker in (
            "DiagnosticsPrivacy.hidePayloadBodies(",
            "DiagnosticsPrivacy.collapseRepeatedLogLines(",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, collector)
        tests = source(
            ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/diagnostics/DiagnosticsReportTest.kt"
        )
        for test in (
            "logTimestampsSurviveRedaction",
            "mqttPayloadBodiesNeverReachTheReport",
            "repeatedLogLinesCollapseInsteadOfFillingTheReport",
            "freshBrokerErrorShowsUpInTheSummary",
            "seedingIsSharingNotWorkInProgress",
            "failureCodesExplainTheFailureCount",
        ):
            with self.subTest(test=test):
                self.assertIn(test, tests)

    def test_transfer_failures_are_explained_by_codes(self):
        dao = source(MAIN / "data/local/dao/FileTransferDao.kt")
        self.assertIn("transferErrorCounts", dao)
        self.assertIn("lastFailureAtMs", dao)
        report = source(DIAG / "DiagnosticsReport.kt")
        self.assertIn("ошибок=${sums.failed} (за всё время работы приложения)", report)
        self.assertIn('appendLine("раздаётся=${sums.seeding}")', report)
        self.assertIn("TransferErrorText.explain(failure.code)", report)

    def test_mqtt_is_explained_by_one_shared_parser(self):
        view_model = source(VIEW_MODEL)
        self.assertIn("MqttLinkText.humanize(mqttLine)", view_model)
        self.assertNotIn("private fun humanizeMqttLine", view_model)
        self.assertNotIn("private fun mqttHumanError", view_model)
        self.assertTrue((DIAG / "MqttLinkText.kt").exists())

    def test_hooks_write_only_metadata(self):
        for relative in HOOK_FILES:
            path = MAIN / relative
            with self.subTest(file=relative):
                self.assertTrue(path.exists(), f"нет файла-хука {relative}")
                text = strip_comments(source(path))
                self.assertIn("TransferDiagnostics.", text)
                for span in call_spans(text, "TransferDiagnostics.record"):
                    for forbidden in FORBIDDEN_IN_EVENTS:
                        self.assertNotIn(
                            forbidden, span,
                            f"{relative}: в журнал уходит {forbidden} — это запрещено (приватность)",
                        )
                for method in (
                    "TransferDiagnostics.recordSuccess",
                    "TransferDiagnostics.recordWarning",
                    "TransferDiagnostics.recordFailure",
                ):
                    for span in call_spans(text, method):
                        for forbidden in FORBIDDEN_IN_EVENTS:
                            self.assertNotIn(forbidden, span, f"{relative}: {method} с {forbidden}")

    def test_ci_runs_the_pure_kotlin_diagnostics_tests(self):
        script = source(ROOT / "scripts/ci/check-chat-history.sh")
        self.assertIn("DiagnosticsReportTest.kt", script)
        self.assertIn("MqttLinkTextTest.kt", script)
        self.assertIn("com.vladimir.messenger.data.diagnostics.DiagnosticsReportTest", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
