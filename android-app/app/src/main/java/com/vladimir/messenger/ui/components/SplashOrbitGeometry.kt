package com.vladimir.messenger.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Continuous 3D orbits in normalized square-art coordinates, not node-to-node routes. */
internal object SplashOrbitGeometry {
    const val CORE_RADIUS = 0.15
    const val PATH_SAMPLES = 160
    const val CAMERA_DISTANCE = 2.0

    data class Orbit(
        val radius: Double,
        val tilt: Double,
        val roll: Double,
        val phaseOffset: Double,
        /** Whole turns keep both position and velocity continuous at the loop seam. */
        val turns: Int,
    )

    data class Point(val x: Double, val y: Double, val depth: Double, val scale: Double)

    val orbits = listOf(
        Orbit(radius = 0.28, tilt = 0.77, roll = -0.35, phaseOffset = 0.08, turns = 2),
        Orbit(radius = 0.315, tilt = 1.01, roll = 1.05, phaseOffset = 0.30, turns = -1),
        Orbit(radius = 0.252, tilt = 0.42, roll = 1.95, phaseOffset = 0.60, turns = 1),
    )

    fun project(orbit: Orbit, cycle: Double, offset: Double = 0.0): Point {
        val angle = 2.0 * PI * (cycle * orbit.turns + orbit.phaseOffset + offset)
        val x = orbit.radius * cos(angle)
        val y = orbit.radius * sin(angle) * cos(orbit.tilt)
        val z = orbit.radius * sin(angle) * sin(orbit.tilt)
        val rotatedX = x * cos(orbit.roll) - y * sin(orbit.roll)
        val rotatedY = x * sin(orbit.roll) + y * cos(orbit.roll)
        val perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE - z)
        return Point(rotatedX * perspective, rotatedY * perspective, z, perspective)
    }

    /** The raster core is an opaque sphere: rear arcs/particles cannot cross its face. */
    fun hiddenByCore(point: Point): Boolean = point.depth < 0 && hypot(point.x, point.y) < CORE_RADIUS

    /** Bounds hero art on short/landscape screens instead of cropping or distorting it. */
    fun heroSizeDp(width: Float, height: Float): Float =
        minOf((width - 32f).coerceAtLeast(1f), (height - 188f).coerceAtLeast(1f), 520f)
}
