package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

/**
 * Полёт реакции поверх всего приложения.
 *
 * Значок должен вылетать ЗА пределы сообщения - ракета уходит вверх мимо
 * пузыря. Внутри списка так не сделать: пузырь обрезает всё, что вышло за его
 * край, а сам список ещё и прокручивается. Поэтому полёт рисуется отдельным
 * слоем поверх всего экрана, а координаты берутся от точки нажатия.
 *
 * Слой один на всё приложение (см. [ReactionFlightOverlay] в MainActivity), в
 * памяти живёт один-единственный полёт: нажали второй раз - предыдущий
 * сменился новым, накопления анимаций нет.
 */
object ReactionFlight {

    /** Один полёт: что летит и откуда стартовало (доля ширины/высоты экрана). */
    data class Flight(
        val id: Long,
        val emoji: String,
        val startXFraction: Float,
        val startYFraction: Float,
    )

    private val _current = MutableStateFlow<Flight?>(null)
    val current: StateFlow<Flight?> = _current.asStateFlow()

    private var counter: Long = 0L

    /**
     * Запустить полёт значка.
     *
     * [startXFraction]/[startYFraction] - откуда стартовать, в долях экрана.
     * По умолчанию из нижней трети середины: туда обычно и нажимают.
     */
    fun launch(
        emoji: String,
        startXFraction: Float = 0.5f,
        startYFraction: Float = 0.72f,
    ) {
        if (emoji.isBlank()) return
        counter += 1
        _current.value = Flight(
            id = counter,
            emoji = emoji,
            startXFraction = startXFraction.coerceIn(0.05f, 0.95f),
            startYFraction = startYFraction.coerceIn(0.05f, 0.95f),
        )
    }

    /** Полёт закончился - убираем слой, чтобы он ничего не перехватывал. */
    fun clear(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }

    /**
     * Характер полёта для конкретного значка.
     *
     * Ракета уходит вверх стрелой, сердце всплывает и качается, огонь бьёт
     * вверх языком, праздник разлетается. Остальные всплывают спокойно.
     */
    fun styleFor(emoji: String): FlightStyle = when (emoji) {
        "\uD83D\uDE80" -> FlightStyle(riseFraction = 0.95f, durationMs = 1100, swayDp = 6f, spinDegrees = -18f, growTo = 1.7f)
        "\u2764\uFE0F", "\uD83D\uDE0D" -> FlightStyle(riseFraction = 0.62f, durationMs = 1500, swayDp = 34f, spinDegrees = 10f, growTo = 1.9f)
        "\uD83D\uDD25" -> FlightStyle(riseFraction = 0.5f, durationMs = 1100, swayDp = 18f, spinDegrees = 12f, growTo = 2.1f)
        "\uD83C\uDF89", "\u2B50", "\uD83C\uDF1F" -> FlightStyle(riseFraction = 0.55f, durationMs = 1300, swayDp = 26f, spinDegrees = 200f, growTo = 1.8f)
        "\uD83D\uDC4D", "\uD83D\uDC4F", "\uD83D\uDCAA" -> FlightStyle(riseFraction = 0.45f, durationMs = 1000, swayDp = 10f, spinDegrees = -14f, growTo = 1.7f)
        else -> FlightStyle(riseFraction = 0.5f, durationMs = 1200, swayDp = 20f, spinDegrees = 8f, growTo = 1.8f)
    }

    /** Насколько высоко, как долго, как качает и как крутит. */
    data class FlightStyle(
        val riseFraction: Float,
        val durationMs: Int,
        val swayDp: Float,
        val spinDegrees: Float,
        val growTo: Float,
    )
}

/**
 * Слой полёта. Ставится ОДИН раз поверх всего приложения.
 *
 * Слой не ловит нажатия: `Box` без обработчиков прозрачен для касаний, поэтому
 * во время полёта можно продолжать пользоваться экраном.
 */
@Composable
fun ReactionFlightOverlay() {
    val flight by ReactionFlight.current.collectAsStateWithLifecycle()
    val shown = flight ?: return
    val style = remember(shown.id) { ReactionFlight.styleFor(shown.emoji) }

    // Одна дорожка 0..1: по ней считаем и подъём, и качание, и прозрачность.
    val progress = remember(shown.id) { Animatable(0f) }
    LaunchedEffect(shown.id) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = style.durationMs, easing = LinearEasing),
        )
        ReactionFlight.clear(shown.id)
    }

    val t = progress.value

    // BoxWithConstraints даёт размер САМОГО слоя (весь экран). Внутри
    // graphicsLayer `size` - это размер летящего значка, а не экрана, поэтому
    // отсчитывать полёт от него нельзя.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = with(LocalDensity.current) { maxWidth.toPx() }
        val screenH = with(LocalDensity.current) { maxHeight.toPx() }
        Text(
            text = shown.emoji,
            fontSize = 34.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer {
                    // Взлёт замедляется к концу: так движение читается живым,
                    // а не равномерным сдвигом картинки.
                    val eased = 1f - (1f - t) * (1f - t)
                    val sway = style.swayDp.dp.toPx() * sin(t * 6.2831855f)
                    translationX = screenW * shown.startXFraction - size.width / 2f + sway
                    translationY = screenH * shown.startYFraction -
                        screenH * style.riseFraction * eased -
                        size.height / 2f
                    // Растёт на взлёте и держит размер до конца.
                    val grow = 1f + (style.growTo - 1f) * kotlin.math.min(t * 2.2f, 1f)
                    scaleX = grow
                    scaleY = grow
                    rotationZ = style.spinDegrees * t
                    // Гаснет только во второй половине пути.
                    alpha = if (t < 0.55f) 1f else 1f - (t - 0.55f) / 0.45f
                },
        )
    }
}
