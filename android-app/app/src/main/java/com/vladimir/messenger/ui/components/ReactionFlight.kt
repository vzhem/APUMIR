package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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

    /** Один полёт: что летит и откуда стартовало (пиксели ЭКРАНА). */
    data class Flight(
        val id: Long,
        val emoji: String,
        val startXPx: Float,
        val startYPx: Float,
    )

    private val _current = MutableStateFlow<Flight?>(null)
    val current: StateFlow<Flight?> = _current.asStateFlow()

    private var counter: Long = 0L

    /**
     * Запустить полёт значка.
     *
     * Координаты - в пикселях ЭКРАНА (см. [toScreenSpot]). Именно
     * экрана, а не окна: пузырь выбора реакции - отдельное окно со своим
     * началом координат, и его «внутренние» пиксели не совпадают с окном
     * приложения, поверх которого рисуется полёт. Раньше сюда передавались
     * доли экрана из настроек устройства - в них нет строки состояния и
     * выреза, поэтому значок улетал в верхнюю часть экрана.
     */
    fun launch(
        emoji: String,
        startXPx: Float,
        startYPx: Float,
    ) {
        if (emoji.isBlank()) return
        if (startYPx <= 0f) return
        counter += 1
        _current.value = Flight(
            id = counter,
            emoji = emoji,
            startXPx = startXPx,
            startYPx = startYPx,
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
        "\uD83D\uDE80" -> FlightStyle(riseFraction = 0.85f, durationMs = 2000, swayDp = 8f, spinDegrees = -14f, growTo = 1.8f)
        "\u2764\uFE0F", "\uD83D\uDE0D" -> FlightStyle(riseFraction = 0.55f, durationMs = 2400, swayDp = 34f, spinDegrees = 10f, growTo = 2.0f)
        "\uD83D\uDD25" -> FlightStyle(riseFraction = 0.45f, durationMs = 2000, swayDp = 18f, spinDegrees = 12f, growTo = 2.2f)
        "\uD83C\uDF89", "\u2B50", "\uD83C\uDF1F" -> FlightStyle(riseFraction = 0.5f, durationMs = 2200, swayDp = 26f, spinDegrees = 200f, growTo = 1.9f)
        "\uD83D\uDC4D", "\uD83D\uDC4F", "\uD83D\uDCAA" -> FlightStyle(riseFraction = 0.4f, durationMs = 1800, swayDp = 12f, spinDegrees = -14f, growTo = 1.8f)
        else -> FlightStyle(riseFraction = 0.45f, durationMs = 2000, swayDp = 20f, spinDegrees = 8f, growTo = 1.9f)
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
 * Перевести положение внутри окна в положение на ЭКРАНЕ.
 *
 * `positionInWindow` даёт координаты внутри своего окна, а окон у нас два:
 * приложение и диалог выбора реакции. Смещение окна на экране берём у
 * системного `View` - после этого обе точки живут в одной системе координат.
 */
fun toScreenSpot(view: View, xInWindow: Float, yInWindow: Float): Pair<Float, Float> {
    val origin = IntArray(2)
    view.getLocationOnScreen(origin)
    return (origin[0] + xInWindow) to (origin[1] + yInWindow)
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

    // BoxWithConstraints даёт высоту слоя - она нужна, чтобы «подняться на
    // половину экрана» считалось от экрана, а не от размера значка.
    // Слой сам живёт в окне приложения, поэтому экранную точку старта
    // переводим обратно в координаты этого окна.
    val view = LocalView.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenH = with(LocalDensity.current) { maxHeight.toPx() }
        val origin = remember(shown.id) {
            IntArray(2).also { view.getLocationOnScreen(it) }
        }
        val startX = shown.startXPx - origin[0]
        val startY = shown.startYPx - origin[1]
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
                    translationX = startX - size.width / 2f + sway
                    translationY = startY -
                        screenH * style.riseFraction * eased -
                        size.height / 2f
                    // Растёт на взлёте и держит размер до конца.
                    val grow = 1f + (style.growTo - 1f) * kotlin.math.min(t * 2.2f, 1f)
                    scaleX = grow
                    scaleY = grow
                    rotationZ = style.spinDegrees * t
                    // Гаснет только на последней четверти пути: значок должен
                    // быть виден почти весь полёт, а не мигнуть и исчезнуть.
                    alpha = if (t < 0.75f) 1f else 1f - (t - 0.75f) / 0.25f
                },
        )
    }
}
