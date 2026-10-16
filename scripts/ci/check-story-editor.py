#!/usr/bin/env python3
"""Source wiring only; Android renderer/IME tests are a separate device gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


class StoryEditorContracts(unittest.TestCase):
    def test_editor_and_viewer_share_the_canvas(self):
        source = (MAIN / "ui/components/Stories.kt").read_text()
        self.assertEqual(2, source.count("StoryContentCanvas("))
        self.assertNotIn("singleLine = true", source)
        self.assertIn("story_hide_keyboard", source)

    def test_viewer_fills_window_and_measures_content_between_controls(self):
        source = (MAIN / "ui/components/Stories.kt").read_text()
        viewer = source.split("fun StoryViewerDialog(")[1].split("fun StoryComposerDialog(")[0]
        self.assertIn("window.setLayout(", viewer)
        self.assertEqual(2, viewer.count("android.view.ViewGroup.LayoutParams.MATCH_PARENT"))
        self.assertIn("window.setBackgroundDrawable", viewer)
        self.assertIn("background(Color.Black).safeDrawingPadding()", viewer)
        self.assertIn("fillMaxWidth().weight(1f).clipToBounds()", viewer)
        self.assertNotIn("padding(top = 96.dp", viewer)
        self.assertNotIn("align(Alignment.BottomCenter)", viewer)
        self.assertIn("rememberSaveable(authorId)", viewer)
        self.assertIn("rememberSaveable(current.id)", viewer)

    def test_enter_is_not_a_done_or_publish_action(self):
        source = (MAIN / "ui/components/StoryContentCanvas.kt").read_text()
        self.assertIn("singleLine = false", source)
        self.assertIn("ImeAction.Default", source)
        self.assertNotIn("onDone", source)
        self.assertNotIn("ImeAction.Send", source)

    def test_layout_scales_the_artwork_not_the_wrapping_width(self):
        source = (MAIN / "ui/components/StoryContentCanvas.kt").read_text()
        for part in ("Constraints.fixed(width, height)", "placeWithLayer", "360.dp", "640.dp",
                     "LocalDensity provides Density(density.density, 1f)", "hasVisualOverflow",
                     "textStyle = textStyle", "style = textStyle", "repeat(8)"):
            self.assertIn(part, source)

    def test_photo_shape_reaches_viewer_and_storage(self):
        source = (MAIN / "ui/components/Stories.kt").read_text()
        canvas = (MAIN / "ui/components/StoryContentCanvas.kt").read_text()
        store = (MAIN / "data/story/StoryStore.kt").read_text()
        self.assertIn("photoCircle = story.photoCircle", source)
        self.assertIn("photoCircle = photoCircle", source)
        self.assertIn("aspectRatio(1f).clip(CircleShape)", canvas)
        self.assertIn("ContentScale.Crop", canvas)
        self.assertIn('.put("photoCircle", story.photoCircle)', store)
        self.assertIn('.put("photoCircle", s.photoCircle)', store)
        self.assertIn('o.optBoolean("photoCircle", false)', store)

    def test_video_has_two_formats_and_a_clippable_shared_surface(self):
        selector = (MAIN / "ui/components/StoryVideoShapeSelector.kt").read_text()
        self.assertEqual(2, selector.count("ApuPremiumChip("))
        for name in ("story_photo_rectangle", "story_photo_circle"):
            self.assertIn(name, selector)
        surface = (MAIN / "ui/components/StoryVideoSurface.kt").read_text()
        for token in ("clip(CircleShape)", "RESIZE_MODE_ZOOM", "RESIZE_MODE_FIT"):
            self.assertIn(token, surface)
        layout = (ROOT / "android-app/app/src/main/res/layout/story_video_texture.xml").read_text()
        self.assertIn('app:surface_type="texture_view"', layout)
        for name in ("StoryVideoEditor.kt", "StoryVideoPlayer.kt"):
            self.assertIn("StoryVideoSurface(", (MAIN / "ui/components" / name).read_text())
        camera = (MAIN / "ui/components/StoryVideoCamera.kt").read_text()
        self.assertIn("ImplementationMode.COMPATIBLE", camera)
        self.assertIn("StoryVideoFrame(circle", camera)

    def test_video_shape_is_delivered_and_saved_not_burned_into_the_file(self):
        store = (MAIN / "data/story/StoryStore.kt").read_text()
        for token in ('.put("videoCircle", story.videoCircle)', '.put("videoCircle", s.videoCircle)',
                      'o.optBoolean("videoCircle", false)'):
            self.assertIn(token, store)
        vm = (MAIN / "ui/screens/chat/ChatListViewModel.kt").read_text()
        self.assertIn("videoCircle = exported.draft.circle", vm)
        exporter = (MAIN / "data/story/StoryVideoExporter.kt").read_text()
        self.assertIn("Presentation.createForHeight(exportHeight)", exporter)
        self.assertNotIn("LAYOUT_SCALE_TO_FIT", exporter)
        self.assertNotIn("draft.circle", exporter)
        player = (MAIN / "ui/components/StoryVideoPlayer.kt").read_text()
        self.assertIn("StoryVideoSurface(player, story.videoCircle", player)
        self.assertIn("photoCircle = story.videoCircle", player)

    def test_manual_whitespace_survives_publish(self):
        source = (MAIN / "ui/components/Stories.kt").read_text()
        self.assertIn("onPublish(text, bg, photo, photo != null && photoCircle)", source)
        vm = (MAIN / "ui/screens/chat/ChatListViewModel.kt").read_text()
        publish = vm.split("fun publishStory(")[1].split("fun deleteStory(")[0]
        self.assertNotIn(".trim()", publish)
        store = (MAIN / "data/story/StoryStore.kt").read_text()
        publish = store.split("fun publish(")[1].split("fun encode(")[0]
        self.assertNotIn(".trim()", publish)

    def test_regressions_are_wired_into_ci(self):
        ci = (ROOT / "scripts/ci/check-android-compile.sh").read_text()
        self.assertIn("*StoryPublishTest*", ci)
        self.assertIn("compileDebugAndroidTestKotlin", ci)
        device = (ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/ui/components/StoryEditorInstrumentedTest.kt").read_text()
        for part in ("performTextInput", "ImeAction.Default", "assertSameLines", "largeFont = true",
                     "photoCaption", "longTextAndManyNewlines"):
            self.assertIn(part, device)


if __name__ == "__main__":
    unittest.main(verbosity=2)
