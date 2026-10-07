package com.vladimir.messenger.ui.screens.groups

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.data.group.GroupStatDay
import com.vladimir.messenger.data.group.GroupStats
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.TopicIconCatalog
import com.vladimir.messenger.ui.components.TopicIconView
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val StatisticsGold = Color(0xFFE4B45A)
private val StatisticsGoldDark = Color(0xFF9A7123)
private val StatisticsLocale = Locale.forLanguageTag("ru")
private val StatisticsDateFormat = DateTimeFormatter.ofPattern("d MMMM", StatisticsLocale)

/** Общий дашборд групп и каналов. Источник цифр — существующие локальные счётчики. */
@Composable
internal fun GroupStatisticsTab(
    stats: GroupStats?,
    topics: List<TopicSummary>,
    isChannel: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    if (stats == null) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            ApuBubble {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(24.dp))
                Text(if (isRefreshing) "Загружаем статистику…" else "Статистику пока не удалось загрузить")
                if (!isRefreshing) ApuTextAction(label = "Попробовать ещё раз", onClick = onRefresh)
            }
        }
        return
    }
    val totalViews = stats.channelPosts.sumOf { it.viewCount.toLong() }
    val totalComments = stats.channelPosts.sumOf { it.commentCount.toLong() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ApuBubble {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isChannel) "Статистика канала" else "Статистика группы",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Всё время · последние 7 дней", style = MaterialTheme.typography.bodySmall, color = ApuBubbleMutedColor)
                    }
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить статистику", tint = StatisticsGoldDark)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatisticsMetric(
                    Icons.Filled.People,
                    stats.memberCount,
                    if (isChannel) "Подписчики" else "Участники",
                    Modifier.weight(1f),
                )
                StatisticsMetric(
                    if (isChannel) Icons.Filled.Article else Icons.Filled.Chat,
                    if (isChannel) stats.channelPosts.size else stats.totalMessages,
                    if (isChannel) "Публикации" else "Сообщения",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatisticsMetric(
                    if (isChannel) Icons.Filled.Visibility else Icons.Filled.Forum,
                    if (isChannel) totalViews else stats.topicCount,
                    if (isChannel) "Просмотры постов" else "Темы",
                    Modifier.weight(1f),
                )
                StatisticsMetric(
                    if (isChannel) Icons.Filled.Chat else Icons.Filled.People,
                    if (isChannel) totalComments else stats.activeSenders7Days,
                    if (isChannel) "Комментарии" else "Авторы за 7 дней",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            StatisticsActivity(
                groupId = stats.groupId,
                days = if (isChannel) stats.publicationsLast7Days else stats.last7Days,
                isChannel = isChannel,
            )
        }
        if (isChannel) {
            item {
                ApuBubble {
                    StatisticsHeading("Отклик на публикации")
                    if (stats.channelPosts.isEmpty()) {
                        StatisticsHint("Опубликуйте первый пост — здесь появятся его показатели.")
                    } else {
                        StatisticsSummary("Просмотров в среднем на пост", decimal(totalViews.toDouble() / stats.channelPosts.size))
                        StatisticsSummary("Комментариев в среднем на пост", decimal(totalComments.toDouble() / stats.channelPosts.size))
                        StatisticsHint("Сумма просмотров постов — не число уникальных читателей канала.")
                    }
                }
            }
        }
        item { StatisticsRanking(stats, topics, isChannel) }
        item {
            ApuBubble {
                StatisticsHeading("Управление")
                StatisticsSummary("Администраторов", count(stats.adminCount))
                StatisticsSummary("Заявок в ожидании", count(stats.pendingRequests))
                if (!isChannel) StatisticsSummary("Тем без сообщений", count(stats.perTopic.count { it.value == 0 }))
            }
        }
        item {
            ApuBubble {
                StatisticsHint("Сводка по сохранённым данным. История и счётчики дополняются при синхронизации.")
            }
        }
    }
}

@Composable
private fun StatisticsMetric(icon: ImageVector, value: Number, label: String, modifier: Modifier) {
    ApuBubble(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(StatisticsGold.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = StatisticsGoldDark)
            }
            Spacer(Modifier.height(6.dp))
            val formatted = count(value)
            Text(
                formatted,
                fontSize = if (formatted.length > 10) 22.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, fontSize = 12.sp, minLines = 2, maxLines = 2, textAlign = TextAlign.Center, color = ApuBubbleMutedColor)
        }
    }
}

@Composable
private fun StatisticsActivity(groupId: String, days: List<GroupStatDay>, isChannel: Boolean) {
    ApuBubble {
        StatisticsHeading(if (isChannel) "Публикации за 7 дней" else "Сообщения за 7 дней")
        if (days.isEmpty()) {
            StatisticsHint("Пока нет данных за этот период.")
            return@ApuBubble
        }
        StatisticsHint("${shortDay(days.first().dayKey)} — ${shortDay(days.last().dayKey)} · UTC")
        var selectedKey by rememberSaveable(groupId, isChannel) { mutableStateOf(days.last().dayKey) }
        val selected = days.firstOrNull { it.dayKey == selectedKey } ?: days.last()
        val max = days.maxOf { it.messageCount }.coerceAtLeast(1)
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEach { day ->
                val chosen = day.dayKey == selected.dayKey
                val fraction by animateFloatAsState(
                    targetValue = day.messageCount.toFloat() / max,
                    animationSpec = tween(350),
                    label = "dailyStatisticsBar",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) StatisticsGold.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(role = Role.Button, onClick = { selectedKey = day.dayKey })
                        .semantics {
                            contentDescription = "${longDay(day.dayKey)}: " +
                                (if (isChannel) "публикаций" else "сообщений") +
                                " ${day.messageCount}, авторов ${day.senderCount}"
                        }
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count(day.messageCount), fontSize = 11.sp, maxLines = 1, color = if (chosen) StatisticsGoldDark else ApuBubbleMutedColor)
                            Spacer(Modifier.height(4.dp))
                            val barHeight = if (day.messageCount > 0) (88.dp * fraction).coerceAtLeast(2.dp) else 2.dp
                            Box(
                                Modifier.fillMaxWidth(0.65f).height(barHeight).clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (day.messageCount > 0) Modifier.background(Brush.verticalGradient(listOf(StatisticsGold, StatisticsGoldDark)))
                                        else Modifier.background(ApuBubbleMutedColor.copy(alpha = 0.25f))
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(shortDay(day.dayKey), fontSize = 10.sp, maxLines = 1, color = ApuBubbleMutedColor)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(StatisticsGold.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(longDay(selected.dayKey), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text((if (isChannel) "Публикации: " else "Сообщения: ") + count(selected.messageCount), fontSize = 13.sp)
                Text("Авторов: ${count(selected.senderCount)}", fontSize = 12.sp, color = ApuBubbleMutedColor)
            }
        }
        val total = days.sumOf { it.messageCount.toLong() }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatisticsSmallMetric("Всего", count(total), Modifier.weight(1f))
            StatisticsSmallMetric("В день", decimal(total.toDouble() / days.size), Modifier.weight(1f))
            StatisticsSmallMetric("Активных дней", "${days.count { it.messageCount > 0 }}/${days.size}", Modifier.weight(1f))
        }
        if (total > 0) {
            val peak = days.maxByOrNull { it.messageCount }!!
            StatisticsSummary("Самый активный день", "${shortDay(peak.dayKey)} · ${count(peak.messageCount)}")
        } else {
            StatisticsHint(if (isChannel) "За эти дни новых публикаций не было." else "За эти дни сообщений не было.")
        }
        StatisticsHint("Нажмите на день для подробностей. Текущий день ещё идёт.")
    }
}

@Composable
private fun StatisticsRanking(stats: GroupStats, topics: List<TopicSummary>, isChannel: Boolean) {
    val byId = topics.associateBy { it.id }
    var expanded by rememberSaveable(stats.groupId, isChannel) { mutableStateOf(false) }
    ApuBubble {
        StatisticsHeading(if (isChannel) "Популярные публикации" else "Активность тем")
        if (isChannel) {
            val sorted = stats.channelPosts.sortedWith(compareByDescending<com.vladimir.messenger.data.group.ChannelPostStat> { it.viewCount }
                .thenByDescending { it.commentCount }.thenByDescending { it.publishedAtMs })
            if (sorted.isEmpty()) StatisticsHint("Пока нет публикаций.")
            val max = sorted.maxOfOrNull { it.viewCount }?.coerceAtLeast(1) ?: 1
            sorted.take(if (expanded) sorted.size else 5).forEachIndexed { i, post ->
                StatisticsRankRow(
                    rank = i + 1,
                    title = byId[post.topicId]?.name?.takeIf { it.isNotBlank() } ?: "Публикация",
                    details = "${count(post.viewCount)} просмотров · ${count(post.commentCount)} комментариев",
                    value = count(post.viewCount),
                    fraction = post.viewCount.toFloat() / max,
                    icon = null,
                )
            }
            if (sorted.size > 5) {
                ApuTextAction(
                    label = if (expanded) "Свернуть" else "Все публикации · ${count(sorted.size)}",
                    onClick = { expanded = !expanded },
                )
            }
        } else {
            val sorted = stats.perTopic.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { byId[it.key]?.name.orEmpty() })
            if (sorted.isEmpty()) StatisticsHint("В этой группе нет тем.")
            val total = sorted.sumOf { it.value.toLong() }
            sorted.take(if (expanded) sorted.size else 5).forEachIndexed { i, entry ->
                val topic = byId[entry.key]
                val share = if (total > 0) entry.value.toFloat() / total else 0f
                StatisticsRankRow(
                    rank = i + 1,
                    title = topic?.name?.takeIf { it.isNotBlank() } ?: "Без темы",
                    details = if (entry.value > 0) "${decimal(share * 100.0)}% сообщений группы" else "Пока без сообщений",
                    value = count(entry.value),
                    fraction = share,
                    icon = topic?.iconEmoji?.ifBlank { TopicIconCatalog.DEFAULT } ?: TopicIconCatalog.DEFAULT,
                )
            }
            if (sorted.size > 5) {
                ApuTextAction(
                    label = if (expanded) "Свернуть" else "Все темы · ${count(sorted.size)}",
                    onClick = { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun StatisticsRankRow(rank: Int, title: String, details: String, value: String, fraction: Float, icon: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(StatisticsGold.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                if (icon == null) Text(rank.toString(), color = StatisticsGoldDark, fontWeight = FontWeight.SemiBold)
                else TopicIconView(icon, 25.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(details, style = MaterialTheme.typography.bodySmall, color = ApuBubbleMutedColor)
            }
            Text(value, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(StatisticsGold.copy(alpha = 0.16f))) {
            // Нулю соответствует пустая полоса: не рисуем фиктивную активность.
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(StatisticsGold, StatisticsGoldDark))))
            }
        }
    }
}

@Composable
private fun StatisticsSmallMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(label, fontSize = 11.sp, color = ApuBubbleMutedColor, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatisticsSummary(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, color = ApuBubbleMutedColor)
        Text(value, modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun StatisticsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ApuBubbleTextColor)
}

@Composable
private fun StatisticsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ApuBubbleMutedColor)
}

private fun count(value: Number): String = NumberFormat.getIntegerInstance(StatisticsLocale).format(value)
private fun decimal(value: Double): String = String.format(StatisticsLocale, "%.1f", value)
private fun shortDay(key: String): String = runCatching { LocalDate.parse(key).format(DateTimeFormatter.ofPattern("dd.MM")) }.getOrDefault(key)
private fun longDay(key: String): String = runCatching { LocalDate.parse(key).format(StatisticsDateFormat) }.getOrDefault(key)
