#!/usr/bin/env python3
"""Контракт видеоаватара: «как в телеграм» — тот же редактор, что у видеоисторий.

Зачем отдельный контракт. Видеоаватар собирается из готовых узлов видеоисторий
(камера с фильтрами/фоном/эффектами голоса, редактор, экспорт), и расходится он
с ними тихо: кто-то копирует редактор под аватар и перестаёт чинить оба, кто-то
снимает предел 15 секунд, кто-то возвращает в профиль `VideoView`, у которого
`SurfaceView` уходит из-под круглой маски и видео торчит квадратом за аватар.
Ни одно из этого не видно ни в логе сборки, ни в существующих контрактах историй.

Что проверяется:

  1) возможность и ранг: `VIDEO_AVATAR(3)` в `FeatureRankPolicy`, отказ виден до
     записи (окно создания) и проверяется там, где файл сохраняется;
  2) паритет с видеоисториями: ОДИН редактор (`StoryVideoEditor`) и ОДНА камера
     (`StoryVideoCamera`) на оба потока — второй реализации быть не должно,
     иначе новое умение историй не появится у аватара;
  3) пределы аватара: 15 секунд и 24 МБ в `VideoAvatarPolicy`, проверка перед
     сохранением, приведение черновика после «сбросить всё»;
  4) показ: TextureView (`StoryVideoSurface`), зацикленно, без звука, пауза в
     фоне, кадр-постер как запасной слой; никакого `VideoView`;
  5) постер уходит наружу обычным аватаром, ролик остаётся в приватной папке;
  6) строки есть в обеих локалях;
  7) поток не живёт в god-файле настроек;
  8) видеокружки СОБЕСЕДНИКОВ: общий плеер крутит их ролики везде, где
     встречается аватар контакта; ролик доезжает анонсом/просьбой обычной
     файловой передачей, старые клиенты остаются на постере;
  9) доставка не завязана на постоянные идентификаторы конвертов (иначе
     повторные анонсы проглатываются как дубликаты и у друзей остаётся
     постер), состояние своего видео читается ДО первого анонса, а подпись
     в профиле текстом не показывается (запрос владельца 2026-11-06);
 10) просьба ролика адресована ВЛАДЕЛЬЦУ видео (со своим id в поле владелец
     молча отбрасывал просьбу), кольцо показывает реальные принятые байты
     (12 часов → по часовой), а «Логи» показывают цепочку обмена; повторы не
     теряются до запуска ядра и при смене старой версии (2026-11-06).

Запуск:
    python3 scripts/ci/check-video-avatar.py
Код возврата: 0 — в порядке, 1 — нарушение.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class VideoAvatarContract(unittest.TestCase):
    def setUp(self):
        self.policy = read(JAVA / "data/rank/FeatureRankPolicy.kt")
        self.composer = read(JAVA / "ui/components/VideoAvatarComposer.kt")
        self.installer = read(JAVA / "data/profile/VideoAvatarInstaller.kt")
        self.rules = read(JAVA / "data/profile/VideoAvatarPolicy.kt")
        self.avatar = read(JAVA / "ui/components/MyAvatar.kt")
        self.player = read(JAVA / "ui/components/VideoAvatarPlayer.kt")
        self.peer = read(JAVA / "ui/components/ApuVipRing.kt")
        self.editor = read(JAVA / "ui/components/StoryVideoEditor.kt")
        self.camera = read(JAVA / "ui/components/StoryVideoCamera.kt")
        self.story_composer = read(JAVA / "ui/components/StoryVideoComposer.kt")
        self.settings = read(JAVA / "ui/screens/settings/SettingsScreen.kt")
        self.wire = read(JAVA / "data/avatavideo/AvatarVideoWire.kt")
        self.store = read(JAVA / "data/avatavideo/PeerVideoAvatarStore.kt")
        self.coord = read(JAVA / "data/avatavideo/AvatarVideoCoordinator.kt")
        self.inbox = read(JAVA / "data/avatavideo/SpecialMediaInbox.kt")
        self.group_router = read(JAVA / "data/group/GroupRouter.kt")
        self.my_video_store = read(JAVA / "data/profile/VideoAvatarStore.kt")

    # 1) ранг ----------------------------------------------------------------
    def test_video_avatar_opens_at_the_circle_rank(self):
        self.assertRegex(self.policy, r"VIDEO_AVATAR\(3\)")
        self.assertIn("RankCapability.VIDEO_AVATAR -> ", read(JAVA / "data/rank/RankFeatureSummary.kt"))
        # До записи человек видит причину, а не пустое место или ошибку в конце.
        self.assertIn("VideoAvatarRankGate.canSet(context)", self.composer)
        self.assertIn("VideoAvatarRankGate.lockedNotice(context)", self.composer)
        # И там, где файл реально сохраняется: UI можно обойти.
        self.assertIn("VideoAvatarRankGate.requireCanSet(context)", self.installer)
        # Установщик внутренний: на входе `StoryVideoExporter.Exported` —
        # внутренний тип видеоисторий, а публичная функция не вправе его
        # показывать (на этом в CI падал `:app:compileReleaseKotlin`).
        self.assertIn("internal object VideoAvatarInstaller", self.installer)
        self.assertIn("RankCapability.VIDEO_AVATAR", read(JAVA / "data/profile/VideoAvatarRankGate.kt"))

    # 2) паритет с видеоисториями -------------------------------------------
    def test_avatar_reuses_the_story_camera_and_editor_instead_of_copying_them(self):
        self.assertIn("StoryVideoCamera(", self.composer)
        self.assertIn("StoryVideoEditor(", self.composer)
        self.assertIn("StoryVideoEditor(", self.story_composer)
        # Камера записи одна: фильтры лица, фон, эффекты голоса и фонарик живут
        # в ней, и копия оставила бы аватар без них.
        self.assertNotIn("CameraX", self.composer)
        self.assertNotIn("ProcessCameraProvider", self.composer)
        # Экспорт тот же: отдельный кодер разошёлся бы по качеству и проверкам.
        self.assertIn("StoryVideoExporter.Exported", self.composer)
        self.assertIn("StoryVideoExporter.Exported", self.installer)
        # Форма (круг/обычный) выбирается тем же переключателем историй.
        self.assertIn("StoryVideoShapeSelector(", self.composer)

    def test_shared_editor_takes_the_avatar_window_and_its_own_labels(self):
        # Предел и подписи — параметры общего редактора, а не второй экран.
        self.assertIn("maxDurationMs: Long = StoryVideoDraft.MAX_DURATION_MS", self.editor)
        self.assertIn("minOf(maxDurationMs, source.durationMs - draft.startMs)", self.editor)
        self.assertIn("maxDurationMs: Long = StoryVideoDraft.MAX_DURATION_MS", self.camera)
        self.assertIn("maxDurationMs = maxDurationMs", self.camera)
        # Камера записывает не дольше потолка, а таймер и подпись простоя
        # называют реальный предел: зашитая минута врала бы про аватар.
        self.assertIn("setDurationLimitMillis(maxDurationMs)", read(JAVA / "data/story/StoryVideoRecorder.kt"))
        self.assertIn("progress = (elapsed / maxDurationMs.toFloat())", self.camera)
        self.assertIn("totalMs: Long = StoryVideoDraft.MAX_DURATION_MS", self.camera)
        self.assertIn("@androidx.annotation.StringRes titleRes: Int = R.string.story_record_title", self.camera)
        self.assertIn("titleRes = R.string.av_video_editor_title", self.composer)
        for token in ("titleRes = R.string.av_video_editor_title",
                      "coverLabelRes = R.string.av_video_cover_label",
                      "resetHintRes = R.string.av_video_reset_hint",
                      "resetDoneBodyRes = R.string.av_video_reset_done_body"):
            self.assertIn(token, self.composer)
        # История свои 60 секунд не потеряла: у неё параметры по умолчанию.
        self.assertIn("story_record_title", self.editor)
        self.assertIn("story_video_reset_hint", self.editor)

    # 3) пределы аватара -----------------------------------------------------
    def test_avatar_limits_are_stricter_than_stories_and_checked_before_saving(self):
        self.assertIn("const val MAX_DURATION_MS = 15_000L", self.rules)
        self.assertIn("fun clampToAvatar(", self.rules)
        self.assertIn("VideoAvatarPolicy.clampToAvatar(", self.composer)
        self.assertIn("VideoAvatarPolicy.MAX_DURATION_MS", self.composer)
        self.assertIn("VideoAvatarPolicy.requireAcceptable(exported.draft, exported.media)", self.installer)
        # Копирование с потолком, а не readBytes: правило чтения файлов.
        self.assertIn("copyWithLimit(", self.installer)
        self.assertNotIn("readBytes()", self.installer)

    # 4) показ в профиле -----------------------------------------------------
    def test_profile_plays_the_avatar_through_a_texture_view_in_a_loop(self):
        # Плеер один на все видеоаватары (свой и чужие): копия «для профиля»
        # расходилась бы с остальными по зацикливанию, звуку и паузе в фоне.
        self.assertIn("VideoAvatarPlayback(", self.avatar)
        # SurfaceView уходит из-под круглой маски Compose — только TextureView.
        self.assertIn("StoryVideoSurface(", self.player)
        # `VideoView(` и `android.widget.VideoView` — прежний способ: он и был
        # квадратным поверх круглого аватара. В комментарии слово остаться может.
        for text, where in ((self.avatar, "MyAvatar"), (self.player, "VideoAvatarPlayer")):
            self.assertNotIn("VideoView(", text, where)
            self.assertNotIn("android.widget.VideoView", text, where)
        self.assertIn("Player.REPEAT_MODE_ONE", self.player)
        self.assertIn("player.volume = 0f", self.player)
        # В фоне плеер стоит: аватар не должен играть в кармане.
        self.assertIn("Lifecycle.Event.ON_STOP -> player.pause()", self.player)
        self.assertIn("Lifecycle.Event.ON_START -> player.play()", self.player)
        # Постер/инициалы остаются под видео: ролик мог не доехать зеркалом.
        self.assertIn("AvatarBitmaps.cachedUri(avatarUri)", self.avatar)
        self.assertIn("Avatar(name = displayName, modifier = Modifier.fillMaxSize(), size = size)", self.avatar)
        self.assertIn("var broken by remember(path)", self.player)
        # Плеер не живёт вечно: снятие с композиции освобождает декодер.
        self.assertIn("player.release()", self.player)

    def test_live_playback_is_opt_in_and_reaches_the_chat_list(self):
        # Флаг явный: по умолчанию постер, иначе плеер заводился бы на каждом
        # мелком аватаре (отклики, просмотр истории) и сажал батарею.
        self.assertIn("playVideo: Boolean = false", self.avatar)
        self.assertIn("if (video != null && playVideo)", self.avatar)
        stories = read(JAVA / "ui/components/Stories.kt")
        self.assertIn("playVideo = true", stories)
        # Плеер заводится только у своей ячейки ленты, не у чужих историй.
        bubble = stories.split("private fun StoryBubble(")[1].split("\n@Composable")[0]
        self.assertIn("playVideo = true", bubble)
        self.assertNotIn("playVideo", stories.split("fun StoryViewerDialog(")[1])

    # 5) постер наружу, ролик себе ------------------------------------------
    def test_poster_goes_out_as_the_regular_avatar_and_the_clip_stays_private(self):
        self.assertIn("AvatarFiles.saveCoverFile(context, exported.cover)", self.installer)
        self.assertIn("AvatarHolder.set(context, it)", self.installer)
        self.assertIn("AvatarFiles.videoTarget(context)", self.installer)
        self.assertIn("AvatarFiles.pruneVideos(context, keep = target)", self.installer)
        self.assertIn("VideoAvatarStore.set(", self.installer)
        files = read(JAVA / "util/AvatarFiles.kt")
        self.assertIn("fun saveCoverFile(", files)
        self.assertIn("fun videoTarget(", files)
        self.assertIn("fun pruneVideos(", files)
        # Убрать видеоаватар — значит удалить и файл, а не только запись.
        self.assertIn("fun clear(context: Context)", self.installer)
        self.assertIn("VideoAvatarInstaller.clear(context)", self.settings)

    # 6) локализация ---------------------------------------------------------
    def test_avatar_strings_exist_in_both_locales(self):
        ru = read(RES / "values/strings.xml")
        en = read(RES / "values-en/strings.xml")
        names = [
            "av_video_editor_title", "av_video_source_hint", "av_video_trim_hint",
            "av_video_cover_label", "av_video_install", "av_video_installing",
            "av_video_result_hint", "av_video_reset_hint", "av_video_reset_done_body",
        ]
        for name in names:
            for text, where in ((ru, "values"), (en, "values-en")):
                self.assertEqual(
                    len(re.findall(rf'<string name="{name}"', text)), 1,
                    f"{name} must exist exactly once in {where}",
                )
        # Подстановка кадра-постера обязана совпадать в переводе.
        for text, where in ((ru, "values"), (en, "values-en")):
            match = re.search(r'<string name="av_video_cover_label">(.*?)</string>', text, re.S)
            self.assertEqual(["%1$s"], re.findall(r"%\d+\$[ds]", match.group(1)), where)

    # 7) god-файл ------------------------------------------------------------
    def test_the_whole_flow_lives_outside_the_settings_god_file(self):
        self.assertIn("ProfileAvatarDialogs(onDismiss = { showAvatarPicker = false })", self.settings)
        flow = read(JAVA / "ui/screens/settings/ProfileAvatarDialogs.kt")
        for token in ("AvatarPickerDialog(", "AvatarCropDialog(", "VideoAvatarComposer(",
                      "cameraPermission.launch(android.Manifest.permission.CAMERA)",
                      "AvatarFiles.saveCropped"):
            self.assertIn(token, flow)
        # В настройках не осталось второго пути постановки видеоаватара.
        self.assertNotIn("videoAvatarPicker", self.settings)
        self.assertNotIn("prepareVideoAvatar", self.settings)
        self.assertNotIn("prepareVideoAvatar", read(JAVA / "util/AvatarFiles.kt"))

    def test_the_clip_mirrors_to_the_second_device_with_the_profile(self):
        mirror = read(JAVA / "data/mirror/ProfileMirror.kt")
        sync = read(JAVA / "data/mirror/MirrorSync.kt")
        # Ролик едет тем же resumable каналом, что аватар и обои, но со своим
        # потолком: 24 МБ в три раза больше картинки.
        self.assertIn('const val FIELD_VAV = "vav"', mirror)
        self.assertIn("const val VIDEO_MAX_BYTES = VideoAvatarPolicy.MAX_EXPORT_BYTES", mirror)
        self.assertIn("private fun capFor(kind: String): Long", mirror)
        self.assertIn("fun transferOf(state: State, kind: String)", mirror)
        self.assertIn("FIELD_AV, FIELD_VAV -> File(context.filesDir, \"avatar\")", mirror)
        self.assertIn("VideoAvatarStore.set(", mirror)
        self.assertIn("AvatarFiles.pruneVideos(app, keep = target)", mirror)
        # Отпечаток видеоаватара — отдельная строка hello/hb: старая сборка её
        # не знает, и общий отпечаток гонял бы снимок профиля каждые 10 секунд.
        self.assertIn("fun videoDigest(): String", mirror)
        self.assertIn("fun cachedVideoDigest(context: Context): String", mirror)
        self.assertIn("fun notePartnerVideoDigest(digest: String)", mirror)
        # Общий отпечаток профиля (name|nick|theme|avSha|wallSha) не меняется:
        # иначе устройства на разных сборках вечно запрашивали бы снимок.
        self.assertIn('listOf(name, nick, theme, avSha, wallSha)', mirror)
        self.assertIn('.put("pv", ProfileMirror.cachedVideoDigest(context))', sync)
        self.assertIn('ProfileMirror.notePartnerVideoDigest(obj.optString("pv", ""))', sync)
        self.assertIn("pv != ProfileMirror.cachedVideoDigest(context)", sync)
        # Установка и уборка видеоаватара публикуют поле второму устройству.
        self.assertIn("ProfileMirror.FIELD_VAV", self.installer)
        self.assertIn("ProfileMirror.noteLocalChange(", self.installer)

    def test_policy_is_pure_and_covered_by_the_jvm_gate(self):
        # Чистый файл: без Android его компилирует и гоняет check-chat-history.sh.
        self.assertNotIn("import android.", self.rules)
        self.assertNotIn("android.content.Context", self.rules)
        harness = read(ROOT / "scripts/ci/check-chat-history.sh")
        self.assertIn("VideoAvatarPolicy.kt", harness)
        self.assertIn("VideoAvatarPolicyTest.kt", harness)
        self.assertIn("com.vladimir.messenger.data.profile.VideoAvatarPolicyTest", harness)
        # Зеркало видеоаватара (чистая арифметика снимка) гоняется полным
        # компиляционным гейтом вместе с тестом дня рождения.
        compile_step = read(ROOT / "scripts/ci/check-android-compile.sh")
        self.assertIn("*ProfileMirrorVideoAvatarTest*", compile_step)
        self.assertIn("*VideoAvatarPolicyTest*", compile_step)
        self.assertTrue(
            (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/mirror/"
             "ProfileMirrorVideoAvatarTest.kt").is_file(),
        )

    # 8) видеокружки собеседников -------------------------------------------
    def test_peer_video_avatars_play_everywhere(self):
        # Показ: у PeerAvatar есть peerId и общий плеер крутит ролик поверх
        # постера (запрос владельца 2026-11-06: «видиокружочки профиля крутились
        # на профиле чата и во всех других местах»).
        self.assertIn("peerId: String? = null", self.peer)
        self.assertIn("PeerVideoAvatarLayer(peerId = peerId)", self.peer)
        self.assertIn("VideoAvatarPlayback(path = video.absolutePath", self.peer)
        self.assertIn("PeerVideoAvatarStore.fileFor(", self.peer)
        # Все места, где встречается аватар собеседника, передают его id.
        ui = JAVA / "ui"
        peer_call_sites = 0
        for path in ui.rglob("*.kt"):
            if path.name in ("ApuVipRing.kt", "MyAvatar.kt"):
                continue
            peer_call_sites += len(re.findall(r"peerId = ", read(path)))
        self.assertGreaterEqual(peer_call_sites, 12, "peerId должен дойти до всех мест показа")
        moderation = read(ui / "components/ApuModerationDialog.kt")
        self.assertIn("peerId = authorId", moderation)
        self.assertIn("avatarB64 = avatars[authorId]", moderation)
        # Провод: анонс и просьба живут отдельным видом группового конверта —
        # старые клиенты глотают его как неразобранный пакет и не видят текст в чате.
        self.assertIn('PREFIX = "APUGRP1|avv1|"', self.wire)
        self.assertIn("fun announce(", self.wire)
        self.assertIn("fun ready(", self.wire)
        self.assertIn("fun reserved(", self.wire)
        # Приём: GroupRouter разбирает вид avv1 до групповых пакетов.
        self.assertIn("AvatarVideoWire.isPacket(text)", self.group_router)
        self.assertIn("avatarVideos.receive(senderId, text)", self.group_router)
        # Обмен: владелец анонсирует sha, отвечает на просьбу, повторяет по расписанию.
        self.assertIn("fun receive(senderId: String, text: String)", self.coord)
        self.assertIn("announceAll(", self.coord)
        self.assertIn("sendMyVideoTo(", self.coord)
        self.assertIn("catchUpRequests(", self.coord)
        self.assertIn("AvatarVideoGossip.attach(this)", self.coord)
        # Смена своего видеоаватара уходит контактам сразу, а не к следующему анонсу.
        self.assertIn("AvatarVideoGossip.onMyVideoChanged(context)", self.my_video_store)
        # Хранилище проверяет sha256 и размер и ограничено по объёму.
        self.assertIn("sha256Of(", self.store)
        self.assertIn("MAX_TOTAL_BYTES", self.store)
        self.assertIn("fun sweep(", self.store)
        self.assertIn("fun install(", self.store)
        # Файлы приходят обычной передачей и не попадают в чат как вложение.
        self.assertIn("AvatarVideoWire.reserved(", self.inbox)
        self.assertIn("PeerVideoAvatarStore.install(", self.inbox)
        router = read(JAVA / "data/file/FileTransferRouter.kt")
        self.assertIn("AvatarVideoWire.reserved(displayName)", router)
        self.assertIn("SpecialMediaInbox.accept(", router)
        # Сервисный конверт не догоняется на старых клиентах и не растит god-файлы:
        # приём и отправка живут в новых файлах data/avatavideo/.
        self.assertNotIn("AvatarVideo", read(JAVA / "data/group/GroupWire.kt"))
        self.assertNotIn("AvatarVideo", read(JAVA / "data/group/GroupRepository.kt"))

    # 9) доставка и подпись -------------------------------------------------
    def test_announces_never_look_like_retries_and_caption_stays_out_of_profile(self):
        # Application стартует до MainActivity: анонс обязан брать состояние
        # своего видео уже загруженным, иначе уходит «ролика нет» и у друзей
        # навсегда остаётся постер вместо ролика.
        self.assertIn("VideoAvatarStore.init(context)", self.coord)
        self.assertLess(
            self.coord.index("VideoAvatarStore.init(context)"),
            self.coord.index("announceAll(force = true)"),
        )
        # Идентификаторы конвертов — новые на каждую отправку: постоянный id
        # ядро и приёмник считают повтором доставленного пакета и молча глотают.
        self.assertIn("UUID.randomUUID()", self.coord)
        self.assertIn('messageId("avatar-video-announce")', self.coord)
        self.assertIn('messageId("avatar-video-ready")', self.coord)
        # Повторная просьба — своя на каждый ролик: новый видеоаватар
        # запрашивается сразу, не дожидаясь конца таймаута по старому.
        self.assertIn('val key = "$READY_AT_PREFIX$ownerId:$sha256"', self.coord)
        # avv1 — служебный конверт, как контакт-сброс: не растит долгий журнал.
        ephemeral = read(JAVA / "data/security/EphemeralControl.kt")
        self.assertIn('"APUGRP1|avv1|"', ephemeral.split("fun isReplaceable", 1)[1])
        # Подпись видеоаватара в профиле текстом не показывается (запрос
        # владельца 2026-11-06: «подпись в профиле текстом ненужна»); поле
        # подписи в общем редакторе остаётся — паритет с видеоисториями.
        self.assertNotIn("VideoAvatarCaption", self.settings)
        self.assertFalse((JAVA / "ui/components/VideoAvatarCaption.kt").exists())
        # Поле подписи текстом убрано и из окна видеоаватара (запрос владельца
        # 2026-11-06: «Поле ввода текста так и осталось. Нужно убрать»):
        # редактор один, но поле подписи показывается только видеоисториям.
        self.assertIn("showCaption: Boolean = true", self.editor)
        self.assertIn("if (showCaption) ApuFormTextField(value = draft.caption", self.editor)
        self.assertIn("caption = it.take(StoryVideoDraft.MAX_CAPTION_CHARS)", self.editor)
        self.assertIn("showCaption = false", self.composer)
        self.assertNotIn("draft.caption", self.composer)
        self.assertIn("MAX_CAPTION_CHARS", self.rules)

    # 10) просьба ролика и кольцо загрузки ----------------------------------
    def test_ready_asks_the_video_owner_and_the_ring_shows_the_download(self):
        # ГЛАВНОЕ (запрос владельца 2026-11-06: «Аватарки у друзей так и
        # статичны»): поле «владелец» в просьбе — чей ролик просят. Со своим
        # id владелец молча отбрасывал просьбу, файл не уходил никогда.
        self.assertIn("AvatarVideoWire.ready(ownerId, sha256)", self.coord)
        self.assertNotIn("AvatarVideoWire.ready(myId", self.coord)
        # Старые клиенты клали в поле свой id — обе формы покрыты JVM-тестом.
        transfer_policy = read(JAVA / "data/avatavideo/AvatarVideoTransferPolicy.kt")
        self.assertIn("AvatarVideoTransferPolicy.acceptsReady(", self.coord)
        self.assertIn("packetOwner == myId || packetOwner == senderId", transfer_policy)
        self.assertIn("lastReadyOwnerAtMs:", self.coord)  # не наследуем старый ошибочный throttle
        self.assertIn("CoreStatus.ready.collect", self.coord)
        self.assertIn("restoreCompletedVideos()", self.coord)
        self.assertIn("fileRouter.get().pumpOutgoing()", self.coord)
        self.assertIn("if (!accepted) requestMissingMessageBinding(ownerId)", self.coord)
        self.assertIn("if (!accepted) requestMissingMessageBinding(peer)", self.coord)
        self.assertIn("if (contactDao.getContactById(senderId) == null) return", self.coord)
        self.assertIn("file.name !in activeParts", self.store)
        # Метаданные уведомляют открытый экран о новом ролике, а прогресс
        # берётся только у совпадающего отправителя, sha и размера. Room
        # присылает обновления сам; бесконечного опроса и анимации в простое нет.
        announce = self.store.split("fun noteAnnounce(", 1)[1].split("fun install(", 1)[0]
        self.assertIn("_changed.update", announce)
        self.assertIn("fun pumpDownloads(", self.coord)
        self.assertIn("transferDao.observeAvatarVideoIncoming()", self.coord)
        fake_dao = read(ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/file/FakeFileTransferDao.kt")
        self.assertIn("override fun observeAvatarVideoIncoming()", fake_dao)
        self.assertIn('row.peerNodeId != ownerId', transfer_policy)
        self.assertIn('row.fileSha256 != sha256', transfer_policy)
        self.assertIn('row.totalBytes != expectedBytes', transfer_policy)
        self.assertIn('AvatarVideoWire.fileName(sha256)', transfer_policy)
        self.assertIn('MAX_UNVERIFIED_PROGRESS = 0.95f', transfer_policy)
        self.assertIn("VideoAvatarLoadingRing(", self.peer)
        self.assertIn("PeerVideoAvatarStore.downloads", self.peer)
        self.assertIn("PeerVideoAvatarStore.metaFor(context, peerId)", self.peer)
        self.assertIn("startAngle = -90f, sweepAngle = 360f * fraction", self.peer)
        self.assertNotIn("rememberInfiniteTransition", self.peer)
        self.assertIn("onRenderedFirstFrame()", self.player)
        self.assertIn("VideoAvatarLoadingRing(progress = 1f", self.peer)
        self.assertIn("fun setDownloads(", self.store)
        # Повторный файл с тем же sha должен установиться из новой передачи,
        # а не из старой COMPLETE-строки, у которой файла уже может не быть.
        router = read(JAVA / "data/file/FileTransferRouter.kt")
        self.assertIn('it.messageId == messageId && it.direction == "INCOMING"', router)
        self.assertTrue((ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/avatavideo/"
                         "AvatarVideoTransferPolicyTest.kt").is_file())
        # «Логи» показывают цепочку обмена: по отчёту видно, где она рвётся.
        self.assertIn("fun report(context: Context): String", self.store)
        self.assertIn("PeerVideoAvatarStore.bump(", self.coord)
        self.assertIn("PeerVideoAvatarStore.bump(", self.inbox)
        self.assertIn(
            "PeerVideoAvatarStore.report(",
            read(JAVA / "data/diagnostics/TransferDiagnostics.kt"),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
