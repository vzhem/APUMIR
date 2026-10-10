package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Раунд 203: «Поделиться в APU» - пересылка сообщения с указанием источника.
// Цель: друг (личный чат) ИЛИ группа+тема ИЛИ канал+пост (пост канала - это
// тема с комментариями). Ссылка на источник пишется НАД пересылаемым текстом
// (см. forwardMessage в ChatDetailViewModel / GroupChatViewModel).

enum class ForwardKind { FRIEND, GROUP }

/** Куда пересылаем: чат друга, группа+тема или канал+пост. */
data class ForwardTarget(
    val kind: ForwardKind,
    /** chatId у друга, groupId у группы/канала. */
    val id: String,
    val title: String,
    val isChannel: Boolean = false,
    /** Выбранная тема группы / пост канала. */
    val topicId: String? = null,
    val topicTitle: String? = null,
)

/** Что пересылаем: текст сообщения и подпись источника для шапки. */
data class ForwardPayload(val text: String, val label: String)

data class ForwardTopic(val id: String, val name: String, val iconEmoji: String = "")

@Composable
private fun ForwardRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ForwardLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.action_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Выбор адресата: друзья, затем группы, затем каналы. */
@Composable
fun ForwardChooserDialog(
    targets: List<ForwardTarget>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (ForwardTarget) -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_share_in_apu)) },
        text = {
            if (loading) {
                ForwardLoading()
            } else {
                val friends = targets.filter { it.kind == ForwardKind.FRIEND }
                val groups = targets.filter { it.kind == ForwardKind.GROUP && !it.isChannel }
                val channels = targets.filter { it.kind == ForwardKind.GROUP && it.isChannel }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (friends.isEmpty() && groups.isEmpty() && channels.isEmpty()) {
                        Text(
                            stringResource(R.string.fwd_nobody),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (friends.isNotEmpty()) {
                        Text(
                            stringResource(R.string.fwd_friends),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        friends.forEach { target ->
                            ForwardRow(target.title) { onPick(target) }
                        }
                    }
                    if (groups.isNotEmpty()) {
                        Text(
                            stringResource(R.string.inbox_groups),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        groups.forEach { target ->
                            ForwardRow(target.title) { onPick(target) }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        Text(
                            stringResource(R.string.inbox_channels),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        channels.forEach { target ->
                            ForwardRow(target.title) { onPick(target) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** Выбор темы группы / поста канала - второй шаг для групп и каналов. */
@Composable
fun ForwardTopicPickerDialog(
    targetTitle: String,
    isChannel: Boolean,
    topics: List<ForwardTopic>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (ForwardTopic) -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChannel) stringResource(R.string.fwd_pick_post) else stringResource(R.string.fwd_pick_topic)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (loading) {
                    ForwardLoading()
                } else if (topics.isEmpty()) {
                    Text(
                        if (isChannel) {
                            stringResource(R.string.fwd_no_posts, targetTitle)
                        } else {
                            stringResource(R.string.fwd_no_topics, targetTitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Раунд 203: только название - ключ значка (iconEmoji)
                    // в строке выглядел «странным текстом» (владелец, скрин).
                    topics.forEach { topic ->
                        ForwardRow(topic.name) { onPick(topic) }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** Раунд 203: подтверждение пересылки - плашка по центру экрана (владелец). */
@Composable
fun ForwardSentOverlay(visible: Boolean, onTimeout: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(2200)
            onTimeout()
        }
    }
    if (!visible) return
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.fwd_sent),
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(
                    androidx.compose.ui.graphics.Color(0xCC1E2430),
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}
