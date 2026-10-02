package com.vladimir.messenger.ui.components

// =============================================================================
// APPSPLASH.KT
// =============================================================================
// Сплэш при запуске приложения: иконка APU во весь экран и анимация передачи
// данных - светящиеся точки бегут по линиям между «серверами» сети.
// Раунд 263: заставка больше не «выдержка ради красоты». Она держится, пока
// ядро действительно поднимается (честный статус этапа внизу), и отпускает
// человека сразу, как ядро готово - с потолком 10 секунд на случай проблем.
// При тёплом старте (ядро уже работает) сплэш не показывается вовсе.
// Уход - мягким растворением, как у топовых мессенджеров.
// Здесь же CoreWarmBar - тонкая полоска «Ядро подключается…» для экранов.
// =============================================================================

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R
import com.vladimir.messenger.service.CoreStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Золото сети - как акцент темы (её палитровые константы приватные). */
private val ApuGold = Color(0xFFE4B45A)

/** Пол: сколько заставка живёт, если ядро всё ещё поднимается. */
private const val SPLASH_MAX_MILLIS = 10_000L

/** Минимум, чтобы заставка не мелькала на быстром железе. */
private const val SPLASH_MIN_MILLIS = 900L

/** Длительность растворения. */
private const val SPLASH_FADE_MILLIS = 420

/** Полноэкранный сплэш: иконка во весь экран + бегущие пакеты данных. */
@Composable
fun AppSplash(onFinished: () -> Unit) {
    val gold = ApuGold
    val coreStage by CoreStatus.stage.collectAsState()

    // Уходит мягко: сначала гаснет прозрачность, потом экран отпускают.
    var leaving by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(SPLASH_FADE_MILLIS),
        label = "splash-fade",
    )

    // Ждём реальную готовность ядра (или честный потолок), не меньше
    // SPLASH_MIN_MILLIS, чтобы заставка не мелькала.
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        kotlinx.coroutines.withTimeoutOrNull(SPLASH_MAX_MILLIS) {
            CoreStatus.ready.first { it }
        }
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < SPLASH_MIN_MILLIS) delay(SPLASH_MIN_MILLIS - elapsed)
        leaving = true
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            delay(SPLASH_FADE_MILLIS.toLong())
            onFinished()
        }
    }

    // Бесконечный прогресс 0..1 - по нему «едут» точки данных.
    val transition = rememberInfiniteTransition(label = "splash-data")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splash-progress",
    )

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade)
            .background(Color.Black),
    ) {
        // Иконка APU во весь экран (вектор - масштаб без потерь).
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = "APU",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Тёмная вуаль снизу, чтобы подпись читалась на любом фоне.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                        ),
                        startY = with(density) { 320.dp.toPx() },
                    )
                )
        )

        // Сеть «серверов»: узлы на линиях и бегущие между ними точки данных.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val nodes = listOf(
                Offset(w * 0.18f, h * 0.22f),
                Offset(w * 0.82f, h * 0.30f),
                Offset(w * 0.30f, h * 0.52f),
                Offset(w * 0.72f, h * 0.62f),
                Offset(w * 0.50f, h * 0.40f),
                Offset(w * 0.12f, h * 0.70f),
                Offset(w * 0.90f, h * 0.78f),
            )
            val links = listOf(
                0 to 4, 1 to 4, 2 to 4, 3 to 4, 0 to 2, 1 to 3, 2 to 5, 3 to 6, 5 to 4, 6 to 4,
            )
            // Линии связи между узлами.
            links.forEach { (a, b) ->
                drawLine(
                    color = gold.copy(alpha = 0.28f),
                    start = nodes[a],
                    end   = nodes[b],
                    strokeWidth = 2f,
                )
            }
            // Узлы-«серверы».
            nodes.forEach { node ->
                drawCircle(color = gold.copy(alpha = 0.55f), radius = 7f, center = node)
            }
            // Бегущие пакеты данных: у каждой связи своя фаза.
            links.forEachIndexed { index, (a, b) ->
                val phase = (progress + index * 0.13f) % 1f
                val p = Offset(
                    x = nodes[a].x + (nodes[b].x - nodes[a].x) * phase,
                    y = nodes[a].y + (nodes[b].y - nodes[a].y) * phase,
                )
                // След пакета.
                drawCircle(color = gold.copy(alpha = 0.18f), radius = 14f, center = p)
                drawCircle(color = gold, radius = 6f, center = p)
            }
        }

        // Внизу: честный этап подъёма ядра + фирменная подпись.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(targetState = coreStage, label = "splash-stage") { stage ->
                Text(
                    text     = stage,
                    color    = gold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text     = "APU · связь напрямую, без посредников",
                color    = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Раунд 263: тонкая полоска «Ядро подключается…» поверх экрана, пока ядро
 * ещё доподнимается в фоне. Пустой список не должен выглядеть поломкой.
 */
@Composable
fun CoreWarmBar() {
    val ready by CoreStatus.ready.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = !ready,
        enter = androidx.compose.animation.expandVertically() +
            androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() +
            androidx.compose.animation.fadeOut(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(ApuGold.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = ApuGold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Ядро подключается…",
                color = ApuGold,
                fontSize = 12.sp,
            )
        }
    }
}
