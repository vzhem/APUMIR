package com.vladimir.messenger.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryFeedbackLedger
import com.vladimir.messenger.data.story.StoryFeedbackStore
import com.vladimir.messenger.data.story.StoryFeedbackWire
import com.vladimir.messenger.ui.theme.AvatarStore

// =============================================================================
// STORYFEEDBACKUI.KT — реакции и комментарии на истории
// =============================================================================
// Владелец 2026-10-14: «Нужно сделать чтобы на историю можно было ставить
// реакцию и писать комментарии. Всё как у топовых мессенджеров». Лента
// откликов публичная: значки со счётчиками и комментарии видит каждый, кто
// открыл историю (доставка - docs/STORY_REACTIONS_AND_COMMENTS.md).
//
// История показывается на чёрном/градиентном полноэкранном просмотрщике,
// поэтому отклики здесь не светлыми пузырями, как в переписке, а translucent-
// плашками поверх снимка - так же, как у привычных мессенджеров. Палитра
// значков и окно выбора - общие с перепиской (ReactionPalette из 20
// доброжелательных значков и ReactionPickerDialog), чтобы человек не учился
// двум разным наборам.
// =============================================================================

/** Золото истории: тот же тон, что у кольца своего кружка в ленте. */
private val StoryFeedbackGold = Color(0xFFE6BE70)

/** Плашка отклика поверх снимка: белая полупрозрачная, как элементы просмотрщика. */
private val StoryFeedbackFill = Color.White.copy(alpha = 0.16f)

/** Сколько значков показываем в ряду; остальные сворачиваются в «+N». */
private const val MAX_VISIBLE_REACTIONS = 5

/**
 * Отклики одной истории для экрана: перерисовываются, когда книга откликов
 * изменилась (пришёл пакет, поставил свой значок).
 */
@Composable
fun rememberStoryFeedback(storyId: String): StoryFeedbackLedger.Summary {
    val version by StoryFeedbackStore.changes.collectAsStateWithLifecycle()
    val ownId by StoryFeedbackStore.ownId.collectAsStateWithLifecycle()
    return remember(version, ownId, storyId) { StoryFeedbackStore.summary(storyId) }
}

/**
 * Панель откликов внизу просмотрщика: значки с публичными счётчиками, вход в
 * палитру и вход в комментарии.
 *
 * Тап по значку ставит его (или снимает, если он уже мой) - так же, как ряд
 * реакций под сообщением в переписке.
 */
@Composable
fun StoryFeedbackBar(
    summary: StoryFeedbackLedger.Summary,
    onOpenPicker: () -> Unit,
    onToggleEmoji: (String) -> Unit,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Ряд объявляется вслух целиком: значки со счётчиками читаются как
        // «Реакции: 3», а пустой ряд - как «Пока никто не отреагировал».
        // Слияния потомков нет, поэтому каждый значок остаётся нажимаемым.
        val reactionsDescription = if (summary.reactionTotal == 0) {
            stringResource(R.string.story_reactions_empty)
        } else {
            stringResource(R.string.story_reactions_count, summary.reactionTotal)
        }
        Row(
            modifier = Modifier.weight(1f).semantics { contentDescription = reactionsDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            summary.reactions.take(MAX_VISIBLE_REACTIONS).forEach { item ->
                StoryReactionChip(item = item, onToggle = onToggleEmoji)
            }
            if (summary.reactions.size > MAX_VISIBLE_REACTIONS) {
                Text(
                    "+" + (summary.reactions.size - MAX_VISIBLE_REACTIONS),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        StoryFeedbackPill(
            glyph = "\uD83D\uDE00",
            description = stringResource(R.string.story_reaction_add),
            onClick = onOpenPicker,
        )
        StoryFeedbackPill(
            glyph = "\uD83D\uDCAC",
            description = if (summary.comments.isEmpty()) {
                stringResource(R.string.story_comments_open)
            } else {
                stringResource(R.string.story_comments_count, summary.comments.size)
            },
            count = summary.comments.size,
            onClick = onOpenComments,
        )
    }
}

/** Значок с публичным счётчиком: подпрыгивает при появлении и при счёте. */
@Composable
private fun StoryReactionChip(
    item: StoryFeedbackLedger.ReactionCount,
    onToggle: (String) -> Unit,
) {
    // Animatable, а не animateFloatAsState: нужен толчок «сжался - отпружинил»,
    // как у значков в переписке, а не плавный переход между двумя значениями.
    val bounce = remember(item.emoji) { Animatable(0.75f) }
    LaunchedEffect(item.emoji, item.count, item.mine) {
        bounce.snapTo(0.75f)
        bounce.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    val view = LocalView.current
    var spot by remember { mutableStateOf(0f to 0f) }
    val shape = RoundedCornerShape(14.dp)
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
            .clip(shape)
            .background(StoryFeedbackFill)
            .border(
                width = if (item.mine) 1.5.dp else 0.dp,
                color = if (item.mine) StoryFeedbackGold else Color.Transparent,
                shape = shape,
            )
            .apuTapHaptics()
            .clickable {
                if (!item.mine && spot.second > 0f) {
                    ReactionFlight.launch(item.emoji, spot.first, spot.second)
                }
                onToggle(item.emoji)
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.emoji, fontSize = 15.sp)
        Spacer(Modifier.width(4.dp))
        // Счётчик публичный: его видно всем зрителям истории, поэтому показываем
        // и единицу (в переписке единица скрыта - там значок личный).
        Text(
            item.count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Круглая плашка действия: палитра значков или лента комментариев. */
@Composable
private fun StoryFeedbackPill(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    count: Int = 0,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(StoryFeedbackFill)
            .semantics { contentDescription = description }
            .apuTapHaptics()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 15.sp)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Комментарии истории: поле ввода сверху (его не уносит клавиатура и не
 * прячет прокрутка карточки), ниже - лента.
 *
 * Окно фирменное ([ApuSettingsDialog]), поэтому внутри обычный [Column], а не
 * ленивый список: карточка окна уже прокручивается целиком, а второй
 * прокручиваемый список внутри неё Compose не измеряет. Комментариев на одну
 * историю не больше [StoryFeedbackLedger.MAX_COMMENTS_PER_STORY], так что
 * прокрутки карточки хватает.
 */
@Composable
fun StoryCommentsDialog(
    summary: StoryFeedbackLedger.Summary,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val limit = StoryFeedbackWire.MAX_COMMENT_CHARS
    val canSend = StoryFeedbackWire.sanitizeComment(draft) != null
    val send = {
        val body = StoryFeedbackWire.sanitizeComment(draft)
        if (body != null) {
            onSend(body)
            draft = ""
        }
    }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.story_comments)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ApuBubbleField(
                    value = draft,
                    onValueChange = { draft = it.take(limit) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.story_comment_hint)) },
                    supportingText = {
                        Text(
                            if (draft.isEmpty()) {
                                stringResource(R.string.story_comment_limit)
                            } else {
                                "${draft.length}/$limit"
                            },
                        )
                    },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.height(12.dp))
                if (summary.comments.isEmpty()) {
                    Text(
                        stringResource(R.string.story_comments_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                } else {
                    summary.comments.forEach { comment ->
                        StoryCommentRow(comment = comment)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.story_feedback_public),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleMutedColor,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.story_comment_send),
                onClick = { send() },
                enabled = canSend,
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
    )
}

/** Строка комментария: аватар, имя, текст и когда написано. */
@Composable
private fun StoryCommentRow(comment: StoryFeedbackWire.Comment) {
    val ownId by StoryFeedbackStore.ownId.collectAsStateWithLifecycle()
    val avatars by AvatarStore.avatars.collectAsStateWithLifecycle()
    val mine = ownId.isNotBlank() && comment.actorId == ownId
    val name = when {
        mine -> stringResource(R.string.story_you)
        comment.actorName.isNotBlank() -> comment.actorName
        else -> stringResource(R.string.story_feedback_unnamed)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (mine) {
            MyAvatar(displayName = name, modifier = Modifier.size(32.dp), size = 32)
        } else {
            PeerAvatar(name, avatars[comment.actorId], 32.dp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ApuBubbleAccentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                comment.body,
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleTextColor,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(
                    comment.atMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = ApuBubbleMutedColor,
                maxLines = 1,
            )
        }
    }
}
