package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.data.reaction.ReactionPalette
import com.vladimir.messenger.data.reaction.ReactionSummary
import kotlinx.coroutines.delay

private val GoldBorder = Color(0xFFD4AF37)

/** Сколько значков помещается в одну строку пузыря выбора. */
private const val PICKER_COLUMNS = 5

/**
 * Ряд поставленных реакций под сообщением или постом.
 *
 * Свои значки обведены золотом - так сразу видно, что уже поставлено. Нажатие
 * на значок снимает или ставит его повторно, без меню.
 *
 * Значок оживает в двух случаях: когда реакция только появилась и когда её
 * счётчик изменился. Постоянной анимации в ленте нет специально - иначе
 * прокрутка сотни сообщений грела бы телефон.
 */
@Composable
fun ReactionRow(
    reactions: List<ReactionSummary>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    Column(
        modifier = modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Значков может набраться много - переносим их построчно, чтобы ряд
        // не уезжал за край пузыря.
        for (line in reactions.chunked(6)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in line) {
                    ReactionChip(item = item, onToggle = onToggle)
                }
            }
        }
    }
}

/** Отдельный значок с числом: подпрыгивает при появлении и при смене счётчика. */
@Composable
private fun ReactionChip(
    item: ReactionSummary,
    onToggle: (String) -> Unit,
) {
    // Animatable, а не animateFloatAsState: нужен именно толчок «сжался -
    // разжался с отскоком», а не плавный переход между двумя значениями.
    val bounce = remember(item.emoji) { Animatable(0.7f) }
    LaunchedEffect(item.emoji, item.count, item.mine) {
        bounce.snapTo(0.7f)
        bounce.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // Где значок находится на ЭКРАНЕ: отсюда стартует полёт, чтобы ракета
    // уходила вверх ИМЕННО от этого сообщения.
    val view = LocalView.current
    var spot by remember { mutableStateOf(0f to 0f) }

    Row(
        modifier = Modifier
            .scale(bounce.value)
            .onGloballyPositioned { coords ->
                val at = coords.positionInWindow()
                spot = toScreenSpot(
                    view,
                    at.x + coords.size.width / 2f,
                    at.y + coords.size.height / 2f,
                )
            }
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF1F3F7))
            .border(
                width = if (item.mine) 1.5.dp else 0.dp,
                color = if (item.mine) GoldBorder else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable {
                // Полёт запускаем, только когда СТАВИМ реакцию. При снятии
                // ничего не взлетает: это было бы прощальным салютом.
                // spot == 0 значит замер ещё не прошёл - полёт пропускаем,
                // чтобы значок не стартовал из угла экрана.
                if (!item.mine && spot.second > 0f) {
                    ReactionFlight.launch(item.emoji, spot.first, spot.second)
                }
                onToggle(item.emoji)
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.emoji, fontSize = 14.sp)
        if (item.count > 1) {
            Spacer(Modifier.width(4.dp))
            Text(
                item.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF404550),
            )
        }
    }
}

/**
 * Пузырь выбора значка: двадцать доброжелательных реакций сеткой.
 *
 * Значки въезжают волной - каждый следующий чуть позже предыдущего, - а уже
 * поставленный мягко пульсирует и обведён золотом. Рядом появляется «Убрать
 * реакцию»: менять и снимать нужно там же, где ставил.
 */
@Composable
fun ReactionPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** Значок, который этот телефон уже поставил (null - реакции ещё нет). */
    myEmoji: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Реакция") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReactionPalette.EMOJI.chunked(PICKER_COLUMNS).forEachIndexed { rowIndex, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.forEachIndexed { columnIndex, emoji ->
                            PickerEmoji(
                                emoji = emoji,
                                isMine = emoji == myEmoji,
                                orderIndex = rowIndex * PICKER_COLUMNS + columnIndex,
                                onPick = onPick,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = if (myEmoji != null && onRemove != null) {
            { TextButton(onClick = onRemove) { Text("Убрать реакцию") } }
        } else {
            null
        },
    )
}

/** Значок в пузыре выбора: волна появления плюс пульс у уже выбранного. */
@Composable
private fun PickerEmoji(
    emoji: String,
    isMine: Boolean,
    orderIndex: Int,
    onPick: (String) -> Unit,
) {
    // Пузырь выбора - отдельное окно, поэтому положение переводим в экранное.
    val view = LocalView.current
    var spot by remember { mutableStateOf(0f to 0f) }
    val appear = remember(emoji) { Animatable(0f) }
    LaunchedEffect(emoji) {
        // Волна: 20 значков за примерно четверть секунды, дальше анимации нет.
        delay(orderIndex * 12L)
        appear.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    // Свой значок дышит и слегка покачивается - его видно среди двадцати.
    val pulse = rememberInfiniteTransition(label = "reaction-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isMine) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reaction-pulse-scale",
    )
    val tilt by pulse.animateFloat(
        initialValue = 0f,
        targetValue = if (isMine) 8f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reaction-pulse-tilt",
    )

    Text(
        emoji,
        fontSize = 26.sp,
        modifier = Modifier
            .scale(appear.value * pulseScale)
            .rotate(tilt)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isMine) 1.5.dp else 0.dp,
                color = if (isMine) GoldBorder else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .onGloballyPositioned { coords ->
                val at = coords.positionInWindow()
                spot = toScreenSpot(
                    view,
                    at.x + coords.size.width / 2f,
                    at.y + coords.size.height / 2f,
                )
            }
            .clickable {
                // Значок вылетает из пузыря выбора и уходит вверх мимо него.
                if (spot.second > 0f) {
                    ReactionFlight.launch(emoji, spot.first, spot.second)
                }
                onPick(emoji)
            }
            .padding(horizontal = 4.dp, vertical = 5.dp),
    )
}
