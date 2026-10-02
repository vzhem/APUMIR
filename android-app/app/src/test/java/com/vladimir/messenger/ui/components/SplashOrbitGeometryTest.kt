package com.vladimir.messenger.ui.components

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashOrbitGeometryTest {
    @Test
    fun everyOrbitHasTheSamePositionAtTheLoopBoundary() {
        for (orbit in SplashOrbitGeometry.orbits) {
            val start = SplashOrbitGeometry.project(orbit, 0.0)
            val end = SplashOrbitGeometry.project(orbit, 1.0)
            assertEquals(start.x, end.x, 1e-10)
            assertEquals(start.y, end.y, 1e-10)
            assertEquals(start.depth, end.depth, 1e-10)
            assertEquals(start.scale, end.scale, 1e-10)
        }
    }

    @Test
    fun velocityIsContinuousAcrossTheLoopBoundaryToo() {
        val epsilon = 1e-6
        for (orbit in SplashOrbitGeometry.orbits) {
            val before = SplashOrbitGeometry.project(orbit, -epsilon)
            val start = SplashOrbitGeometry.project(orbit, 0.0)
            val end = SplashOrbitGeometry.project(orbit, 1.0)
            val after = SplashOrbitGeometry.project(orbit, 1.0 + epsilon)
            assertEquals((start.x - before.x) / epsilon, (after.x - end.x) / epsilon, 1e-3)
            assertEquals((start.y - before.y) / epsilon, (after.y - end.y) / epsilon, 1e-3)
        }
    }

    @Test
    fun projectedOrbitsStayFiniteAndInsideTheArt() {
        for (orbit in SplashOrbitGeometry.orbits) {
            for (sample in 0..1000) {
                val point = SplashOrbitGeometry.project(orbit, sample / 1000.0)
                assertTrue(point.x.isFinite() && point.y.isFinite() && point.depth.isFinite())
                assertTrue(abs(point.x) < 0.45 && abs(point.y) < 0.45)
                assertTrue(point.scale > 0.8 && point.scale < 1.3)
            }
        }
    }

    @Test
    fun nearElectronsAreBiggerThanTheSameOrbitOnTheFarSide() {
        val orbit = SplashOrbitGeometry.orbits.first()
        val points = (0..1000).map { SplashOrbitGeometry.project(orbit, it / 1000.0) }
        assertTrue(points.maxBy { it.depth }.scale > points.minBy { it.depth }.scale)
    }

    @Test
    fun rearObjectsAreHiddenByTheOpaqueCoreButFrontOnesAreNot() {
        assertTrue(SplashOrbitGeometry.hiddenByCore(SplashOrbitGeometry.Point(0.01, 0.01, -0.1, 0.9)))
        assertFalse(SplashOrbitGeometry.hiddenByCore(SplashOrbitGeometry.Point(0.01, 0.01, 0.1, 1.1)))
        assertFalse(SplashOrbitGeometry.hiddenByCore(SplashOrbitGeometry.Point(0.3, 0.3, -0.1, 0.9)))
    }

    @Test
    fun electronsFollowCurvesInsteadOfPassingThroughTheCoreOnStraightLinks() {
        for (orbit in SplashOrbitGeometry.orbits) {
            val opposite = 0.5 / abs(orbit.turns)
            val start = SplashOrbitGeometry.project(orbit, 0.0)
            val end = SplashOrbitGeometry.project(orbit, opposite)
            val middle = SplashOrbitGeometry.project(orbit, opposite / 2)
            val straightMiddleX = (start.x + end.x) / 2
            val straightMiddleY = (start.y + end.y) / 2
            assertTrue(hypot(middle.x - straightMiddleX, middle.y - straightMiddleY) > 0.1)
        }
    }

    @Test
    fun heroUsesAllOfTheSquareWithoutCroppingOnPhoneTabletOrLandscape() {
        assertEquals(328f, SplashOrbitGeometry.heroSizeDp(360f, 800f), 0.001f)
        assertEquals(172f, SplashOrbitGeometry.heroSizeDp(800f, 360f), 0.001f)
        assertEquals(520f, SplashOrbitGeometry.heroSizeDp(1000f, 1400f), 0.001f)
        assertTrue(SplashOrbitGeometry.heroSizeDp(0f, 0f) > 0)
    }
}
