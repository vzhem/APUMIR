package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryFaceFiltersTest {
    private val box = StoryFaceBox(left = 300f, top = 200f, right = 500f, bottom = 460f)

    private fun face(
        leftEye: StoryFacePoint? = null,
        rightEye: StoryFacePoint? = null,
        nose: StoryFacePoint? = null,
        roll: Float = 0f,
        faceBox: StoryFaceBox = box,
    ) = StoryFace(faceBox, leftEye, rightEye, nose, roll)

    @Test
    fun emptyFilterIsInactiveAndAnyPartActivatesIt() {
        assertFalse(StoryFaceFilter.NONE.isActive)
        assertTrue(StoryFaceFilter(ears = StoryEars.FOX).isActive)
        assertTrue(StoryFaceFilter(nose = StoryNose.PIG).isActive)
        assertTrue(StoryFaceFilter(eyes = StoryEyes.OWL).isActive)
    }

    @Test
    fun tinyOrDegenerateFacesAreSkipped() {
        assertNull(StoryFaceGeometry.layout(face(faceBox = StoryFaceBox(0f, 0f, 20f, 20f))))
        assertNull(StoryFaceGeometry.layout(face(faceBox = StoryFaceBox(10f, 10f, 10f, 200f))))
        assertNull(StoryFaceGeometry.layout(face(roll = Float.NaN)))
    }

    @Test
    fun detectedLandmarksBecomeAnchorsForEyesAndNose() {
        val layout = StoryFaceGeometry.layout(
            face(
                leftEye = StoryFacePoint(350f, 300f),
                rightEye = StoryFacePoint(450f, 300f),
                nose = StoryFacePoint(400f, 350f),
            ),
        )!!
        assertEquals(350f, layout.leftEye.x, 0.001f)
        assertEquals(300f, layout.leftEye.y, 0.001f)
        assertEquals(450f, layout.rightEye.x, 0.001f)
        assertEquals(400f, layout.nose.x, 0.001f)
        assertEquals(350f, layout.nose.y, 0.001f)
        // Глаз — половина расстояния между зрачками (100px), значит 50px в диаметре.
        assertEquals(50f, layout.leftEye.size, 0.001f)
    }

    @Test
    fun missingLandmarksFallBackInsideTheFaceBox() {
        val layout = StoryFaceGeometry.layout(face())!!
        assertTrue(layout.leftEye.x < layout.rightEye.x)
        assertTrue(layout.leftEye.y > box.top && layout.leftEye.y < box.bottom)
        assertTrue(layout.nose.y > layout.leftEye.y)
        assertTrue(layout.nose.y < box.bottom)
    }

    @Test
    fun earsSitOnTheTopCornersOfTheHead() {
        val layout = StoryFaceGeometry.layout(face())!!
        assertTrue(layout.leftEar.x < box.centerX)
        assertTrue(layout.rightEar.x > box.centerX)
        assertEquals(layout.leftEar.x, box.centerX - (layout.rightEar.x - box.centerX), 0.001f)
        assertTrue(layout.leftEar.y < box.centerY)
        assertTrue(layout.leftEar.size > 0f)
    }

    @Test
    fun rollIsMirroredOnTheFrontCamera() {
        assertEquals(20f, StoryFaceGeometry.screenRoll(20f, mirrored = false), 0.001f)
        assertEquals(-20f, StoryFaceGeometry.screenRoll(20f, mirrored = true), 0.001f)
    }

    @Test
    fun mouthCheeksAndBrowsFallBackOrFollowLandmarks() {
        val fallback = StoryFaceGeometry.layout(face())!!
        assertTrue(fallback.mouth.y > box.centerY)
        assertTrue(fallback.leftCheek.x < box.centerX && fallback.rightCheek.x > box.centerX)
        assertTrue(fallback.leftBrow.y < fallback.leftEye.y)

        val mouthLeft = StoryFacePoint(370f, 400f)
        val mouthRight = StoryFacePoint(430f, 400f)
        val real = StoryFaceGeometry.layout(
            face(leftEye = StoryFacePoint(350f, 300f), rightEye = StoryFacePoint(450f, 300f)).copy(
                mouthLeft = mouthLeft,
                mouthRight = mouthRight,
            ),
        )!!
        assertEquals(400f, real.mouth.x, 0.001f)
        assertEquals(400f, real.mouth.y, 0.001f)
        assertEquals(60f, real.mouth.size, 0.001f)
    }

    @Test
    fun filterWithNewPartsIsActive() {
        assertTrue(StoryFaceFilter(lips = StoryLips.RED).isActive)
        assertTrue(StoryFaceFilter(brows = StoryBrows.BUSHY).isActive)
        assertTrue(StoryFaceFilter(whiskers = StoryWhiskers.CAT).isActive)
        assertTrue(StoryFaceFilter(cheeks = StoryCheeks.BLUSH).isActive)
    }

    @Test
    fun newVariantsAreSelectableAndActive() {
        assertTrue(StoryFaceFilter(brows = StoryBrows.THIN).isActive)
        assertTrue(StoryFaceFilter(cheeks = StoryCheeks.FRECKLES).isActive)
        assertEquals(3, StoryBrows.entries.size)
        assertEquals(3, StoryCheeks.entries.size)
    }

    @Test
    fun animalVariantCountsMatchArtAssets() {
        // Сколько вариантов в каждом выборе (кроме «Без»). Должны совпадать с картинками в StoryStickers.
        assertEquals(12, StoryEars.entries.size - 1)
        assertEquals(12, StoryNose.entries.size - 1)
        assertEquals(14, StoryEyes.entries.size - 1)
        assertEquals(5, StoryHair.entries.size - 1)
        assertEquals(5, StoryBeard.entries.size - 1)
        assertEquals(5, StoryMustache.entries.size - 1)
    }

    @Test
    fun hairAnchorSitsOnTopOfHead() {
        val layout = StoryFaceGeometry.layout(face())!!
        assertEquals(box.top, layout.hair.y, 0.001f)
        assertTrue(layout.hair.size > box.width)
        assertTrue(StoryFaceFilter(hair = StoryHair.BOB).isActive)
    }

    @Test
    fun beardAnchorSitsAtMouthLine() {
        val layout = StoryFaceGeometry.layout(face())!!
        assertEquals(layout.mouth.y, layout.beard.y, 0.001f)
        assertTrue(layout.beard.size > 0f)
        assertTrue(StoryFaceFilter(beard = StoryBeard.FULL).isActive)
    }

    @Test
    fun mustacheSitsAboveTheLips() {
        val layout = StoryFaceGeometry.layout(face())!!
        assertTrue(layout.mustache.y < layout.mouth.y)
        assertTrue(StoryFaceFilter(mustache = StoryMustache.WALRUS).isActive)
    }
}
