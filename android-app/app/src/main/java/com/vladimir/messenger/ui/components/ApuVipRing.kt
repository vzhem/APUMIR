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
import com.vladimir.messenger.data.avatavideo.PeerVideoAvatarStore
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import kotlin.math.PI
import kotlin.math.sin

/** Ширина золотого кольца: у VIP-аватарки край круга занят им. */
val VipRingWidth: Dp = 2.5.dp

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
    // Кольцо металлическое и в покое: фаза за пределами волны блеска,
    // поэтому список VIP-аватарок не запрашивает кадры без действия человека.
    val lap = 2f

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
 *
 * [peerId] — node id того, кто на аватаре: у контакта с видеоаватаром его
 * ролик крутится везде, где встречается аватар (список и чат, профиль,
 * участники группы/канала, отклики, дни рождения, визитка). Без id или без
 * приехавшего ролика остаётся прежний постер.
 */
@Composable
fun PeerAvatar(
    name: String,
    avatarB64: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    vip: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    peerId: String? = null,
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
            // Видеокружок собеседника поверх постера: пока ролик не приехал
            // (или его убрали), крутится нечего и слой просто не рисуется.
            PeerVideoAvatarLayer(peerId = peerId)
        }
        if (vip) {
            VipRing(Modifier.matchParentSize())
        }
        overlay()
    }
}

/**
 * Слой видеоаватара собеседника: находит приехавший ролик и зацикленно играет
 * его той же формой (круг/квадрат), что выбрал владелец. Данные перечитываются
 * по поколению хранилища: установленный ролик появляется без перезахода в экран.
 */
@Composable
private fun PeerVideoAvatarLayer(peerId: String?) {
    if (peerId.isNullOrBlank()) return
    val context = LocalContext.current
    val version by PeerVideoAvatarStore.changed.collectAsState()
    val video = remember(peerId, version) { PeerVideoAvatarStore.fileFor(context, peerId) }
    if (video != null) {
        val circle = remember(peerId, version) { PeerVideoAvatarStore.circleFor(context, peerId) }
        // key: при смене ролика плеер создаётся заново под новый файл.
        androidx.compose.runtime.key(video.absolutePath, circle) {
            VideoAvatarPlayback(path = video.absolutePath, circle = circle)
        }
    }
}
