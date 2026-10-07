#!/usr/bin/env python3
"""Контракты отчёта «Логи»: приватность, разделы, сводка и окно в стиле APU.

Это не скриншот-тест и не компилятор: проверяются ИМЕНА, тексты и вызовы в
исходниках. Зачем отдельная проверка: отчёт уезжает владельцу в чат, поэтому
нельзя молча потерять обещание приватности, раздел «сводка» или кнопку
копирования. Ровно те ошибки уже случались в проекте (см. журнал: сборки
v11.74.171 падали на необъявленных именах) — здесь ловится класс «вызов
исчез из кода», а не «скриншот стал другим».
"""
import importlib.util
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
            "[сообщения]",
            "[передачи]",
            "[сессия]",
            "[журнал]",
            "[лог процесса]",
        ):
            with self.subTest(section=section):
                self.assertIn(section, report)
        # Сводка отвечает на вопросы, за которыми открывают «Логи».
        for title in ("Сеть", "Брокер", "Ядро", "Сообщения", "Передачи", "Прямой канал", "Батарея"):
            with self.subTest(title=title):
                self.assertIn('"%s"' % title, report)

    def test_dialog_keeps_house_style_and_actions(self):
        screen = source(SCREEN)
        self.assertIn("ApuSettingsDialog(", screen)
        self.assertNotRegex(screen, r"\bAlertDialog\(")
        for marker in (
            "TransferDiagnostics.collect(",
            "onStage = { stage -> transferLogsStage = stage },",
            "onPartial = { partial -> transferLogsSnapshot = partial },",
            # Владелец 2026-10-07: «нет логов списка вообще» — журнал виден
            # отдельным списком, а не только внутри текста отчёта.
            "ApuDiagnosticsEventList(events = logsSnapshot?.events.orEmpty())",
            "ApuDiagnosticsStatusCard(lines = logsSnapshot?.statusLines.orEmpty())",
            "ApuDiagnosticsReportCard(",
            "AppShare.shareText(settingsContext, reportText,",
            "mqttClipboard.setText(",
            "transferLogsRefresh++",
            # Владелец 2026-10-07 (вторая итерация): «как то всё по пенсионерски
            # и по деревенски… цвета яркие и чёткие, объём кнопок выразителен,
            # блеск премиальный». Шапка — тёмный баннер со статусом, приватность —
            # стеклянная полоса со щитом, кнопки — наши объёмные, не TextButton.
            "ApuDiagnosticsHero(",
            "ApuDiagnosticsPrivacyStrip(",
            "ApuDiagnosticsActionButton(",
            "DiagnosticsActionStyle.PRIMARY",
            "caption = logsSnapshot?.let { snapshot ->",
            "версия ${snapshot.appVersion}",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, screen)
        # Внутри окна «Логи» — только наши объёмные кнопки (TextButton остаётся
        # у соседних диалогов экрана, это не наша зона).
        logs_block = screen[screen.index("ApuDiagnosticsHero("):screen.index("muteScope?.let { scope ->")]
        self.assertNotIn("TextButton(", logs_block)
        for label in ("Отправить", "Скопировать", "Обновить", "Закрыть"):
            with self.subTest(label=label):
                self.assertIn('label = "%s"' % label, logs_block)
        ui = source(DIAG_UI)
        self.assertIn("ApuSettingsCard(", ui)
        self.assertIn("ApuBubbleMutedColor", ui)
        self.assertIn("SelectionContainer", ui)

    def test_logs_window_is_never_a_dead_end(self):
        """Владелец 2026-10-07: «кнопки в новом не работают и нет логов списка вообще. Всё на паузе.»

        Три причины пустого окна закрыты контрактом:
          1) сбор нельзя оставить вечно «в паузе» — он в try/catch/finally и
             делится на быструю часть (она приходит сразу) и журнал процесса;
          2) чтение журнала процесса ограничено строками и временем, а процесс
             убивается в любом случае (раньше читался весь буфер телефона);
          3) у кнопок нет права молчать: нажатие всегда даёт видимый ответ, а
             ошибка сбора видна в окне, а не только в системном журнале.
        """
        screen = source(SCREEN)
        diag = source(DIAG / "TransferDiagnostics.kt")
        ui = source(DIAG_UI)
        for marker in (
            # 1) окно и этапы
            "catch (failure: Throwable)",
            "} finally {",
            "transferLogsLoading = false",
            "transferLogsStage = TransferDiagnostics.STAGE_DEVICE",
            "onStage = { stage -> transferLogsStage = stage }",
            "onPartial = { partial -> transferLogsSnapshot = partial }",
            # 3) кнопки отвечают всегда
            "apuDiagnosticsNothingYet(settingsContext, transferLogsStage)",
            # 4) долгий сбор объясняется словами, а не молчанием
            "transferLogsSlow = true",
            "сбор идёт дольше обычного",
        ):
            with self.subTest(marker=marker, where="SettingsScreen"):
                self.assertIn(marker, screen)
        self.assertNotIn(
            "enabled = reportText.isNotBlank()",
            screen,
            "кнопки «Отправить»/«Скопировать» не должны быть мёртвыми при пустом отчёте",
        )
        for marker in (
            # 2) журнал процесса читается ограниченно и не держит окно
            "LOGCAT_LINE_LIMIT",
            "LOGCAT_BUDGET_MS",
            '"*:V",',
            "reader.join(budgetMs)",
            "process.destroy()",
            "LogcatScan.EMPTY",
            # 1) двухзаходный сбор
            "onPartial?.invoke(",
            "snapshotOf(",
            "STAGE_LOGCAT",
            # журнал списком
            "val events: List<DiagnosticsJournalEntry> = emptyList(),",
        ):
            with self.subTest(marker=marker, where="TransferDiagnostics"):
                self.assertIn(marker, diag)
        self.assertIn('"-t", LOGCAT_LINE_LIMIT.toString()', diag)
        for marker in (
            "fun ApuDiagnosticsEventList(",
            "DiagnosticsReport.clock(event.atMs)",
            "diagnosticsLevelLabel(event.level)",
        ):
            with self.subTest(marker=marker, where="ApuDiagnosticsUi"):
                self.assertIn(marker, ui)

    def test_lost_items_do_not_live_forever(self):
        """Владелец 2026-10-07: тяжёлое — сутки, текст и малое — неделя, и уборка.

        Проверяются ИМЕНА и числа в исходниках: срок задаётся в одном месте,
        иначе «потеряшка» снова заживёт по старому правилу в другом файле.
        """
        retention = source(MAIN / "data/file/FileTransferRetention.kt")
        for marker in (
            "object FileTransferRetention",
            "const val HEAVY_MAX_BYTES: Long = 2L * 1024 * 1024",
            "const val HEAVY_TTL_MS: Long = 24L * 60L * 60L * 1000L",
            "const val LIGHT_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L",
            "fun isHeavyCategory(mediaType: String)",
            "fun ttlMs(mediaType: String, totalBytes: Long)",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, retention)
        self.assertNotIn("import android", retention)

        # Черта «тяжёлого» одна на две задачи: раздача в режиме абонента и срок
        # хранения. Если значения разъедутся, это должно падать здесь.
        server_mode = source(MAIN / "data/swarm/ServerMode.kt")
        heavy = re.search(r"HEAVY_MAX_BYTES: Long = ([0-9L* ]+)", retention)
        light = re.search(r"LIGHT_SERVE_MAX_BYTES: Long = ([0-9L* ]+)", server_mode)
        self.assertIsNotNone(heavy, "в FileTransferRetention нет HEAVY_MAX_BYTES")
        self.assertIsNotNone(light, "в ServerMode нет LIGHT_SERVE_MAX_BYTES")
        self.assertEqual(heavy.group(1).strip(), light.group(1).strip())

        # Срок берётся из правила: отправка (личная и групповая), хранение чужого
        # файла и карточка в зеркале.
        for path, marker in (
            ("data/file/OutgoingFilePreparationService.kt", "FileTransferRetention.ttlMs(inspected.mediaType, inspected.sizeBytes)"),
            ("data/file/FileTransferReceiver.kt", "FileTransferRetention.ttlMs(manifest.mediaType, manifest.fileSize.toLong())"),
            ("data/file/FileTransferRouter.kt", "FileTransferRetention.ttlMs(mime, size)"),
        ):
            with self.subTest(path=path):
                self.assertIn(marker, source(MAIN / path))

        # Уборка: просроченные незавершённые строки уходят вместе с кусками,
        # копии на диске без живой строки — тоже.
        router = source(MAIN / "data/file/FileTransferRouter.kt")
        for marker in (
            "suspend fun purgeExpiredTransfers(",
            "transferDao.getExpiredIncomplete(nowMs, EXPIRED_PURGE_BATCH)",
            "chunkStore.transferIds()",
            "purgeExpiredTransfers(now)",
            "TRANSFER_EXPIRED_ROWS",
            "TRANSFER_EXPIRED_COPIES",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, router)
        dao = source(MAIN / "data/local/dao/FileTransferDao.kt")
        self.assertIn("suspend fun getExpiredIncomplete(nowMs: Long, limit: Int)", dao)
        store = source(MAIN / "data/file/FileTransferChunkStore.kt")
        self.assertIn("fun transferIds(): List<String>", store)
        group_store = source(MAIN / "data/group/GroupFileStore.kt")
        self.assertIn("fun entries(): List<Entry>", group_store)
        swarm = source(MAIN / "data/group/GroupFileSwarm.kt")
        self.assertIn("sweepCopies(now)", swarm)

        # Правило и результат уборки видны владельцу в отчёте «Логи».
        report = source(DIAG / "DiagnosticsReport.kt")
        self.assertTrue(
            "appendLine(FileTransferRetention.describe())" in report,
            "в разделе [передачи] нет строки о сроках хранения",
        )
        self.assertTrue(
            '"убрано просроченного=${expiredRows} "' in report
            and '"(копий на диске освобождено=${expiredCopies})"' in report,
            "в отчёте нет числа убранного просроченного (потеряшек)",
        )
        self.assertTrue(
            'const val TRANSFER_EXPIRED_ROWS = "transfer_expired_rows"' in report,
            "нет счётчика убранных строк",
        )

        # Тест правила зарегистрирован в прогоне JVM-тестов на runner.
        script = source(ROOT / "scripts/ci/check-chat-history.sh")
        for marker in ("FileTransferRetention.kt", "FileTransferRetentionTest.kt",
                       "com.vladimir.messenger.data.file.FileTransferRetentionTest"):
            with self.subTest(marker=marker):
                self.assertIn(marker, script)
        tests = source(ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/file/FileTransferRetentionTest.kt")
        for marker in (
            "fun heavyIsPhotoVideoOrBigFile()",
            "fun heavyLivesOneDayAndLightLivesAWeek()",
            "fun logsSayTheRuleAndTheSweep()",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, tests)

    def test_premium_palette_keeps_contrast(self):
        """Яркий премиальный вид обязан остаться ЧИТАЕМЫМ.

        Владелец 2026-10-07: «чтобы и цвета были яркие и чёткие… и блеск
        передавался премиальный». Яркость живёт в заливках, градиентах и
        бликах; текстом идут тёмные чернила. Здесь считается контраст по
        WCAG 2.1 — тот же метод, что у палитры чата, и порог 4.5 для текста.

        Проверяются все пары «текст на фоне», которые человек реально увидит:
        чернила на золотой кнопке (на худшем стопе градиента), светлый текст
        консоли на тёмной подложке, подписи уровней на карточке настроек.
        """
        spec = importlib.util.spec_from_file_location("chat_style", ROOT / "scripts/ci/check-chat-style.py")
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)

        def rgb(hex_value):
            value = int(hex_value, 16)
            return tuple((value >> shift & 255) / 255 for shift in (16, 8, 0))

        ui = source(DIAG_UI)

        def palette(name):
            match = re.search(r"\b" + name + r"\s*(?::\s*Color)?\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", ui)
            self.assertIsNotNone(match, "в ApuDiagnosticsUi.kt нет цвета " + name)
            return rgb(match.group(1))

        def contrast(first, second):
            dark, light = sorted((checks.luminance(first), checks.luminance(second)))
            return (light + 0.05) / (dark + 0.05)

        # 1) Золотая кнопка: чернила DiagOnGold на ВСЕХ стопах градиента.
        on_gold = palette("DiagOnGold")
        for stop in ("DiagGoldLight", "DiagGold", "DiagGoldDeep"):
            with self.subTest(button_stop=stop):
                self.assertGreaterEqual(contrast(on_gold, palette(stop)), 4.5)

        # 2) Тёмная консоль: светлый текст и яркие краски ошибок на её подложке.
        surface = palette("DiagConsoleSurface")
        for ink in ("DiagConsoleText", "DiagConsoleTitle", "DiagConsoleWarn", "DiagConsoleBad"):
            with self.subTest(console_ink=ink):
                self.assertGreaterEqual(contrast(palette(ink), surface), 4.5)

        # 3) Подписи уровней на светлой карточке настроек (там же яйцо-карточка).
        card = checks.blend(
            checks.color(checks.source("components/ApuBubble.kt"), "ApuBubbleSurfaceColor"),
            (1, 1, 1),
            0.92,
        )
        for ink in ("0xFF047857", "0xFF8A5A00", "0xFF9A3412", "0xFFA12D3A"):
            with self.subTest(level_ink=ink):
                self.assertGreaterEqual(contrast(rgb(ink[2:]), card), 4.5)

        # 4) Чернила уровня в коде — те же самые: текстом идёт тёмный набор,
        #    яркий остаётся заливкам. Иначе «ярче» снова станет «бледнее».
        for ink in ("Color(0xFF047857)", "Color(0xFF8A5A00)", "Color(0xFF9A3412)"):
            with self.subTest(ink_in_code=ink):
                self.assertIn(ink, ui)
        self.assertIn("DiagnosticsLevel.BAD -> ApuSettingsDangerColor", ui)

        # 5) Объём и блеск на месте: тень (диагностика), блик, отклик на нажатие.
        for marker in ("fun Modifier.diagnosticsGloss(", "fun Modifier.diagnosticsLift(",
                       "shadow(elevation", "drawWithCache", "graphicsLayer { scaleX = scale"):
            with self.subTest(marker=marker):
                self.assertIn(marker, ui)

    def test_logs_window_keeps_the_house_style(self):
        """Окно «Логи»: яркий премиальный вид на house-подложке."""
        ui = source(DIAG_UI)
        for marker in (
            "ApuDiagnosticsHero(",
            "ApuDiagnosticsVerdictPill(",
            "ApuDiagnosticsEmblem(",
            "ApuDiagnosticsGlassChip(",
            "ApuDiagnosticsLegend",
            "ApuDiagnosticsDivider",
            "ApuDiagnosticsLevelWell(",
            "ApuDiagnosticsTrafficLights(",
            "ApuDiagnosticsActionButton(",
            # Отклик на нажатие — своё сжатие, без «ряби» Material.
            "collectIsPressedAsState()",
            "animateFloatAsState(",
            "indication = null",
            "diagnosticsGloss(",
            "diagnosticsLift(",
            "ВСЁ В ПОРЯДКЕ",
            "ЕСТЬ ПРЕДУПРЕЖДЕНИЯ",
            "ЕСТЬ ОШИБКИ",
            "порядок",
            "справка",
            "внимание",
            "поломка",
            "Полный отчёт",
            "fun diagnosticsReportAnnotated(",
            "withStyle(",
            "ApuSettingsCard(",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, ui)
        # Краски берутся у house style, а не выдумываются на месте.
        self.assertIn("diagnosticsLevelColor(level)", ui)
        self.assertIn("ApuBubbleAccentColor", ui)
        self.assertIn("ApuSettingsDangerColor", ui)
        # Яркое — в заливках, читаемое — в тексте: оба набора обязаны жить рядом.
        self.assertIn("fun diagnosticsLevelInkColor(", ui)
        self.assertIn("fun diagnosticsLevelColor(", ui)
        # Раскраска не меняет ни одного символа: текст остаётся исходным,
        # поэтому копия разойдётся с экраном только по недосмотру — не сейчас.
        self.assertIn("if (style == null) append(line) else withStyle(style) { append(line) }", ui)
        # Правило подсветки — чистая функция в слое отчёта (её гоняют JVM-тесты),
        # а не набор шаблонов внутри композабла.
        report = source(DIAG / "DiagnosticsReport.kt")
        for marker in (
            "enum class DiagnosticsLineTone",
            "fun diagnosticsLineTone(",
            "toneSectionRegex",
            "toneBadJournalRegex",
            "toneWarnJournalRegex",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, report)
        tests = source(ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/diagnostics/DiagnosticsReportTest.kt")
        for marker in (
            "reportHighlightMarksSectionsFailuresAndWarnings",
            "reportHighlightKeepsEveryCharacterOfTheShownText",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, tests)

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
            # 2026-10-07: «мои сообщения доходят, а от него нет» — направление
            # переписки и причины потери входящих должны быть в отчёте.
            "summarySpotsOutgoingWithoutIncoming",
            "messageSectionShowsDirectionsAndReasons",
            "quietSessionIsNotAWarning",
            "healthyExchangeReadsAsOk",
            "relayQueueOverflowIsExplainedNotJustCounted",
            "peerKeyChangeExplainsSilentChat",
            "offlineQueueIsNotMistakenForSilence",
            "queueIsSplitBetweenOwnAndForeign",
            "foreignQueueAloneIsNotAWarning",
            "refusedSendIsNamedInTheSummary",
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

    def test_messages_report_both_directions_and_reasons(self):
        """Жалоба «от него не приходит» должна разбираться по отчёту, а не на слух."""
        report = source(DIAG / "DiagnosticsReport.kt")
        for marker in (
            "const val MSG_IN = ",
            "const val MSG_OUT = ",
            "const val MSG_ACK = ",
            "const val MSG_IN_NOT_OPENED = ",
            "const val MSG_IN_BAD_SENDER = ",
            "const val MSG_PEER_KEY_CHANGED = ",
            "const val MSG_SEND_FAILED = ",
            "const val MSG_QUEUED_OFFLINE = ",
            "private fun messageLine(",
            "private fun StringBuilder.appendMessages(",
            "входящих показано=",
            "не вскрылось (чужая переписка или устаревший ключ)=",
            "ядро передало сигналов=",
            "пересылка: отброшено из-за полной очереди=",
            "очередь ядра: своё ждёт получателя=",
            "очередь ядра: чужая пересылка=",
            "чужой лимит на получателя",
            "смена ключа у собеседника (раз)=",
            "отложено до сети (уйдёт само)=",
            "не ушло (узел отказал или нет сети)=",
            "отсканируйте его QR-код заново",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, report)
        # «Входящих нет, а свои уходят и подтверждаются» — предупреждение, а не
        # строка «всё хорошо»: ровно это владелец и увидел на телефоне.
        self.assertIn("входящих нет, а отправлено", report)
        collector = source(DIAG / "TransferDiagnostics.kt")
        for marker in (
            "fun noteMessageIncoming(",
            "fun noteMessageOutgoing(",
            "fun noteDeliveryAck(",
            "fun noteSealedNotOpened(",
            "fun noteBadSender(",
            "fun notePeerKeyChanged(",
            "fun noteMessageSendFailed(",
            "fun noteMessageQueuedOffline(",
            "lastIncomingAtMs",
            "private fun scanLogcat(",
            "coreMessageSignals = all.count",
            "relayQueueFull = all.count",
            "Relay-очередь получателя переполнена",
            "MessageReceived EMITTED",
            "Relay-очередь: своих=",
            "private fun queueStat(",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, collector)
        # Хуки обязаны стоять там, где сообщение входит и выходит: счётчик без
        # вызова — это «голословный» отчёт, за который уже был разбор.
        service = source(MAIN / "service/CoreServerService.kt")
        for marker in (
            "TransferDiagnostics.noteMessageIncoming()",
            "noteSealedNotOpened(senderKeyKnown = known)",
            "TransferDiagnostics.noteBadSender()",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, service)
        receiver = source(MAIN / "data/file/FileTransferReceiver.kt")
        for marker in (
            "HelloResult.REJECTED_KEY_CHANGED",
            "TransferDiagnostics",
            "notePeerKeyChanged()",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, receiver)
        chat = source(MAIN / "data/repository/ChatRepository.kt")
        for marker in (
            ".noteMessageOutgoing()",
            "TransferDiagnostics.noteDeliveryAck()",
            "noteMessageQueuedOffline()",
            "noteMessageSendFailed(e.javaClass.simpleName)",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, chat)

    def test_core_queue_separates_own_from_foreign(self):
        """Своя переписка не может быть отказана или вытеснена чужой пересылкой."""
        queue = source(ROOT / "rust-core/src/network/relay_queue.rs")
        for marker in (
            "MAX_OWN_PER_RECIPIENT",
            "MAX_OWN_TOTAL",
            "pub fn enqueue_own(",
            "fn evict_oldest_own_for(",
            "fn evict_oldest_own(",
            "pub fn own_count(",
            "pub fn foreign_count(",
            "pub struct RelayQueueStats",
            "pub fn stats(",
        ):
            with self.subTest(marker=marker):
                # Короткое сообщение: упавший assertIn печатает весь файл, а
                # этот текст уходит в комментарий к PR (2026-10-06).
                self.assertTrue(marker in queue, f"нет маркера {marker} в relay_queue.rs")
        # Лимиты получателя/очереди считают ТОЛЬКО чужое.
        for marker in (
            "fn enqueue_foreign(",
            "self.foreign_for_locked(&entries, &msg.recipient)",
            "self.foreign_count_locked(&entries) >= self.max_total",
            # Своё идёт первым и в выдаче получателю, и в gossip-раунде.
            "b.own",
        ):
            with self.subTest(marker=marker):
                self.assertTrue(marker in queue, f"нет маркера {marker} в relay_queue.rs")
        core = source(ROOT / "rust-core/src/engine/core.rs")
        for marker in (
            "enqueue_own(prepared.message)",
            "q.enqueue_own(next_hop_message)",
            "let is_own_origin = next_hop_message.origin_sender == node_id;",
            "queue.enqueue_own(record)",
            "Relay-очередь: своих={} чужих={}",
            "MAX_TOTAL + MAX_OWN_TOTAL",
        ):
            with self.subTest(marker=marker):
                self.assertTrue(marker in core, f"нет маркера {marker} в core.rs")

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
