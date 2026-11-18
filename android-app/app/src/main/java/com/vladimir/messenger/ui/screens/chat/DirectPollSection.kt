package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.PollSummary
import com.vladimir.messenger.ui.components.ApuPollCard
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * Опрос личного чата на экране (р252): карточка в ленте и окно с итогами.
 *
 * Вынесено из `ChatDetailScreen.kt` отдельным файлом: у того файла уже выбран
 * потолок строк, и новый код в него не помещается.
 *
 * Две части одного поведения:
 *  - [DirectPollFeedCard] — карточка опроса в ленте. Вопрос печатается в ней
 *    же, а сообщение-вопрос не рисуется вовсе (`hideBubble` в ленте): владелец
 *    просил, чтобы вопрос был вместе с табличкой, а не отдельным сообщением.
 *  - [DirectPollResultsDialog] — то же самое во весь экран. Нужно, чтобы к
 *    опросу можно было вернуться, в том числе к закрытому: в ленте карточка
 *    маленькая, а итоги после закрытия посмотреть больше негде.
 */
@Composable
internal fun DirectPollFeedCard(
    poll: PollSummary,
    onToggle: (Int) -> Unit,
    onClose: () -> Unit,
    canClose: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ApuPollCard(
        poll = poll,
        onToggle = onToggle,
        onClose = onClose,
        canClose = canClose,
        // Вопрос — часть таблички: отдельного пузыря у этого сообщения нет.
        showQuestion = true,
        onOpen = onOpen,
        modifier = modifier,
    )
}

/**
 * Окно итогов. [poll] равен null, когда открывать нечего — тогда ничего не
 * рисуется, а закрытое состояние экрана остаётся прежним.
 */
@Composable
internal fun DirectPollResultsDialog(
    poll: PollSummary?,
    onToggle: (PollSummary, Int) -> Unit,
    onClose: (PollSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val opened = poll ?: return
    // Фирменное окно, а не stock AlertDialog: плоские Material-углы и чужая
    // «рябь» в этом приложении запрещены проверкой стиля.
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (opened.anonymous) stringResource(R.string.poll_anon) else stringResource(R.string.poll_label)) },
        text = {
            ApuPollCard(
                poll = opened,
                onToggle = { index -> onToggle(opened, index) },
                onClose = { onClose(opened) },
                canClose = opened.isMine && !opened.closed,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.poll_results_done),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * Оба окна опроса личного чата в одном вызове: создание и итоги.
 *
 * Так они попадают на экран одной строкой-вызовом, не раздувая `ChatDetailScreen`
 * (у него выбран потолок строк), и порядок окон задан здесь, а не в ленте.
 */
@Composable
internal fun DirectPollDialogs(
    showCreate: Boolean,
    onDismissCreate: () -> Unit,
    onCreate: (question: String, options: List<String>, anonymous: Boolean) -> Unit,
    polls: Map<String, PollSummary>,
    openMessageId: String?,
    onDismissOpen: () -> Unit,
    onToggle: (PollSummary, Int) -> Unit,
    onClose: (PollSummary) -> Unit,
) {
    if (showCreate) {
        com.vladimir.messenger.ui.components.CreatePollDialog(
            onDismiss = onDismissCreate,
            // Успешное создание тоже закрывает окно: отдельный колбэк на это
            // заставлял каждый экран повторять одну и ту же строку.
            onCreate = { draft ->
                onDismissCreate()
                onCreate(draft.question, draft.options, draft.anonymous)
            },
        )
    }
    DirectPollResultsDialog(
        poll = openMessageId?.let { polls[it] },
        onToggle = onToggle,
        onClose = onClose,
        onDismiss = onDismissOpen,
    )
}
