package com.vladimir.messenger.ui.components

// =============================================================================
// APPSPLASH.KT
// =============================================================================
// Сплэш при запуске приложения: иконка APU во весь экран и анимация передачи
// данных - светящиеся точки бегут по линиям между «серверами» сети, показывая,
// что связь наводится. Держится пару секунд и уходит.
// =============================================================================

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.vladimir.messenger.ui.theme.LocalMessengerColors
import kotlinx.coroutines.delay

private const val SPLASH_MILLIS = 2500L

/** Полноэкранный сплэш: иконка во весь экран + бегущие пакеты данных. */
@Composable
fun AppSplash(onFinished: () -> Unit) {
    val colors = LocalMessengerColors.current

    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        onFinished()
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
                    color = colors.gold.copy(alpha = 0.28f),
                    start = nodes[a],
                    end   = nodes[b],
                    strokeWidth = 2f,
                )
            }
            // Узлы-«серверы».
            nodes.forEach { node ->
                drawCircle(color = colors.gold.copy(alpha = 0.55f), radius = 7f, center = node)
            }
            // Бегущие пакеты данных: у каждой связи своя фаза.
            links.forEachIndexed { index, (a, b) ->
                val phase = (progress + index * 0.13f) % 1f
                val p = Offset(
                    x = nodes[a].x + (nodes[b].x - nodes[a].x) * phase,
                    y = nodes[a].y + (nodes[b].y - nodes[a].y) * phase,
                )
                // След пакета.
                drawCircle(color = colors.gold.copy(alpha = 0.18f), radius = 14f, center = p)
                drawCircle(color = colors.gold, radius = 6f, center = p)
            }
        }

        // Подпись внизу.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text     = "APU - связь напрямую, без посредников",
                color    = colors.gold,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
