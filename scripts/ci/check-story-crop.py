#!/usr/bin/env python3
"""Контракты редактора фото истории: обрезка как хочешь (владелец 2026-10-15).

Run: python3 scripts/ci/check-story-crop.py

Проверка по источникам: она ловит откат разводки (снимок снова уходит без
редактора, предпросмотр и публикация разъехались по разным функциям, строка
осталась в одной локали) и откат лимитов провода. Геометрию (матрицу, «кадр
закрыт снимком», поворот, сдвиг, приближение) считает песочница
`tools/sandbox/story_crop_reference.py` - эта проверка запускает её и падает,
если свойства перестали выполняться. Компиляции Kotlin и проверки на телефоне
здесь нет: их делают `scripts/ci/check-android-compile.sh` и владелец.
"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"

NEW_STRINGS = (
    "story_edit_photo",
    "story_edit_photo_open",
    "story_crop_original",
    "story_crop_rotate",
    "story_crop_mirror",
    "story_crop_flip",
    "story_crop_reset",
    "story_crop_straighten_value",
    "story_crop_hint",
    "story_crop_corner",
    "story_photo_edit_failed",
)


def source(path):
    return Path(path).read_text()


def flat(text):
    """Схлопнуть пробелы: вызовы в исходниках перенесены по строкам."""
    return re.sub(r"\s+", " ", text)


def functions(text):
    """Имя функции -> её текст (до следующей функции)."""
    parts = re.split(r"\bfun (?:internal |private )?(\w+)\(", text)
    return {parts[index]: parts[index + 1] for index in range(1, len(parts) - 1, 2)}


class StoryCropContracts(unittest.TestCase):
    def test_story_frame_matches_the_story_canvas(self):
        """Кадр редактора - тот же прямоугольник 9:16, что и у превью истории."""
        canvas = source(MAIN / "ui/components/StoryContentCanvas.kt")
        self.assertIn("360.dp", canvas)
        self.assertIn("640.dp", canvas)
        crop = flat(source(MAIN / "data/story/StoryCrop.kt"))
        self.assertIn("const val STORY_ASPECT = 360f / 640f", crop)
        self.assertIn("const val MAX_SIDE = 1280", crop)
        self.assertIn("const val STRAIGHTEN_LIMIT = 45f", crop)
        self.assertIn("const val MIN_FRAME = 0.12f", crop)
        for name in ("ASPECT_ORIGINAL", "ASPECT_FREE", "ASPECT_SQUARE", "ASPECT_PORTRAIT",
                     "ASPECT_STORY", "ASPECT_WIDE"):
            self.assertIn('const val %s = "' % name, crop)
        for part in ("fun initialState(", "fun withCorner(", "fun withQuarter(", "fun withStraighten(",
                     "fun withRotation(", "fun toggleFlipH(", "fun toggleFlipV(", "fun withZoom(",
                     "fun panBy(", "fun reset(", "fun covers(", "fun bind(", "fun renderSize(",
                     "fun matrix(", "fun draw(", "fun render("):
            self.assertIn(part, crop, part + " пропала из геометрии кадра")

    def test_preview_and_publication_draw_with_one_function(self):
        """Совпадение «что видно» и «что уехало» держится общим вызовом рисования.

        Предпросмотр зовёт StoryCrop.draw напрямую, публикация - StoryCrop.render,
        а тот внутри рисует тем же draw: дорога рисования одна на двоих.
        """
        callers = []
        for path in sorted(MAIN.rglob("*.kt")):
            if path.name == "StoryCrop.kt":
                continue
            if "StoryCrop.draw(" in path.read_text():
                callers.append(path.name)
        self.assertEqual(["StoryPhotoEditor.kt"], callers, "рисовать кадр должен только редактор")
        crop = flat(source(MAIN / "data/story/StoryCrop.kt"))
        render = functions(crop)["render"]
        self.assertIn("draw(canvas, bitmap, state", render, "render обязан рисовать через draw")
        self.assertIn("StoryCrop.render(", flat(source(MAIN / "data/story/StoryPhoto.kt")))

    def test_photo_pipeline_keeps_the_wire_limits(self):
        """Декод один, собирает кадр ровно publishEdited, лимиты провода прежние."""
        photo = flat(source(MAIN / "data/story/StoryPhoto.kt"))
        self.assertIn("const val MAX_B64 = 160_000", photo)
        self.assertIn("const val SOURCE_MAX_SIDE = 2048", photo)
        for side in (1280, 1024, 800, 640, 480):
            self.assertIn(str(side), photo)
        for quality in (85, 70, 55, 40):
            self.assertIn(str(quality), photo)
        for part in ("fun loadForEdit(", "fun publishEdited(", "fun encode(", "fun prepare(",
                     "fun createCaptureTarget("):
            self.assertIn(part, photo, part + " пропала из подготовки фото")
        for orientation in range(2, 9):
            self.assertIn("%d ->" % orientation, photo)
        bodies = functions(photo)
        decoders = sorted(name for name, body in bodies.items() if "BitmapFactory.decodeStream(" in body)
        self.assertEqual(["loadForEdit"], decoders, "декод должен быть ровно один: в loadForEdit")
        croppers = sorted(name for name, body in bodies.items() if "StoryCrop.render(" in body)
        self.assertEqual(["publishEdited"], croppers, "кадр собирает ровно publishEdited")
        sizers = sorted(name for name, body in bodies.items() if "StoryCrop.renderSize(" in body)
        self.assertEqual(["publishEdited"], sizers)

    def test_composer_always_goes_through_the_editor(self):
        """После съёмки и выбора фото история не уходит как есть."""
        stories = flat(source(MAIN / "ui/components/Stories.kt"))
        self.assertIn("StoryPhoto.loadForEdit(", stories)
        self.assertIn("StoryPhoto.publishEdited(", stories)
        self.assertIn("StoryPhotoEditorDialog(", stories)
        self.assertNotIn("StoryPhoto.prepare(", stories)
        self.assertIn("editSource = null", stories)
        self.assertIn("R.string.story_photo_edit_failed", stories)
        # Отмена редактора возвращает к прежнему фото, а не гасит его.
        self.assertIn("onCancel = { editing = false }", stories)

    def test_editor_uses_house_controls_and_guards_gestures(self):
        """Фирменные кнопки, никакой стоковой мелочи, живые жесты."""
        editor = source(MAIN / "ui/components/StoryPhotoEditor.kt")
        flat_editor = flat(editor)
        for house in ("ApuPremiumChip(", "ApuPremiumButton(", "ApuPremiumSlider(", "ApuTextAction("):
            self.assertIn(house, flat_editor, house + " не используется в редакторе")
        for stock in ("AlertDialog(", "TextButton(", "OutlinedTextField("):
            self.assertNotIn(stock, editor, stock + " в редакторе выглядит чужеродно")
        # Жест живёт дольше одной перерисовки: значения обязаны читаться свежими.
        self.assertGreaterEqual(flat_editor.count("rememberUpdatedState("), 2)
        self.assertIn("detectTransformGestures {", flat_editor)
        self.assertIn("detectDragGestures {", flat_editor)
        self.assertIn("StoryCrop.withCorner(", flat_editor)
        self.assertIn("StoryCrop.aspectPresets.forEach", flat_editor)
        self.assertIn("story_crop_corner", editor)
        # Кадр истории рисуется на своей площадке 9:16, а не растягивается на весь экран.
        self.assertIn("StoryCrop.STORY_ASPECT", flat_editor)

    def test_every_new_string_exists_in_both_locales(self):
        ru = source(RES / "values/strings.xml")
        en = source(RES / "values-en/strings.xml")
        for key in NEW_STRINGS:
            self.assertIn('name="%s"' % key, ru, key + " нет в RU")
            self.assertIn('name="%s"' % key, en, key + " нет в EN")

    def test_jvm_checks_are_wired_into_ci(self):
        test = source(TEST / "data/story/StoryCropTest.kt")
        for part in ("initialStateShowsTheWholePhoto", "fourQuartersReturnToTheOriginalFrame",
                     "aspectPresetsKeepTheRatioAndStayInsideTheStoryFrame",
                     "straightenIsLimitedAndNeverOpensCorners", "twoFingerRotationSplitsIntoQuartersAndRemainder",
                     "panStopsAtTheEdgeOfThePhoto", "cornerDragMakesAFreeFrameWithoutInvertingIt",
                     "renderSizeKeepsTheFrameRatioAndTheStoryLimit", "randomStatesStayBoundedAndCovered"):
            self.assertIn(part, test, part + " пропал из JVM-проверок")
        ci = source(ROOT / "scripts/ci/check-android-compile.sh")
        self.assertIn("*StoryCropTest*", ci)

    def test_geometry_reference_still_holds(self):
        """Песочница с проверками геометрии обязана проходить целиком."""
        script = ROOT / "tools/sandbox/story_crop_reference.py"
        self.assertTrue(script.exists(), "нет песочницы с проверками геометрии")
        done = subprocess.run(
            [sys.executable, str(script)],
            cwd=ROOT, capture_output=True, text=True, timeout=300,
        )
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("все прошли", done.stdout, done.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
