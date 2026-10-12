package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val SplashNavy = Color(0xFF010A16)
private val OrbitGold = Color(0xFFE4B45A)

/** Dedicated sharp art, restrained camera movement and shaded, depth-aware electrons. */
@Composable
internal fun SplashCoreScene(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val art by produceState<ImageBitmap?>(initialValue = null, key1 = resources) {
        // No image decoding on the UI thread, networking or wait added to core startup.
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(
                resources,
                R.drawable.splash_digital_core,
                BitmapFactory.Options().apply { inScaled = false },
            )?.asImageBitmap()
        }
    }
    val transition = rememberInfiniteTransition(label = "splash-orbits")
    // Compose respects the system animation-duration scale, including disabled animations.
    val cycle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splash-orbit-cycle",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                // Small camera parallax, not a zoom/crop that sacrifices sharpness.
                val angle = cycle.value * (2.0 * PI)
                rotationX = (sin(angle) * 0.65).toFloat()
                rotationY = (cos(angle) * 0.85).toFloat()
                cameraDistance = 6_000f * density
            }
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                fun screen(point: SplashOrbitGeometry.Point) = Offset(
                    center.x + point.x.toFloat() * size.width,
                    center.y + point.y.toFloat() * size.width,
                )
                // Curves depend only on viewport size, not the current frame.
                val paths = SplashOrbitGeometry.orbits.map { orbit ->
                    Path().apply {
                        var open = false
                        for (sample in 0..SplashOrbitGeometry.PATH_SAMPLES) {
                            val point = SplashOrbitGeometry.project(orbit, sample.toDouble() / SplashOrbitGeometry.PATH_SAMPLES / kotlin.math.abs(orbit.turns))
                            if (SplashOrbitGeometry.hiddenByCore(point)) {
                                open = false
                            } else {
                                val position = screen(point)
                                if (open) lineTo(position.x, position.y) else moveTo(position.x, position.y)
                                open = true
                            }
                        }
                    }
                }
                onDrawWithContent {
                    drawContent()
                    if (art != null) {
                        paths.forEach { path ->
                            drawPath(
                                path = path,
                                color = OrbitGold.copy(alpha = 0.22f),
                                style = Stroke(width = 0.7f * density, cap = StrokeCap.Round),
                            )
                        }
                    } else {
                        // A crisp local placeholder while the bitmap decodes; never a spinner.
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color(0xFFFFE8AD), OrbitGold, Color(0xFF443014)),
                                center = center - Offset(size.width * 0.045f, size.width * 0.045f),
                                radius = size.width * 0.20f,
                            ),
                            radius = size.width * 0.13f,
                            center = center,
                        )
                    }
                    val points = SplashOrbitGeometry.orbits.flatMap { orbit ->
                        listOf(
                            SplashOrbitGeometry.project(orbit, cycle.value.toDouble()),
                            SplashOrbitGeometry.project(orbit, cycle.value.toDouble(), offset = 0.5),
                        )
                    }.sortedBy { it.depth }
                    points.forEach { point ->
                        if (!SplashOrbitGeometry.hiddenByCore(point)) {
                            val position = screen(point)
                            val radius = 3.6f * density * point.scale.toFloat()
                            // Controlled halo; the actual electron has a hard, shaded contour.
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(OrbitGold.copy(alpha = 0.24f), Color.Transparent),
                                    center = position, radius = radius * 2.2f,
                                ),
                                radius = radius * 2.2f, center = position,
                            )
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFFFFF8D9), Color(0xFFF3CE78), Color(0xFF754716)),
                                    center = position - Offset(radius * 0.34f, radius * 0.34f),
                                    radius = radius * 1.6f,
                                ),
                                radius = radius, center = position,
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.90f),
                                radius = radius * 0.20f,
                                center = position - Offset(radius * 0.30f, radius * 0.30f),
                            )
                        }
                    }
                    // Blend the square art into the full-screen navy; no icon frame or hard edge.
                    val feather = size.width * 0.05f
                    drawRect(Brush.horizontalGradient(listOf(SplashNavy, Color.Transparent), startX = 0f, endX = feather))
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, SplashNavy), startX = size.width - feather, endX = size.width))
                    drawRect(Brush.verticalGradient(listOf(SplashNavy, Color.Transparent), startY = 0f, endY = feather))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, SplashNavy), startY = size.height - feather, endY = size.height))
                }
            },
    ) {
        art?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.splash_core_cd),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
            )
        }
    }
}
