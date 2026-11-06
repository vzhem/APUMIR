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
  7) поток не живёт в god-файле настроек.

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
        self.editor = read(JAVA / "ui/components/StoryVideoEditor.kt")
        self.camera = read(JAVA / "ui/components/StoryVideoCamera.kt")
        self.story_composer = read(JAVA / "ui/components/StoryVideoComposer.kt")
        self.settings = read(JAVA / "ui/screens/settings/SettingsScreen.kt")

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
        # SurfaceView уходит из-под круглой маски Compose — только TextureView.
        self.assertIn("StoryVideoSurface(", self.avatar)
        # `VideoView(` и `android.widget.VideoView` — прежний способ: он и был
        # квадратным поверх круглого аватара. В комментарии слово остаться может.
        self.assertNotIn("VideoView(", self.avatar)
        self.assertNotIn("android.widget.VideoView", self.avatar)
        self.assertIn("Player.REPEAT_MODE_ONE", self.avatar)
        self.assertIn("player.volume = 0f", self.avatar)
        # В фоне плеер стоит: аватар не должен играть в кармане.
        self.assertIn("Lifecycle.Event.ON_STOP -> player.pause()", self.avatar)
        self.assertIn("Lifecycle.Event.ON_START -> player.play()", self.avatar)
        # Постер/инициалы остаются под видео: ролик мог не доехать зеркалом.
        self.assertIn("AvatarBitmaps.cachedUri(avatarUri)", self.avatar)
        self.assertIn("Avatar(name = displayName, modifier = Modifier.fillMaxSize(), size = size)", self.avatar)
        self.assertIn("var broken by remember(path)", self.avatar)
        # Плеер не живёт вечно: снятие с композиции освобождает декодер.
        self.assertIn("player.release()", self.avatar)

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

    def test_policy_is_pure_and_covered_by_the_jvm_gate(self):
        # Чистый файл: без Android его компилирует и гоняет check-chat-history.sh.
        self.assertNotIn("import android.", self.rules)
        self.assertNotIn("android.content.Context", self.rules)
        harness = read(ROOT / "scripts/ci/check-chat-history.sh")
        self.assertIn("VideoAvatarPolicy.kt", harness)
        self.assertIn("VideoAvatarPolicyTest.kt", harness)
        self.assertIn("com.vladimir.messenger.data.profile.VideoAvatarPolicyTest", harness)


if __name__ == "__main__":
    unittest.main(verbosity=2)
