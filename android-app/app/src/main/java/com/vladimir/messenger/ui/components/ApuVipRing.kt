package com.vladimir.messenger.ui.components

// =============================================================================
// APUVIPRING.KT — золотое кольцо элиты вокруг аватарки
// =============================================================================
// Задача владельца от 2026-10-07: «Нужно чтобы аккаунт VIP должно быть видно
// везде и в группах и в каналах. Вокруг Аватарки должно быть объёмное золотое
// кольцо с иногда проходящим блеском. Как в топовых мессенджерах».
//
// Кольцо — не рамка и не обводка: оно металлическое. Вид объёма дают три слоя
// (светлый внешний кант, золотая лента с переливом, тёмный внутренний кант) —
// так же собрана плашка [ApuVipBadge], поэтому знак и кольцо выглядят одной
// наградой. Блеск проходит редко: раз в девять секунд узкая светлая волна
// обегает кольцо за две с половиной секунды, остальное время кольцо спокойное.
// Это важно и для глаза (сто строк в списке не должны мельтешить), и для
// батареи: анимация живёт только у VIP-аватарок, которые сейчас на экране.
//
// Аватарку рисует [PeerAvatar]: картинка из роевого хранилища либо круг с
// инициалами, как раньше. Кольцо занимает край круга, картинка внутри чуть
// меньше — поэтому VIP-строка выше остальных не становится.
// =============================================================================

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import kotlin.math.PI
import kotlin.math.sin

/** Ширина золотого кольца: у VIP-аватарки край круга занят им. */
val VipRingWidth: Dp = 2.5.dp

/** Полный цикл блеска: девять секунд спокойствия, две с половиной — волна. */
private const val SHEEN_CYCLE_MS = 9_000
private const val SHEEN_LAP_MS = 2_500

/** Цвета кольца те же, что у плашки VIP: одна награда — один металл. */
private val GoldLight = Color(0xFFFFE9A8)
private val GoldMid = Color(0xFFE7B93F)
private val GoldDeep = Color(0xFF9A7212)
private val GoldShadow = Color(0xFF6E4E08)
private val GoldEdge = Color(0xFFFFF6D5)

/**
 * Объёмное золотое кольцо вокруг аватарки (элита APU).
 *
 * Рисуется поверх того места, где лежит аватарка: вызывающий даёт размер блока,
 * а картинку внутрь кладёт [PeerAvatar] с отступом на ширину кольца.
 */
@Composable
fun VipRing(modifier: Modifier = Modifier) {
    // Одна бесконечная анимация на аватарку. Она есть только у VIP-строк: у
    // обычных контактов ни анимации, ни лишней работы в кадре нет.
    val transition = rememberInfiniteTransition(label = "vip-ring")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHEEN_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vip-ring-phase",
    )
    // Блеск живёт только в начале цикла: за это окно волна обегает кольцо.
    val lap = phase * SHEEN_CYCLE_MS / SHEEN_LAP_MS

    Canvas(modifier) {
        val stroke = VipRingWidth.toPx()
        if (size.minDimension <= stroke * 2f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension - stroke) / 2f

        // Золотая лента: перелив по кругу читается как металл, а не как обводка.
        drawCircle(
            brush = Brush.sweepGradient(
                0.00f to GoldLight,
                0.12f to GoldMid,
                0.26f to GoldDeep,
                0.40f to GoldLight,
                0.55f to GoldMid,
                0.70f to GoldLight,
                0.84f to GoldDeep,
                1.00f to GoldLight,
                center = center,
            ),
            radius = radius,
            style = Stroke(width = stroke),
        )
        // Внешний светлый кант и внутренняя тень: кольцо становится выпуклым.
        val edge = 1.dp.toPx()
        drawCircle(
            color = GoldEdge.copy(alpha = 0.95f),
            radius = radius + stroke / 2f - edge / 2f,
            style = Stroke(width = edge),
        )
        drawCircle(
            color = GoldShadow.copy(alpha = 0.55f),
            radius = radius - stroke / 2f + edge / 2f,
            style = Stroke(width = edge),
        )

        // Волна блеска: узкая светлая дуга, обегающая кольцо.
        if (lap < 1f) {
            val fade = sin(PI * lap).toFloat()
            rotate(degrees = -90f + 360f * lap, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, Color.White, Color.Transparent),
                        center = center,
                    ),
                    startAngle = -42f,
                    sweepAngle = 84f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    alpha = fade,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * Мой ранг дотягивает до VIP: свой аватар тоже в кольце элиты.
 *
 * Свой ранг лежит в настройках (`ReferralRankStore`), и он растёт от промокода
 * или приглашения прямо в приложении — поэтому не читаем его один раз, а
 * следим за изменениями: кольцо появляется сразу, без перезахода на экран.
 */
@Composable
fun rememberSelfVip(): Boolean {
    val context = LocalContext.current
    val changes by ReferralRankStore.changes.collectAsState()
    return remember(changes) {
        FileTransferRankPolicy.isVip(ReferralRankStore.qualifiedDirectCount(context))
    }
}

/**
 * Аватарка собеседника: картинка из роевого хранилища либо круг с инициалами.
 *
 * При [vip] вокруг неё объёмное золотое кольцо, а сама картинка чуть меньше —
 * внешний размер и место в строке не меняются. [overlay] — то, что экран
 * рисует поверх круга (например, точка «в сети»).
 */
@Composable
fun PeerAvatar(
    name: String,
    avatarB64: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    vip: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    // Разбор картинки — в фоне и один раз на строку base64 (AvatarBitmaps).
    val bitmap = AvatarBitmaps.rememberAvatar(avatarB64)
    val inset = if (vip) VipRingWidth else 0.dp
    val inner = size - inset * 2
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(inner)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(inner),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Avatar(name = name, modifier = Modifier.size(inner), size = inner.value.toInt())
            }
        }
        if (vip) {
            VipRing(Modifier.matchParentSize())
        }
        overlay()
    }
}
