#!/usr/bin/env python3
"""Story feedback contracts: реакции и комментарии на истории (запрос владельца 2026-10-14).

Run: python3 scripts/ci/check-story-feedback.py

Как и остальные проверки этого каталога, это контракты по исходникам: они ловят
откат разводки (экран перестал звать репозиторий, служба перестала досылать,
строка осталась в одной локали), но НЕ заменяют компиляцию Kotlin и проверку на
телефоне. Чистые правила слияния и формата проверяют JVM-тесты
`StoryFeedback*Test` (см. scripts/ci/check-chat-history.sh).
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
CI = ROOT / "scripts/ci"

NEW_STRINGS = (
    "story_reaction_add",
    "story_reactions_empty",
    "story_reactions_count",
    "story_comments",
    "story_comments_open",
    "story_comments_count",
    "story_comments_empty",
    "story_comment_hint",
    "story_comment_send",
    "story_comment_limit",
    "story_feedback_public",
    "story_feedback_core_not_ready",
    "story_feedback_unnamed",
)


def source(path):
    return Path(path).read_text()


class StoryFeedbackWireContracts(unittest.TestCase):
    def test_feedback_rides_the_story_prefix_and_is_absorbed_before_the_story_json(self):
        """Отклик поглощается там же, где история: новых точек маршрутизации нет.

        Пакет истории разбирается до записи в переписку в
        `ChatRepository.saveIncomingMessage`, значит, он разобран на всех путях
        приёма. Отклик идёт тем же префиксом - иначе служебная строка легла бы в
        чат сообщением (грабли r235/r247 с «печатает…»).
        """
        wire = source(MAIN / "data/story/StoryFeedbackWire.kt")
        store = source(MAIN / "data/story/StoryStore.kt")
        marker = re.search(r'const val MARKER = "([^"]+)"', wire).group(1)
        prefix = re.search(r'const val PREFIX = "([^"]+)"', store).group(1)
        self.assertTrue(marker.startswith(prefix), "маркер отклика выпал из префикса истории")
        self.assertTrue(marker.endswith("|"), "маркер обязан заканчиваться разделителем провода")
        self.assertIn("StoryFeedbackStore.isFeedback(content)", store)
        self.assertIn("StoryFeedbackStore.receive(authorId, content)", store)
        # Разбор отклика - ДО json истории и ДО сборки частей фото: чужой значок
        # не должен сбрасывать чей-то недокачанный снимок.
        self.assertLess(
            store.index("StoryFeedbackStore.isFeedback(content)"),
            store.index("JSONObject(content.removePrefix(PREFIX))"),
        )
        self.assertLess(
            store.index("StoryFeedbackStore.isFeedback(content)"),
            store.index("photoParts.receiveContentAddressed"),
        )

    def test_old_version_drops_feedback_instead_of_showing_it_as_a_story(self):
        """Старая версия APU разбирает историю как json с обязательным `text`.

        Конверт отклика - не json и `text` не содержит, поэтому старый телефон
        отбросит пакет (JSONException), а не покажет чужой комментарий как
        историю автора. Это единственная защита обратной совместимости: сервера
        и переговоров о версиях в рое нет.
        """
        wire = source(MAIN / "data/story/StoryFeedbackWire.kt")
        self.assertNotIn('.put("text"', wire)
        self.assertNotIn('"kind"', wire)
        self.assertIn('joinToString("|")', wire)

    def test_reaction_removal_can_be_built_without_an_emoji(self):
        """Снятие значка значка не несёт - иначе снятие не доедет до автора.

        `outgoing` строит пакет из записи книги откликов, а снятая реакция лежит
        там надгробием с пустым значком. Требование значка на снятии тихой
        потерей вернуло бы null, и у соседей значок остался бы навсегда.
        """
        wire = source(MAIN / "data/story/StoryFeedbackWire.kt")
        self.assertIn("if (added) sanitizeEmoji(emoji) ?: return null else sanitizeEmoji(emoji).orEmpty()", wire)
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn("added = !entry.removed", store)
        self.assertIn("emoji = entry.emoji", store)

    def test_comment_limit_is_the_story_text_limit(self):
        """Черта одна: комментарий не длиннее подписи истории (200 знаков)."""
        wire = source(MAIN / "data/story/StoryFeedbackWire.kt")
        store = source(MAIN / "data/story/StoryStore.kt")
        limit = int(re.search(r"const val MAX_COMMENT_CHARS = (\d+)", wire).group(1))
        self.assertEqual(200, limit)
        self.assertIn(f"text.take({limit})", store)

    def test_state_packet_fits_the_story_packet_budget(self):
        """Сводка обязана влезать в предел пакета истории и резаться по месту."""
        wire = source(MAIN / "data/story/StoryFeedbackWire.kt")
        self.assertIn("MAX_PACKET_BYTES", wire)
        self.assertLessEqual(
            int(re.search(r"const val MAX_PACKET_BYTES = (\d+)", wire).group(1).replace("_", "")),
            16 * 1024,
        )
        self.assertIn("keptComments.drop(1)", wire)
        # Берём хвост: в сводку попадают свежие отклики, режутся старые.
        self.assertIn("comments.takeLast(MAX_STATE_COMMENTS)", wire)


class StoryFeedbackTrustContracts(unittest.TestCase):
    def test_sender_is_the_actor_unless_the_packet_came_from_the_story_author(self):
        """Подписать отклик чужим именем по сети нельзя.

        Автор истории - узел-агрегатор: он рассылает сводку, и в ней «кто» может
        быть любым. Все остальные всегда говорят только за себя.
        """
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertEqual(2, store.count("if (fromAuthor) packet.actorId else senderId"))
        self.assertIn("val fromAuthor = senderId == story.authorId", store)

    def test_summary_is_accepted_only_from_the_story_author(self):
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn("if (fromAuthor && !story.mine)", store)

    def test_summary_request_wakes_the_pump(self):
        """Просьба зрителя толкает насос: сводка уходит сразу, а не через 20 с.

        `changes` - счётчик событий, а не версия книги: StateFlow не публикует
        повтор, поэтому событие без изменения книги (поставлена задача сводки)
        иначе не дошло бы ни до экрана, ни до насоса.
        """
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn("_changes.value = _changes.value + 1", store)
        self.assertNotIn("_changes.value = ledger.version", store)
        ask = store.index("is StoryFeedbackWire.Packet.Ask ->")
        state = store.index("is StoryFeedbackWire.Packet.State ->")
        self.assertIn("bump()", store[ask:state])

    def test_feedback_for_an_unknown_story_is_not_stored(self):
        """Иначе любой узел заполнил бы книгу откликов несуществующими историями."""
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn("StoryStore.active(now).firstOrNull { it.id == packet.storyId }", store)
        self.assertIn("OperationalCounter.STORY_FEEDBACK_IGNORED", store)

    def test_actor_name_is_self_declared_and_never_taken_from_the_address_book(self):
        """Имя в отклике - то, которым человек назвался сам.

        Подпись контакта из азбуки автора («Мама», «Начальник») ушла бы всем
        зрителям истории вместе со сводкой. Поэтому `receive` вообще не получает
        имя контакта из переписки.
        """
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertNotIn("senderName", store)
        self.assertIn("fun receive(senderId: String, content: String): Boolean", store)

    def test_revoked_story_takes_its_feedback_with_it(self):
        store = source(MAIN / "data/story/StoryStore.kt")
        self.assertIn("StoryFeedbackStore.forget(storyId)", store)


class StoryFeedbackDeliveryContracts(unittest.TestCase):
    def test_fanout_is_a_signal_lane_and_never_delays_content(self):
        """Рассылка сводки - служебный веер: при пустом бюджете она ждёт."""
        repo = source(MAIN / "data/story/StoryFeedbackRepository.kt")
        self.assertGreaterEqual(repo.count("swarmBudget.tryAcquire(SwarmLane.SIGNAL)"), 2)
        self.assertIn("SwarmLane.SIGNAL", repo)

    def test_feedback_is_sent_as_a_story_packet_so_the_core_relay_owns_retries(self):
        repo = source(MAIN / "data/story/StoryFeedbackRepository.kt")
        self.assertIn("chatRepository.sendStoryPacket(", repo)
        self.assertIn("StoryFeedbackWire.transportId(", source(MAIN / "data/story/StoryFeedbackStore.kt"))

    def test_unfinished_fanout_does_not_mark_the_version_sent(self):
        """Веер, который не дошёл до всех зрителей, версию отправленной не отмечает.

        Иначе `broadcastDue` навсегда увидела бы «всё разослано» и остаток
        зрителей сводку не получил бы: у них нет другого пути, кроме своей
        просьбы `ask`. Отметка ставится только на пустом списке, на полном веере
        и на пределе попыток - после него остаток добирает сводку просьбой.
        """
        repo = source(MAIN / "data/story/StoryFeedbackRepository.kt")
        self.assertEqual(3, repo.count("StoryFeedbackStore.markBroadcastSent("))
        self.assertIn("StoryFeedbackOutbox.MAX_BROADCAST_ATTEMPTS", repo)
        incomplete = repo.index("story feedback fanout incomplete")
        gave_up = repo.index("story feedback fanout gave up")
        self.assertLess(gave_up, incomplete, "предел попыток проверяется до незавершённого веера")
        unfinished = repo[incomplete:repo.index("return false", incomplete)]
        self.assertNotIn("markBroadcastSent", unfinished)

    def test_immature_summary_is_deferred_not_failed(self):
        """Пауза между волнами - не отказ: задача не снимается и попытка не растёт."""
        repo = source(MAIN / "data/story/StoryFeedbackRepository.kt")
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        outbox = source(MAIN / "data/story/StoryFeedbackOutbox.kt")
        self.assertIn("StoryFeedbackStore.taskDeferred(task, nowMs, StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS)", repo)
        self.assertIn("if (accepted != null) StoryFeedbackStore.taskResult(task, accepted", repo)
        self.assertIn("fun taskDeferred(", store)
        self.assertIn("fun defer(task: Task, nowMs: Long, delayMs: Long)", outbox)
        self.assertIn("maxOf(current.notBeforeMs, nowMs + delayMs)", outbox)

    def test_state_transport_id_is_stable_per_version(self):
        """Повторная волна той же сводки - то же сообщение, а не новый пакет."""
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn('StoryFeedbackWire.transportId(storyId, "state", ownId, version)', store)

    def test_the_service_pumps_feedback_next_to_the_story_outbox(self):
        service = source(MAIN / "service/CoreServerService.kt")
        self.assertIn("storyFeedback.pump()", service)
        self.assertIn("StoryFeedbackRepository", service)

    def test_the_store_is_attached_with_the_story_feed(self):
        app = source(MAIN / "MessengerApplication.kt")
        self.assertIn("StoryFeedbackStore.attach(this)", app)
        self.assertLess(app.index("StoryStore.attach(this)"), app.index("StoryFeedbackStore.attach(this)"))

    def test_feedback_survives_restart_without_a_room_migration(self):
        """Отклики живут сутки вместе с историей, поэтому хранятся снимком, а не таблицей.

        Новая таблица Room потребовала бы миграции и версии базы; снимок
        AtomicFile - тот же способ, которым живёт сама лента историй.
        """
        store = source(MAIN / "data/story/StoryFeedbackStore.kt")
        self.assertIn("AtomicFile", store)
        self.assertIn("story-feedback-v1.json", store)
        self.assertIn("MAX_SNAPSHOT_BYTES", store)
        database = source(MAIN / "data/local/AppDatabase.kt")
        self.assertNotIn("StoryFeedback", database)

    def test_the_diagnostics_report_shows_the_feedback_queue(self):
        diagnostics = source(MAIN / "data/diagnostics/TransferDiagnostics.kt")
        self.assertIn("StoryFeedbackStore.report(", diagnostics)
        counters = source(MAIN / "data/diagnostics/OperationalHealth.kt")
        for name in (
            "STORY_FEEDBACK_RECEIVED",
            "STORY_FEEDBACK_INVALID",
            "STORY_FEEDBACK_IGNORED",
            "STORY_FEEDBACK_BROADCAST",
            "STORY_FEEDBACK_SENT",
            "STORY_FEEDBACK_DEFERRED",
        ):
            self.assertIn(name, counters)


class StoryFeedbackUiContracts(unittest.TestCase):
    def test_viewer_shows_public_counts_and_opens_the_comment_feed(self):
        viewer = source(MAIN / "ui/components/Stories.kt")
        for marker in (
            "StoryFeedbackBar(",
            "rememberStoryFeedback(current.id)",
            "ReactionPickerDialog(",
            "StoryCommentsDialog(",
            "onReact: ((StoryStore.Story, String) -> Unit)? = null",
            "onComment: ((StoryStore.Story, String) -> Unit)? = null",
            "onStoryShown: ((StoryStore.Story) -> Unit)? = null",
        ):
            self.assertIn(marker, viewer)

    def test_feedback_windows_stop_the_playback(self):
        """Открытая палитра или лента комментариев останавливают ленту истории.

        Ключи LaunchedEffect обязаны содержать оба флага: без них эффект не
        перезапустится и история долистается, пока человек пишет комментарий.
        """
        viewer = source(MAIN / "ui/components/Stories.kt")
        self.assertIn(
            "LaunchedEffect(current.id, held, userPaused, confirmDelete, resumed, "
            "showReactionPicker, showComments)",
            viewer,
        )
        self.assertIn(
            "StoryPlayback.frozen(held || showReactionPicker || showComments, userPaused, confirmDelete, resumed)",
            viewer,
        )
        # Правила воспроизведения не менялись: их тест живёт отдельно.
        playback = source(MAIN / "ui/components/StoryPlayback.kt")
        self.assertIn(
            "fun frozen(held: Boolean, userPaused: Boolean, confirmDelete: Boolean, resumed: Boolean): Boolean",
            playback,
        )

    def test_story_palette_is_shared_with_messages(self):
        """Один набор значков на всё приложение: человек не учит два языка."""
        viewer = source(MAIN / "ui/components/Stories.kt")
        self.assertIn("ReactionPickerDialog(", viewer)
        palette = source(MAIN / "data/reaction/ReactionWire.kt")
        self.assertEqual(20, len(re.findall(r'"[^"]+", //', palette)))

    def test_both_story_entrances_are_wired(self):
        """Историю открывают из списка чатов и из карточки собеседника."""
        for relative in (
            "ui/screens/chat/ChatListScreen.kt",
            "ui/screens/chat/ChatDetailScreen.kt",
        ):
            with self.subTest(screen=relative):
                screen = source(MAIN / relative)
                for marker in ("onReact =", "onComment =", "onStoryShown ="):
                    self.assertIn(marker, screen)

    def test_view_models_own_the_actions(self):
        for relative in (
            "ui/screens/chat/ChatListViewModel.kt",
            "ui/screens/chat/ChatDetailViewModel.kt",
        ):
            with self.subTest(view_model=relative):
                model = source(MAIN / relative)
                self.assertIn("StoryFeedbackRepository", model)
                for marker in ("fun reactToStory(", "fun commentOnStory(", "fun onStoryShown("):
                    self.assertIn(marker, model)

    def test_feedback_ui_keeps_the_house_style(self):
        """Фирменные окна, поля и кнопки: стоковых Material-элементов нет."""
        feedback = source(MAIN / "ui/components/StoryFeedbackUi.kt")
        self.assertIn("ApuSettingsDialog(", feedback)
        self.assertIn("ApuBubbleField(", feedback)
        self.assertIn("ApuTextAction(", feedback)
        for banned in ("TextButton(", "AlertDialog(", "OutlinedTextField("):
            self.assertNotIn(banned, feedback)
        # Карточка окна прокручивается целиком, поэтому лента комментариев -
        # обычный Column: второй прокручиваемый список внутри неё не измеряется.
        self.assertNotIn("LazyColumn(", feedback)

    def test_strings_exist_in_both_locales(self):
        russian = source(RES / "values/strings.xml")
        english = source(RES / "values-en/strings.xml")
        for name in NEW_STRINGS:
            with self.subTest(string=name):
                self.assertIn(f'<string name="{name}"', russian)
                self.assertIn(f'<string name="{name}"', english)

    def test_no_new_string_is_left_dead(self):
        """Строка добавляется вместе с экраном, который её показывает.

        Мёртвая строка в двух локалях - это либо забытая разводка (экран не
        говорит того, что должен), либо лишний перевод, который владелец будет
        перечитывать в каждой следующей версии.
        """
        code = "\n".join(
            path.read_text() for path in (MAIN / "ui").rglob("*.kt")
        )
        for name in NEW_STRINGS:
            with self.subTest(string=name):
                self.assertIn(f"R.string.{name}", code)


class StoryFeedbackCiContracts(unittest.TestCase):
    def test_pure_rules_are_compiled_and_run_without_the_android_sdk(self):
        history = source(CI / "check-chat-history.sh")
        for relative in (
            "data/story/StoryFeedbackWire.kt",
            "data/story/StoryFeedbackLedger.kt",
            "data/story/StoryFeedbackOutbox.kt",
        ):
            self.assertIn(relative.split("/")[-1], history)
        for test_class in (
            "com.vladimir.messenger.data.story.StoryFeedbackWireTest",
            "com.vladimir.messenger.data.story.StoryFeedbackLedgerTest",
            "com.vladimir.messenger.data.story.StoryFeedbackOutboxTest",
        ):
            self.assertIn(test_class, history)
        self.assertIn("check-story-feedback.py", history)

    def test_the_android_gate_runs_the_same_regressions(self):
        compile_script = source(CI / "check-android-compile.sh")
        for pattern in ("*StoryFeedbackWireTest*", "*StoryFeedbackLedgerTest*", "*StoryFeedbackOutboxTest*"):
            self.assertIn(pattern, compile_script)

    def test_the_pure_sources_really_have_no_android_imports(self):
        """Иначе чистая компиляция на runner упадёт и спрячет остальные проверки."""
        for name in ("StoryFeedbackWire.kt", "StoryFeedbackLedger.kt", "StoryFeedbackOutbox.kt"):
            with self.subTest(file=name):
                text = source(MAIN / "data/story" / name)
                self.assertNotIn("import android.", text)
                self.assertNotIn("import androidx.", text)
                self.assertNotIn("import org.json", text)
                self.assertNotIn("import kotlinx.coroutines", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
