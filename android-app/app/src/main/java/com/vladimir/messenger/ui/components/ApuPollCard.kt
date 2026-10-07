package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.group.PollDraft
import com.vladimir.messenger.data.group.PollSummary

/**
 * Карточка опроса под сообщением группы или постом канала (р250).
 *
 * Опрос живёт отдельным конвертом, поэтому у телефонов прежней версии
 * карточки не будет вовсе: они увидят вопрос обычным текстом. Здесь же
 * рисуются варианты, проценты и число проголосовавших.
 *
 * Отметка своего голоса берётся из [PollSummary.myChoices]: в анонимном
 * опросе имена не показываются, но свой выбор человек видит.
 */
@Composable
fun ApuPollCard(
    poll: PollSummary,
    /** Отметить (или снять) вариант с этим номером. */
    onToggle: (Int) -> Unit,
    /** Закрыть опрос: кнопку видит автор, владелец и администратор. */
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Показывать ли кнопку «Закрыть опрос» (права решает вызывающий экран). */
    canClose: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.BarChart,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = ApuBubbleAccentColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (poll.anonymous) "Опрос · анонимный" else "Опрос",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ApuBubbleAccentColor,
            )
            if (poll.closed) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Опрос закрыт",
                    modifier = Modifier.size(14.dp),
                    tint = ApuBubbleMutedColor,
                )
            }
        }
        Text(
            poll.question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // До голосования проценты не показываем: считаем, что подглядывать в
        // пустой опрос незачем, а спорных цифр «0 %» на экране меньше.
        val voted = poll.myChoices.isNotEmpty()
        val showResults = voted || poll.closed
        poll.options.forEach { option ->
            val chosen = option.index in poll.myChoices
            val percent = poll.percentOf(option.index)
            PollOptionRow(
                text = option.text,
                percent = percent,
                chosen = chosen,
                showResults = showResults,
                enabled = !poll.closed,
                onClick = { onToggle(option.index) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pollVotersLabel(poll),
                style = MaterialTheme.typography.labelSmall,
                color = ApuBubbleMutedColor,
                modifier = Modifier.weight(1f),
            )
            if (canClose && !poll.closed) {
                ApuTextAction(label = "Закрыть опрос", onClick = onClose)
            }
        }
        // Кто как проголосовал: только в открытом опросе, и только тех, чей
        // голос уже доехал до этого телефона.
        if (!poll.anonymous && poll.voters.isNotEmpty()) {
            val names = poll.voters
                .filter { it.choices.isNotEmpty() }
                .take(3)
                .joinToString(", ") { it.name.ifBlank { "Участник " + it.nodeId.takeLast(4) } }
            if (names.isNotBlank()) {
                Text(
                    names + if (poll.voters.size > 3) " и другие" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    text: String,
    percent: Int,
    chosen: Boolean,
    showResults: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .border(
                1.dp,
                if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape,
            )
            .background(
                if (chosen) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = ApuBubbleTextColor,
            )
            if (showResults) {
                Text(
                    "$percent %",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (chosen) MaterialTheme.colorScheme.primary else ApuBubbleMutedColor,
                )
            }
            if (chosen) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Ваш выбор",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (showResults) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun pollVotersLabel(poll: PollSummary): String {
    val count = poll.totalVoters
    if (count == 0) return "Пока никто не голосовал"
    val word = when {
        count % 10 == 1 && count % 100 != 11 -> "человек"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "человека"
        else -> "человек"
    }
    val votes = poll.totalVotes
    return if (poll.multiChoice && votes != count) {
        "$count $word · $votes голосов"
    } else {
        "$count $word"
    }
}

/**
 * Окно создания опроса (р250): вопрос, до [GroupWire.MAX_POLL_OPTIONS]
 * вариантов и два переключателя - анонимность и несколько вариантов.
 *
 * Пустые варианты не отправляются: человек часто заводит поля «на вырост», и
 * отправлять в опрос пустые строки незачем.
 */
/**
 * Черновик опроса, которым управляет экран (р250).
 *
 * Вынесен из диалога, потому что поля опроса нужны в двух местах: в окне
 * создания опроса в чате и прямо в редакторе поста канала. Состояние живёт в
 * композиции, поэтому при перевороте экрана сохраняется, пока жив экран.
 */
class PollDraftState {
    var question: String by mutableStateOf("")
    /** Два обязательных поля; остальные добавляет кнопкой сам человек. */
    val options: androidx.compose.runtime.snapshots.SnapshotStateList<String> = mutableStateListOf("", "")
    var anonymous: Boolean by mutableStateOf(false)
    var multiChoice: Boolean by mutableStateOf(false)

    /** Заполненные варианты: пустые поля в опрос не едут. */
    fun filledOptions(): List<String> =
        options.map { it.trim() }.filter { it.isNotBlank() }
            .take(GroupWire.MAX_POLL_OPTIONS)
            .map { it.take(GroupWire.MAX_POLL_OPTION_CHARS) }

    /** Готовый черновик или null, если заполнено недостаточно. */
    fun draft(): PollDraft? {
        val question = question.trim()
        val variants = filledOptions()
        if (question.isBlank() || variants.size < GroupWire.MIN_POLL_OPTIONS) return null
        return PollDraft(question, variants, anonymous, multiChoice)
    }

    /** Что не так с черновиком; null - всё в порядке. */
    fun problem(): String? {
        if (question.isBlank()) return "Вопрос не может быть пустым"
        if (filledOptions().size < GroupWire.MIN_POLL_OPTIONS) {
            return "Нужно хотя бы ${GroupWire.MIN_POLL_OPTIONS} варианта ответа"
        }
        return null
    }
}

/** Поля опроса: вопрос, варианты и два переключателя. */
@Composable
fun PollDraftFields(
    state: PollDraftState,
    modifier: Modifier = Modifier,
    /** Текст ошибки проверки (показывается под полями). */
    problem: String? = null,
) {
    Column(modifier = modifier) {
        ApuBubbleField(value = state.question, onValueChange = { state.question = it }, label = { Text("Вопрос") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Spacer(Modifier.height(8.dp))
        state.options.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ApuBubbleField(value = text, onValueChange = { state.options[index] = it }, label = { Text("Вариант ${index + 1}") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (state.options.size < GroupWire.MAX_POLL_OPTIONS) {
            ApuTextAction(
                label = "+ Добавить вариант",
                onClick = { state.options.add("") },
                modifier = Modifier.align(Alignment.Start),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Анонимный",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = state.anonymous, onCheckedChange = { state.anonymous = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Несколько вариантов",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = state.multiChoice, onCheckedChange = { state.multiChoice = it })
        }
        Text(
            "Анонимный опрос показывает только числа: кто как проголосовал, не видно никому.",
            style = MaterialTheme.typography.labelSmall,
            color = ApuBubbleMutedColor,
        )
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.labelSmall,
                color = ApuSettingsDangerColor,
            )
        }
    }
}

@Composable
fun CreatePollDialog(
    onDismiss: () -> Unit,
    onCreate: (PollDraft) -> Unit,
) {
    val state = remember { PollDraftState() }
    var problem by remember { mutableStateOf<String?>(null) }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый опрос") },
        text = {
            PollDraftFields(state = state, problem = problem)
        },
        confirmButton = {
            ApuTextAction(
                label = "Создать",
                onClick = {
                    val issue = state.problem()
                    if (issue != null) {
                        problem = issue
                        return@TextButton
                    }
                    state.draft()?.let(onCreate)
                },
            )
        },
        dismissButton = {
            ApuTextAction(label = "Отмена", onClick = onDismiss)
        },
    )
}
