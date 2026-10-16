package com.vladimir.messenger.ui.components

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.heartgame.HeartGameNetwork
import com.vladimir.messenger.data.heartgame.HeartGamePolicy
import com.vladimir.messenger.data.heartgame.HeartGameStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun HeartGameOverlay(network: HeartGameNetwork) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ready by com.vladimir.messenger.service.CoreStatus.ready.collectAsStateWithLifecycle()
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    val id = remember(ready, revision) { if (ready) HeartGameStore.ownId() else null } ?: return
    val enabled = remember(id, revision) { HeartGameStore.enabled(context, id) }
    val progress = remember { Animatable(0f) }
    var ticket by remember { mutableStateOf<Long?>(null) }
    var lane by remember { mutableFloatStateOf(0.5f) }
    var caught by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(id, enabled, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!enabled) return@repeatOnLifecycle
            launch {
                while (isActive) {
                    try { network.broadcastIfNeeded() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Core/signing unavailable: retry later, never block gameplay. */ }
                    delay(60_000)
                }
            }
            try {
                var wait = HeartGamePolicy.nextInterval()
                while (isActive) {
                    delay(wait)
                    if (HeartGameStore.ownId() != id) break
                    lane = Random.nextFloat()
                    caught = false
                    burst = null
                    progress.snapTo(0f)
                    ticket = HeartGameStore.policy.start(SystemClock.elapsedRealtime())
                    if (ticket != null) progress.animateTo(1f, tween(HeartGamePolicy.FALL_MS.toInt(), easing = LinearEasing))
                    ticket = null
                    HeartGameStore.policy.cancel()
                    wait = HeartGamePolicy.nextInterval() - HeartGamePolicy.FALL_MS
                }
            } finally {
                ticket = null
                burst = null
                HeartGameStore.policy.cancel()
            }
        }
    }
    if (!enabled) return
    val current = ticket
    if (current == null && burst == null) return
    val catchLabel = stringResource(R.string.heart_game_catch)
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val side = 88.dp
        val rangeX = (maxWidth - side - 40.dp).coerceAtLeast(0.dp)
        val rangeY = (maxHeight - side).coerceAtLeast(0.dp)
        val p = burst ?: progress.value
        val x = 20.dp + rangeX * lane + 16.dp * sin(p * PI.toFloat() * 5)
        val y = rangeY * p
        if (current != null && !caught && burst == null) {
            Box(Modifier.offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                .size(side).graphicsLayer {
                    rotationZ = 16f * sin(progress.value * PI.toFloat() * 5)
                    rotationY = 12f * cos(progress.value * PI.toFloat() * 5)
                    alpha = (1f - progress.value).coerceIn(0f, 1f)
                }.testTag("heart-game-drop").semantics { contentDescription = catchLabel }
                .clickable(enabled = !busy, role = Role.Button) {
                    if (!busy) {
                        busy = true
                        val at = progress.value
                        scope.launch {
                            try {
                                val ok = HeartGameStore.catchHeart(context, id, current)
                                caught = true
                                if (ok && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    burst = at
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (!ok) Toast.makeText(context, R.string.heart_game_save_error, Toast.LENGTH_SHORT).show()
                            } finally { busy = false }
                        }
                    }
                }) { SculptedHeart(Modifier.fillMaxSize()) }
        }
        if (burst != null) {
            HeartGameBurst(Modifier.offset { IntOffset(x.roundToPx(), y.roundToPx()) }.size(104.dp)) { burst = null }
        }
    }
}

/** Vector shading, highlights and a darker bevel: crisp at any density, no bitmap asset. */
@Composable
internal fun SculptedHeart(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(50f, 89f)
            cubicTo(37f, 77f, 8f, 57f, 8f, 33f)
            cubicTo(8f, 8f, 37f, 5f, 50f, 25f)
            cubicTo(63f, 5f, 92f, 8f, 92f, 33f)
            cubicTo(92f, 57f, 63f, 77f, 50f, 89f)
            close()
        }
        withTransform({ scale(size.width / 100f, size.height / 100f, Offset.Zero) }) {
            withTransform({ translate(0f, 4f) }) { drawPath(path, Color(0xFF6E123B).copy(alpha = .45f)) }
            drawPath(path, Brush.linearGradient(listOf(Color(0xFFFFB7D0), Color(0xFFFF427C), Color(0xFFAC104D)), Offset(18f, 10f), Offset(79f, 91f)))
            drawOval(Color.White.copy(alpha = .58f), Offset(22f, 19f), androidx.compose.ui.geometry.Size(14f, 8f))
            drawOval(Color.White.copy(alpha = .18f), Offset(63f, 20f), androidx.compose.ui.geometry.Size(10f, 6f))
        }
    }
}

@Composable
private fun HeartGameBurst(modifier: Modifier, onFinished: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1100)); onFinished() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 10) {
                val angle = i * PI.toFloat() / 5
                val radius = size.minDimension * (.12f + .42f * t.value)
                drawCircle(Color(0xFFFFD76D).copy(alpha = 1f - t.value), 4.dp.toPx() * (1f - t.value),
                    center + Offset(cos(angle) * radius, sin(angle) * radius))
            }
        }
        Text("+1", modifier = Modifier.testTag("heart-game-plus-one").graphicsLayer {
            translationY = -26.dp.toPx() * t.value
            scaleX = 1f + .22f * sin(t.value * PI.toFloat()); scaleY = scaleX
            alpha = 1f - t.value
        }, style = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Black,
            brush = Brush.verticalGradient(listOf(Color(0xFFFFF3BE), Color(0xFFFFD05A), Color(0xFFB57608))),
            shadow = Shadow(Color(0xFF794900), Offset(2f, 5f), 3f)))
    }
}
