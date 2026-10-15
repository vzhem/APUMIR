package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Геометрия редактора фото истории: чистая арифметика, без android.graphics.
 *
 * Проверяется то, за что отвечает [StoryCrop]: кадр всегда закрыт снимком
 * (пустых углов не бывает), чипы держат пропорцию, «сброс» и четыре поворота
 * возвращают исходный кадр, сдвиг упирается в край, а готовый кадр не крупнее
 * лимита истории. Рисование матрицей здесь не проверяется: его сверяет
 * `tools/sandbox/story_crop_reference.py` (в песочнице нет JVM).
 */
class StoryCropTest {
    private fun assertClose(expected: Double, actual: Double, delta: Double = 1e-4) {
        assertTrue("ожидалось $expected, получено $actual", abs(expected - actual) <= delta)
    }

    private fun assertCovers(state: StoryCrop.State, width: Int, height: Int) {
        assertTrue("кадр не закрыт снимком: $state", StoryCrop.covers(state, width, height))
    }

    @Test fun initialStateShowsTheWholePhoto() {
        for ((width, height) in listOf(4000 to 2250, 1080 to 1920, 1000 to 1000, 720 to 1280)) {
            val state = StoryCrop.initialState(width, height)
            assertCovers(state, width, height)
            assertFalse("исходное состояние уже считается правкой", StoryCrop.isEdited(state, width, height))
            val scale = StoryCrop.coverScale(state, width, height)
            assertClose(width.toDouble(), (state.frame.widthUnits / scale).toDouble(), 0.01)
            assertClose(height.toDouble(), (state.frame.heightUnits / scale).toDouble(), 0.01)
            assertClose(width.toDouble() / height, state.frame.aspect.toDouble(), 0.001)
        }
    }

    @Test fun aspectPresetsKeepTheRatioAndStayInsideTheStoryFrame() {
        for ((width, height) in listOf(1600 to 1200, 1080 to 1920, 900 to 900)) {
            var state = StoryCrop.initialState(width, height)
            for (id in StoryCrop.aspectPresets) {
                state = StoryCrop.withAspect(state, id, width, height)
                assertCovers(state, width, height)
                assertEquals(id, state.aspect)
                assertTrue(0f <= state.frame.left && state.frame.left < state.frame.right)
                assertTrue(state.frame.right <= 1f && 0f <= state.frame.top && state.frame.top < state.frame.bottom)
                assertTrue(state.frame.bottom <= 1f)
                val target = StoryCrop.presetAspect(id)
                if (target != null) assertClose(target.toDouble(), state.frame.aspect.toDouble(), 0.001)
            }
            // «Исходное» возвращает весь снимок целиком.
            state = StoryCrop.withAspect(state, StoryCrop.ASPECT_ORIGINAL, width, height)
            assertClose(width.toDouble() / height, state.frame.aspect.toDouble(), 0.001)
        }
    }

    @Test fun fourQuartersReturnToTheOriginalFrame() {
        for ((width, height) in listOf(4000 to 2250, 1080 to 1920, 720 to 1280)) {
            val start = StoryCrop.initialState(width, height)
            var state = start
            repeat(4) { state = StoryCrop.withQuarter(state, width, height) }
            assertEquals(0, state.quarter)
            assertEquals(StoryCrop.ASPECT_ORIGINAL, state.aspect)
            assertClose(start.frame.left.toDouble(), state.frame.left.toDouble())
            assertClose(start.frame.right.toDouble(), state.frame.right.toDouble())
            assertClose(start.frame.top.toDouble(), state.frame.top.toDouble())
            assertClose(start.frame.bottom.toDouble(), state.frame.bottom.toDouble())
            // Один поворот у вертикального снимка меняет стороны кадра местами.
            val turned = StoryCrop.withQuarter(start, width, height)
            assertClose(start.frame.aspect.toDouble(), (1f / turned.frame.aspect).toDouble(), 0.01)
        }
    }

    @Test fun straightenIsLimitedAndNeverOpensCorners() {
        for ((width, height) in listOf(1600 to 1200, 1080 to 1920)) {
            val start = StoryCrop.withAspect(StoryCrop.initialState(width, height), StoryCrop.ASPECT_SQUARE, width, height)
            for (degrees in -90..90 step 5) {
                val state = StoryCrop.withStraighten(start, degrees.toFloat(), width, height)
                val expected = degrees.coerceIn(-StoryCrop.STRAIGHTEN_LIMIT.toInt(), StoryCrop.STRAIGHTEN_LIMIT.toInt())
                assertClose(expected.toDouble(), state.straighten.toDouble(), 0.001)
                assertCovers(state, width, height)
            }
        }
    }

    @Test fun twoFingerRotationSplitsIntoQuartersAndRemainder() {
        val cases = listOf(
            Triple(90f, 1, 0f), Triple(45f, 1, -45f), Triple(0f, 0, 0f), Triple(359f, 0, -1f),
            Triple(270f, 3, 0f), Triple(-90f, 3, 0f), Triple(180f, 2, 0f), Triple(-45f, 0, -45f),
        )
        for ((total, quarter, straighten) in cases) {
            val state = StoryCrop.withRotation(StoryCrop.initialState(1000, 1000), total, 1000, 1000)
            assertEquals("угол $total", quarter, state.quarter)
            assertClose(straighten.toDouble(), state.straighten.toDouble(), 0.01)
            // Один и тот же поворот описывается по-разному: 359° и -1° - одно и то же.
            val turns = ((state.rotationDeg % 360f) + 360f) % 360f
            val wanted = ((total % 360f) + 360f) % 360f
            assertTrue("угол $total и остаток: $turns", abs(wanted - turns) < 0.01f)
        }
    }

    @Test fun zoomMagnifiesAndIsBounded() {
        val start = StoryCrop.initialState(1000, 1000)
        val visible = StoryCrop.coverScale(start, 1000, 1000)
        val longStart = max(start.frame.widthUnits, start.frame.heightUnits) / visible
        val zoomed = StoryCrop.withZoom(start, 2f, 1000, 1000)
        val longZoomed = max(zoomed.frame.widthUnits, zoomed.frame.heightUnits) /
            (StoryCrop.coverScale(zoomed, 1000, 1000) * zoomed.zoom)
        assertClose(longStart.toDouble() / 2.0, longZoomed.toDouble(), 0.01)
        assertEquals(1f, StoryCrop.withZoom(start, 0.1f, 1000, 1000).zoom, 0.0001f)
        assertEquals(StoryCrop.MAX_ZOOM, StoryCrop.withZoom(start, 99f, 1000, 1000).zoom, 0.0001f)
        assertClose(2.0, StoryCrop.zoomBy(start, 2f, 1000, 1000).zoom.toDouble(), 0.0001)
    }

    @Test fun panStopsAtTheEdgeOfThePhoto() {
        val pushed = StoryCrop.panBy(
            StoryCrop.withZoom(StoryCrop.initialState(1000, 1500), 3f, 1000, 1500),
            5f, 5f, 1000, 1500,
        )
        assertCovers(pushed, 1000, 1500)
        assertTrue("сдвиг должен упираться в край, а не пропадать",
            abs(pushed.panX) > 0.01f || abs(pushed.panY) > 0.01f)
        // Без приближения двигать нечего: снимок ровно по кадру.
        val still = StoryCrop.panBy(StoryCrop.initialState(1000, 1000), 0.4f, 0.4f, 1000, 1000)
        assertClose(0.0, still.panX.toDouble(), 2e-3)
    }

    @Test fun cornerDragMakesAFreeFrameWithoutInvertingIt() {
        val start = StoryCrop.initialState(1600, 1200)
        val dragged = StoryCrop.withCorner(start, 0, 0.35f, 0.35f, 1600, 1200)
        assertEquals(StoryCrop.ASPECT_FREE, dragged.aspect)
        assertCovers(dragged, 1600, 1200)
        assertTrue(dragged.frame.right - dragged.frame.left >= StoryCrop.MIN_FRAME / StoryCrop.STORY_ASPECT - 1e-5f)
        assertTrue(dragged.frame.bottom - dragged.frame.top >= StoryCrop.MIN_FRAME - 1e-5f)
        // Палец, ушедший за противоположный угол, кадр не переворачивает.
        val collapsed = StoryCrop.withCorner(start, 2, -5f, -5f, 1600, 1200)
        assertTrue(collapsed.frame.right > collapsed.frame.left)
        assertTrue(collapsed.frame.bottom > collapsed.frame.top)
        assertTrue(collapsed.frame.left >= 0f && collapsed.frame.top >= 0f)
        assertTrue(collapsed.frame.right <= 1f && collapsed.frame.bottom <= 1f)
    }

    @Test fun resetReturnsTheUntouchedPhoto() {
        var state = StoryCrop.initialState(1600, 900)
        state = StoryCrop.withQuarter(state, 1600, 900)
        state = StoryCrop.toggleFlipH(state, 1600, 900)
        state = StoryCrop.withZoom(state, 3f, 1600, 900)
        assertTrue(StoryCrop.isEdited(state, 1600, 900))
        assertFalse(StoryCrop.isEdited(StoryCrop.reset(1600, 900), 1600, 900))
        assertNotEquals(state.frame, StoryCrop.reset(1600, 900).frame)
    }

    @Test fun renderSizeKeepsTheFrameRatioAndTheStoryLimit() {
        var state = StoryCrop.initialState(4000, 2250)
        for (id in listOf(StoryCrop.ASPECT_ORIGINAL, StoryCrop.ASPECT_SQUARE, StoryCrop.ASPECT_STORY, StoryCrop.ASPECT_WIDE)) {
            state = StoryCrop.withAspect(state, id, 4000, 2250)
            val (width, height) = StoryCrop.renderSize(state, 4000, 2250)
            assertTrue(width >= 1 && height >= 1)
            assertTrue(width <= StoryCrop.MAX_SIDE && height <= StoryCrop.MAX_SIDE)
            assertClose(state.frame.aspect.toDouble(), width.toDouble() / height, 0.02)
        }
        // Приближение уменьшает число пикселей готового кадра: деталей больше не стало.
        val zoomed = StoryCrop.withZoom(state, 4f, 4000, 2250)
        assertTrue(StoryCrop.renderSize(zoomed, 4000, 2250).width <= StoryCrop.renderSize(state, 4000, 2250).width)
    }

    @Test fun randomStatesStayBoundedAndCovered() {
        val random = Random(20261015)
        repeat(400) {
            val width = listOf(800, 1080, 1600, 2048, 3000).random(random)
            val height = listOf(720, 1080, 1600, 2048, 4000).random(random)
            val raw = StoryCrop.State(
                quarter = random.nextInt(4),
                straighten = random.nextFloat() * 90f - 45f,
                flipH = random.nextBoolean(),
                flipV = random.nextBoolean(),
                aspect = StoryCrop.aspectPresets.random(random),
                frame = StoryCrop.Frame(
                    random.nextFloat() * 0.7f, random.nextFloat() * 0.7f,
                    random.nextFloat() * 0.7f + 0.3f, random.nextFloat() * 0.7f + 0.3f,
                ),
                zoom = random.nextFloat() * 12f,
                panX = random.nextFloat() * 4f - 2f,
                panY = random.nextFloat() * 4f - 2f,
            )
            val bound = StoryCrop.bind(raw, width, height)
            assertTrue(bound.zoom in 1f..StoryCrop.MAX_ZOOM)
            assertTrue(bound.frame.left >= 0f && bound.frame.left < bound.frame.right && bound.frame.right <= 1f)
            assertTrue(bound.frame.top >= 0f && bound.frame.top < bound.frame.bottom && bound.frame.bottom <= 1f)
            assertCovers(bound, width, height)
            val (outWidth, outHeight) = StoryCrop.renderSize(bound, width, height)
            assertTrue(outWidth in 1..StoryCrop.MAX_SIDE && outHeight in 1..StoryCrop.MAX_SIDE)
        }
    }
}
