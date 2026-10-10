package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsChip
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuSettingsProgress

// =============================================================================
// PEERRATINGSCREEN.KT — «Узлы сети»: кто из собеседников надёжный ретранслятор
// =============================================================================
// Раздел, в который раньше вела строка «Подключено пиров». Здесь видно не
// только число, но и КАКИЕ узлы держат сеть: кто всегда на месте, кто быстро
// отдаёт файлы, к кому можно подключиться напрямую.
// =============================================================================

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.peer.PeerRatingStore
import com.vladimir.messenger.data.peer.PeerStats
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.swipeBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerRatingScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    // Список перечитываем по кнопке сброса: постоянный опрос настроек на
    // каждой перерисовке - лишняя работа на главном потоке.
    var reloadTick by remember { mutableStateOf(0) }
    val peers = remember(reloadTick) { PeerRatingStore.ranked(context, now) }
    val scrollState = rememberLazyListState()

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
                    title = { ApuSettingsHeader(stringResource(R.string.peer_nodes_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            PeerRatingStore.clear(context)
                            reloadTick++
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.peer_reset))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        HintBubble {
                            Column {
                                Text(
                                    stringResource(R.string.peer_routing_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = HintBubbleTextColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.peer_routing_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                            }
                        }
                    }

                    if (peers.isEmpty()) {
                        item {
                            HintBubble {
                                Text(
                                    stringResource(R.string.peer_empty_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HintBubbleTextColor,
                                )
                            }
                        }
                    } else {
                        items(peers, key = { it.peerId }) { peer ->
                            PeerRatingCard(peer = peer, nowMs = now)
                        }
                    }
                }
                ApuScrollbar(state = scrollState)
            }
        }
    }
}

@Composable
private fun PeerRatingCard(peer: PeerStats, nowMs: Long) {
    val score = peer.score(nowMs)
    ApuSettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer.peerId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ApuBubbleTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ApuSettingsChip(
                        text = stringResource(R.string.peer_node_suffix, peer.tier(nowMs)),
                        highlighted = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        score.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = ApuBubbleTextColor,
                    )
                    Text(
                        stringResource(R.string.peer_of_100),
                        style = MaterialTheme.typography.labelSmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            ApuSettingsProgress(fraction = score / 100f)
            Spacer(Modifier.height(12.dp))

            Metric(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.peer_online),
                value = percent(peer.availability),
            )
            Metric(
                icon = Icons.Default.Bolt,
                label = stringResource(R.string.peer_speed),
                value = if (peer.bytesPerSecond > 0) speed(peer.bytesPerSecond) else stringResource(R.string.peer_no_data),
            )
            Metric(
                icon = Icons.Default.CheckCircle,
                label = stringResource(R.string.peer_delivery),
                value = if (peer.delivered + peer.failed > 0) {
                    percent(peer.reliability)
                } else {
                    stringResource(R.string.peer_no_data)
                },
            )
            Metric(
                icon = Icons.Default.Public,
                label = stringResource(R.string.peer_direct),
                value = if (peer.hasPublicAddress) stringResource(R.string.peer_yes) else stringResource(R.string.peer_only_via),
            )
            Metric(
                icon = Icons.Default.Storage,
                label = stringResource(R.string.peer_relay_space),
                value = if (peer.offeredBytes > 0) {
                    com.vladimir.messenger.data.swarm.StoragePolicy.format(peer.offeredBytes) +
                        " (+%.1f)".format(peer.storageBonus)
                } else {
                    stringResource(R.string.peer_not_reported)
                },
            )
        }
    }
}

@Composable
private fun Metric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = ApuBubbleMutedColor,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = ApuBubbleMutedColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = ApuBubbleTextColor,
        )
    }
}

private fun percent(value: Double): String = (value * 100).toInt().toString() + "%"

private fun speed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> (bytesPerSecond / (1024 * 1024)).toString() + " МБ/с"
    bytesPerSecond >= 1024 -> (bytesPerSecond / 1024).toString() + " КБ/с"
    else -> bytesPerSecond.toString() + " Б/с"
}
