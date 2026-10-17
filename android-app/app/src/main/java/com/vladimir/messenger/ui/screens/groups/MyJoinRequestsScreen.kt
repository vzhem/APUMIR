package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.MyJoinRequestStore
import com.vladimir.messenger.data.group.MyJoinRequestSummary
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuCommunitiesEmpty
import com.vladimir.messenger.ui.components.ApuGold
import com.vladimir.messenger.ui.components.ApuPremiumChip
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.swipeBack
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Фильтр списка заявок: все, ждут ответа, одобренные, отклонённые. */
private enum class JoinFilter(val labelRes: Int) {
    ALL(R.string.my_join_filter_all),
    PENDING(R.string.my_join_filter_pending),
    APPROVED(R.string.my_join_filter_approved),
    REJECTED(R.string.my_join_filter_rejected);

    fun matches(request: MyJoinRequestSummary): Boolean = when (this) {
        ALL -> true
        PENDING -> request.status == MyJoinRequestStore.PENDING
        APPROVED -> request.status == MyJoinRequestStore.APPROVED
        REJECTED -> request.status == MyJoinRequestStore.REJECTED
    }
}

/**
 * Что человеку сейчас показываем. Это не статус из базы, а вывод из статуса,
 * очереди досылки и того, дошла ли заявка до владельца.
 */
private enum class JoinState { APPROVED, REJECTED, SENDING, RECEIVED, EXPIRED }

private fun joinStateOf(request: MyJoinRequestSummary): JoinState = when {
    request.status == MyJoinRequestStore.APPROVED -> JoinState.APPROVED
    request.status == MyJoinRequestStore.REJECTED -> JoinState.REJECTED
    !request.queued -> JoinState.EXPIRED
    request.ownerReceivedAtMs > 0L -> JoinState.RECEIVED
    else -> JoinState.SENDING
}

private fun formatJoinTime(ms: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyJoinRequestsScreen(
    channels: Boolean,
    onBackClick: () -> Unit,
    /** Открыть группу или канал, куда одобрили заявку. */
    onOpenCommunity: (groupId: String, isChannel: Boolean) -> Unit,
    viewModel: MyJoinRequestsViewModel = hiltViewModel(),
) {
    val all by viewModel.requests.collectAsStateWithLifecycle()
    val shown = remember(all, channels) { all.filter { it.isChannel == channels } }
    var filter by rememberSaveable { mutableStateOf(JoinFilter.ALL) }
    var cancelTarget by remember { mutableStateOf<MyJoinRequestSummary?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val visible = shown.filter { filter.matches(it) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = {
                        ApuSettingsHeader(
                            stringResource(
                                if (channels) R.string.my_join_title_channels else R.string.my_join_title_groups,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        ApuCommunitiesEmpty(
                            icon = Icons.Filled.Forum,
                            title = stringResource(R.string.my_join_empty_title),
                            body = stringResource(R.string.my_join_empty_body),
                        )
                    }
                } else {
                    item(key = "summary") {
                        val pending = shown.count { it.status == MyJoinRequestStore.PENDING }
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.my_join_requests_count,
                                    shown.size,
                                    shown.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ApuBubbleTextColor,
                            )
                            Text(
                                text = "${stringResource(JoinFilter.PENDING.labelRes)}: $pending",
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                    }
                    item(key = "filters") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            JoinFilter.entries.forEach { option ->
                                ApuPremiumChip(
                                    label = stringResource(option.labelRes),
                                    selected = filter == option,
                                    onClick = { filter = option },
                                )
                            }
                        }
                    }
                    items(visible, key = { it.groupId }) { request ->
                        JoinRequestCard(
                            request = request,
                            locale = locale,
                            onOpen = { onOpenCommunity(request.groupId, request.isChannel) },
                            onResend = { viewModel.resend(request.groupId) },
                            onCancel = { cancelTarget = request },
                        )
                    }
                }
            }
        }
    }

    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text(stringResource(R.string.my_join_cancel_title)) },
            text = { Text(stringResource(R.string.my_join_cancel_body)) },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.my_join_remove),
                    danger = true,
                    onClick = {
                        viewModel.cancel(target.groupId)
                        cancelTarget = null
                    },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.my_join_keep), onClick = { cancelTarget = null })
            },
        )
    }
}

@Composable
private fun JoinRequestCard(
    request: MyJoinRequestSummary,
    locale: Locale,
    onOpen: () -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = joinStateOf(request)
    val untitled = request.title.isBlank()
    val kind = stringResource(if (request.isChannel) R.string.my_join_kind_channel else R.string.my_join_kind_group)
    val members = when {
        request.memberCount <= 0 -> kind
        request.isChannel -> kind + " · " + pluralStringResource(R.plurals.my_join_subscribers, request.memberCount, request.memberCount)
        else -> kind + " · " + pluralStringResource(R.plurals.my_join_members, request.memberCount, request.memberCount)
    }

    ApuSettingsCard(highlighted = state == JoinState.APPROVED) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApuPremiumIconTile(
                icon = if (request.isChannel) Icons.Filled.Campaign else Icons.Filled.Groups,
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (untitled) stringResource(R.string.my_join_untitled) else request.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (untitled) ApuBubbleMutedColor else ApuBubbleTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = members,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(state)
        }

        Spacer(Modifier.height(14.dp))
        JoinTimeline(request = request, state = state, locale = locale)

        val details = buildList {
            if (request.groupCreatedAtMs > 0L) {
                add(stringResource(R.string.my_join_created, formatJoinTime(request.groupCreatedAtMs, locale)))
            }
            if (request.ownerName.isNotBlank()) {
                add(stringResource(R.string.my_join_owner, request.ownerName))
            }
            if (request.note.isNotBlank()) {
                add(stringResource(R.string.my_join_note, request.note))
            }
            if (state == JoinState.SENDING || state == JoinState.RECEIVED) {
                if (request.expiresAtMs > 0L) {
                    add(stringResource(R.string.my_join_retry_until, formatJoinTime(request.expiresAtMs, locale)))
                }
            }
            if (state == JoinState.EXPIRED) add(stringResource(R.string.my_join_expired_hint))
        }
        if (details.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            details.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val actions = buildList {
            if (state == JoinState.APPROVED) {
                add(Triple(R.string.my_join_open, onOpen, false))
            }
            if (state == JoinState.SENDING || state == JoinState.RECEIVED) {
                add(Triple(R.string.my_join_resend, onResend, false))
            }
            if (state == JoinState.SENDING || state == JoinState.RECEIVED || state == JoinState.EXPIRED) {
                add(Triple(R.string.my_join_cancel, onCancel, true))
            }
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                actions.forEach { (labelRes, action, danger) ->
                    ApuTextAction(
                        label = stringResource(labelRes),
                        danger = danger,
                        onClick = action,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: JoinState) {
    val (labelRes, ink, fill) = when (state) {
        JoinState.APPROVED -> Triple(R.string.my_join_status_approved, Color(0xFF1B6E3A), Color(0x1F1B6E3A))
        JoinState.REJECTED -> Triple(R.string.my_join_status_rejected, Color(0xFFB3261E), Color(0x1FB3261E))
        JoinState.SENDING -> Triple(R.string.my_join_status_sending, ApuBubbleAccentColor, ApuGold.copy(alpha = 0.18f))
        JoinState.RECEIVED -> Triple(R.string.my_join_status_received, ApuBubbleAccentColor, ApuGold.copy(alpha = 0.28f))
        JoinState.EXPIRED -> Triple(R.string.my_join_status_expired, ApuBubbleMutedColor, Color(0x1F000000))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
    }
}

/** Три шага: отправлена → получена владельцем → ответ. Пройденные шаги золотые. */
@Composable
private fun JoinTimeline(request: MyJoinRequestSummary, state: JoinState, locale: Locale) {
    val waiting = stringResource(R.string.my_join_step_waiting)
    val steps = listOf(
        Triple(
            R.string.my_join_step_sent,
            formatJoinTime(request.requestedAtMs, locale),
            true,
        ),
        Triple(
            R.string.my_join_step_received,
            if (request.ownerReceivedAtMs > 0L) formatJoinTime(request.ownerReceivedAtMs, locale) else waiting,
            request.ownerReceivedAtMs > 0L,
        ),
        Triple(
            R.string.my_join_step_decided,
            if (request.decidedAtMs > 0L) formatJoinTime(request.decidedAtMs, locale) else waiting,
            state == JoinState.APPROVED || state == JoinState.REJECTED,
        ),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, (labelRes, time, done) ->
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (done) ApuGold else ApuBubbleMutedColor.copy(alpha = 0.35f)),
                    )
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .background(if (done) ApuGold else ApuBubbleMutedColor.copy(alpha = 0.2f)),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuBubbleTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = ApuBubbleMutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
