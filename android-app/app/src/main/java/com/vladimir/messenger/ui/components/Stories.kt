package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryStore
import kotlinx.coroutines.delay

/** Четыре фона историй. Порядок совпадает с индексом `bg` в пакете. */
private val storyBackgrounds: List<Brush> = listOf(
    Brush.linearGradient(listOf(Color(0xFF1E2A78), Color(0xFF6B2FA0))),
    Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF1D4ED8))),
    Brush.linearGradient(listOf(Color(0xFFB45309), Color(0xFFBE123C))),
    Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF1F2937))),
)

/** Полоса историй над списком чатов: «Моя» и по одному кружку на автора. */
@Composable
fun StoriesStrip(
    stories: List<StoryStore.Story>,
    onAddClick: () -> Unit,
    onOpenAuthor: (authorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = stories.filter { it.mine }
    val byAuthor = stories.filter { !it.mine }
        .groupBy { it.authorId }
        .map { (_, list) -> list.sortedBy { it.createdAtMs } }
        .sortedByDescending { it.last().createdAtMs }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "mine") {
            StoryBubble(
                title = stringResource(R.string.story_mine),
                initial = "+",
                hasStories = mine.isNotEmpty(),
                onClick = if (mine.isNotEmpty()) ({ onOpenAuthor(mine.first().authorId) }) else onAddClick,
            )
        }
        items(byAuthor, key = { it.first().authorId }) { list ->
            val name = list.last().authorName.ifBlank { "?" }
            StoryBubble(
                title = name,
                initial = name.take(1).uppercase(),
                hasStories = true,
                onClick = { onOpenAuthor(list.first().authorId) },
            )
        }
    }
}

@Composable
private fun StoryBubble(
    title: String,
    initial: String,
    hasStories: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(
                    if (hasStories) Brush.linearGradient(listOf(Color(0xFFE0B458), Color(0xFFB8860B)))
                    else Brush.linearGradient(listOf(Color(0xFFD1D5DB), Color(0xFFD1D5DB))),
                    CircleShape,
                )
                .padding(3.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(3.dp)
                .background(Color(0xFF1E2A78), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Просмотр историй одного автора: по 5 секунд, нажатия слева и справа листают. */
@Composable
fun StoryViewerDialog(
    stories: List<StoryStore.Story>,
    onDismiss: () -> Unit,
) {
    if (stories.isEmpty()) return
    var index by remember(stories.first().authorId) { mutableIntStateOf(0) }
    val current = stories[index.coerceIn(0, stories.lastIndex)]
    LaunchedEffect(index, current.id) {
        delay(5_000)
        if (index < stories.lastIndex) index++ else onDismiss()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(storyBackgrounds[current.bg.coerceIn(0, storyBackgrounds.lastIndex)]),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 48.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stories.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .background(
                                    if (i <= index) Color.White else Color.White.copy(alpha = 0.3f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (current.mine) stringResource(R.string.story_you) else current.authorName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        current.text,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // Нажатие слева - назад, справа - вперёд.
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxSize().clickable {
                        if (index > 0) index--
                    },
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxSize().clickable {
                        if (index < stories.lastIndex) index++ else onDismiss()
                    },
                )
            }
        }
    }
}

/** Редактор своей истории: текст, фон и «Опубликовать». */
@Composable
fun StoryComposerDialog(
    onDismiss: () -> Unit,
    onPublish: (text: String, bg: Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(storyBackgrounds[bg])
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.story_new),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 200) text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text(stringResource(R.string.story_placeholder)) },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                storyBackgrounds.forEachIndexed { i, brush ->
                    Box(
                        modifier = Modifier
                            .size(if (i == bg) 34.dp else 28.dp)
                            .background(brush, CircleShape)
                            .clickable { bg = i },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApuTextAction(
                    label = stringResource(R.string.voice_cancel),
                    onClick = onDismiss,
                )
                ApuTextAction(
                    label = stringResource(R.string.story_publish),
                    onClick = { onPublish(text, bg) },
                    enabled = text.isNotBlank(),
                )
            }
        }
    }
}
