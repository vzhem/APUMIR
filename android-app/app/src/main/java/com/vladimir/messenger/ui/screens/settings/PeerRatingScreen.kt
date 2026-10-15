package com.vladimir.messenger.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.peer.PeerRatingStore
import com.vladimir.messenger.data.peer.PeerStats
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.ui.components.ApuGold
import com.vladimir.messenger.ui.components.ApuGoldDeep
import com.vladimir.messenger.ui.components.ApuGoldLight
import com.vladimir.messenger.ui.components.ApuHeaderButton
import com.vladimir.messenger.ui.components.ApuPremiumChip
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.apuGoldBrush
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.swipeBack
import java.util.Locale
import kotlin.math.roundToInt

private val PeerConsoleInk = Color(0xFFF4F7FB)
private val PeerConsoleMuted = Color(0xFFAAB7C6)
private val PeerConsoleSurface = Color(0xFF0D1520)
private val PeerConsoleRaised = Color(0xFF192735)
private val PeerConsoleLine = Color.White.copy(alpha = 0.09f)
private val PeerHealthy = Color(0xFF70DDB1)
private val PeerCaution = Color(0xFFFFC76A)
private val PeerWeak = Color(0xFFFF8A8A)

private enum class PeerFilter { ALL, DIRECT, RECOMMENDED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerRatingScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val now = remember { System.currentTimeMillis() }
    var reloadTick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PeerFilter.ALL) }
    var showHowScoreWorks by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val peers = remember(reloadTick) { PeerRatingStore.ranked(context, now) }
    val visiblePeers = remember(peers, query, filter, now) {
        peers.filter { peer ->
            val matchesQuery = query.isBlank() || peer.peerId.contains(query.trim(), ignoreCase = true)
            val matchesFilter = when (filter) {
                PeerFilter.ALL -> true
                PeerFilter.DIRECT -> peer.hasPublicAddress
                PeerFilter.RECOMMENDED -> peer.score(now) >= 50
            }
            matchesQuery && matchesFilter
        }
    }
    val scrollState = rememberLazyListState()
    val filterScroll = rememberScrollState()

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
                        ApuHeaderButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            onClick = onBackClick,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    actions = {
                        ApuHeaderButton(
                            icon = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.peer_reset),
                            onClick = { showResetConfirmation = true },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        PeerOverviewCard(
                            peers = peers,
                            nowMs = now,
                            detailsExpanded = showHowScoreWorks,
                            onToggleDetails = { showHowScoreWorks = !showHowScoreWorks },
                        )
                    }

                    if (peers.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ApuSearchField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = stringResource(R.string.peer_search_hint),
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(filterScroll),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ApuPremiumChip(
                                        label = stringResource(R.string.peer_filter_all),
                                        selected = filter == PeerFilter.ALL,
                                        onClick = { filter = PeerFilter.ALL },
                                        modifier = Modifier.heightIn(min = 44.dp),
                                    )
                                    ApuPremiumChip(
                                        label = stringResource(R.string.peer_filter_direct),
                                        selected = filter == PeerFilter.DIRECT,
                                        onClick = { filter = PeerFilter.DIRECT },
                                        modifier = Modifier.heightIn(min = 44.dp),
                                        icon = Icons.Default.Public,
                                    )
                                    ApuPremiumChip(
                                        label = stringResource(R.string.peer_filter_recommended),
                                        selected = filter == PeerFilter.RECOMMENDED,
                                        onClick = { filter = PeerFilter.RECOMMENDED },
                                        modifier = Modifier.heightIn(min = 44.dp),
                                        icon = Icons.Default.CheckCircle,
                                    )
                                }
                            }
                        }
                    }

                    when {
                        peers.isEmpty() -> item { PeerEmptyCard() }
                        visiblePeers.isEmpty() -> item {
                            PeerMessageCard(text = stringResource(R.string.peer_no_results))
                        }
                        else -> items(visiblePeers, key = { it.peerId }) { peer ->
                            PeerRatingCard(peer = peer, nowMs = now) { peerId ->
                                clipboard.setText(AnnotatedString(peerId))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                ApuScrollbar(state = scrollState)
            }
        }
    }

    if (showResetConfirmation) {
        ApuPremiumDialog(
            title = stringResource(R.string.peer_reset_confirm_title),
            onDismiss = { showResetConfirmation = false },
            confirmLabel = stringResource(R.string.peer_reset_confirm),
            onConfirm = {
                PeerRatingStore.clear(context)
                reloadTick++
                query = ""
                filter = PeerFilter.ALL
                showResetConfirmation = false
            },
            confirmStyle = DiagnosticsActionStyle.DANGER,
            dismissLabel = stringResource(R.string.action_cancel),
        ) {
            Text(
                stringResource(R.string.peer_reset_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF344252),
            )
        }
    }
}

@Composable
private fun PeerOverviewCard(
    peers: List<PeerStats>,
    nowMs: Long,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
) {
    val averageScore = if (peers.isEmpty()) 0 else {
        peers.map { it.score(nowMs) }.average().roundToInt()
    }
    val directCount = peers.count { it.hasPublicAddress }

    PeerConsoleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApuPremiumIconTile(
                icon = Icons.Default.Public,
                size = 44.dp,
                corner = 14.dp,
                lift = 6.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.peer_network_kicker),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuGoldLight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.peer_routing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = PeerConsoleInk,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.peer_routing_summary),
            style = MaterialTheme.typography.bodySmall,
            color = PeerConsoleMuted,
        )
        Spacer(Modifier.height(14.dp))
        PeerGoldDivider()
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeerOverviewMetric(
                value = peers.size.toString(),
                label = stringResource(R.string.peer_summary_nodes),
                modifier = Modifier.weight(1f),
            )
            PeerOverviewMetric(
                value = directCount.toString(),
                label = stringResource(R.string.peer_summary_direct),
                modifier = Modifier.weight(1f),
                valueColor = PeerHealthy,
            )
            PeerOverviewMetric(
                value = averageScore.toString(),
                label = stringResource(R.string.peer_summary_average),
                modifier = Modifier.weight(1f),
                valueColor = ApuGoldLight,
            )
        }

        Spacer(Modifier.height(12.dp))
        ApuPremiumChip(
            label = stringResource(
                if (detailsExpanded) R.string.peer_routing_hide else R.string.peer_routing_details,
            ),
            selected = false,
            onClick = onToggleDetails,
            modifier = Modifier.heightIn(min = 44.dp),
            icon = Icons.Default.Info,
        )
        if (detailsExpanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.peer_routing_body),
                style = MaterialTheme.typography.bodySmall,
                color = PeerConsoleMuted,
            )
        }
    }
}

@Composable
private fun PeerOverviewMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PeerConsoleInk,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, PeerConsoleLine, shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PeerConsoleMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PeerRatingCard(
    peer: PeerStats,
    nowMs: Long,
    onCopy: (String) -> Unit,
) {
    val score = peer.score(nowMs)
    val scoreColor = peerTierColor(score)
    val tierLabel = stringResource(peerTierLabel(score))
    val observations = peer.sightings + peer.misses
    val availability = if (observations > 0) percent(peer.availability) else stringResource(R.string.peer_no_data)
    val transferSpeed = if (peer.bytesPerSecond > 0) speed(LocalContext.current, peer.bytesPerSecond)
    else stringResource(R.string.peer_no_data)
    val delivery = if (peer.delivered + peer.failed > 0) percent(peer.reliability)
    else stringResource(R.string.peer_no_data)
    val direct = if (peer.hasPublicAddress) stringResource(R.string.peer_yes)
    else stringResource(R.string.peer_only_via)
    val relaySpace = if (peer.offeredBytes > 0) {
        stringResource(
            R.string.peer_storage_value,
            StoragePolicy.format(peer.offeredBytes),
            String.format(Locale.getDefault(), "%.1f", peer.storageBonus),
        )
    } else {
        stringResource(R.string.peer_not_reported)
    }

    PeerConsoleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    compactPeerId(peer.peerId),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = PeerConsoleInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                PeerTierPill(
                    text = stringResource(R.string.peer_node_suffix, tierLabel),
                    color = scoreColor,
                )
            }
            Spacer(Modifier.width(7.dp))
            ApuHeaderButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.peer_copy_id),
                onClick = { onCopy(peer.peerId) },
            )
            Spacer(Modifier.width(8.dp))
            PeerScoreBadge(score = score, color = scoreColor)
        }

        Spacer(Modifier.height(14.dp))
        PeerScoreBar(score = score, color = scoreColor)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeerMetricTile(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.peer_availability),
                value = availability,
                valueColor = if (observations > 0) PeerHealthy else PeerConsoleMuted,
                modifier = Modifier.weight(1f),
            )
            PeerMetricTile(
                icon = Icons.Default.Bolt,
                label = stringResource(R.string.peer_speed),
                value = transferSpeed,
                valueColor = if (peer.bytesPerSecond > 0) ApuGoldLight else PeerConsoleMuted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeerMetricTile(
                icon = Icons.Default.CheckCircle,
                label = stringResource(R.string.peer_delivery),
                value = delivery,
                valueColor = when {
                    peer.delivered + peer.failed <= 0 -> PeerConsoleMuted
                    peer.reliability >= 0.8 -> PeerHealthy
                    else -> PeerCaution
                },
                modifier = Modifier.weight(1f),
            )
            PeerMetricTile(
                icon = Icons.Default.Public,
                label = stringResource(R.string.peer_direct),
                value = direct,
                valueColor = if (peer.hasPublicAddress) PeerHealthy else PeerCaution,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        PeerMetricTile(
            icon = Icons.Default.Storage,
            label = stringResource(R.string.peer_relay_space),
            value = relaySpace,
            valueColor = if (peer.offeredBytes > 0) ApuGoldLight else PeerConsoleMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PeerMetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PeerConsoleInk,
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PeerConsoleRaised.copy(alpha = 0.76f))
            .border(1.dp, PeerConsoleLine, shape)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = ApuGoldLight,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = PeerConsoleMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PeerScoreBadge(score: Int, color: Color) {
    val shape = CircleShape
    Column(
        modifier = Modifier
            .size(66.dp)
            .apuPremiumLift(7.dp, shape, color.copy(alpha = 0.42f))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(color.copy(alpha = 0.26f), PeerConsoleRaised, PeerConsoleSurface),
                ),
            )
            .border(1.dp, color.copy(alpha = 0.62f), shape)
            .apuPremiumGloss(shape, intensity = 0.48f, topFraction = 0.58f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            score.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
        Text(
            stringResource(R.string.peer_of_100),
            style = MaterialTheme.typography.labelSmall,
            color = PeerConsoleMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PeerScoreBar(score: Int, color: Color) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((score / 100f).coerceIn(0f, 1f))
                .height(8.dp)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(ApuGoldLight, color, ApuGoldDeep)))
                .apuPremiumGloss(shape, intensity = 0.8f, topFraction = 0.7f),
        )
    }
}

@Composable
private fun PeerTierPill(text: String, color: Color) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), shape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun PeerEmptyCard() {
    PeerConsoleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApuPremiumIconTile(
                icon = Icons.Default.Public,
                size = 42.dp,
                corner = 13.dp,
                background = Brush.linearGradient(
                    listOf(PeerConsoleRaised, PeerConsoleSurface),
                ),
                tint = ApuGoldLight,
                gloss = 0.5f,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.peer_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = PeerConsoleInk,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.peer_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = PeerConsoleMuted,
        )
    }
}

@Composable
private fun PeerMessageCard(text: String) {
    PeerConsoleCard {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = PeerConsoleMuted,
        )
    }
}

@Composable
private fun PeerConsoleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .apuPremiumLift(12.dp, shape, Color(0xFF050A10).copy(alpha = 0.55f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A2A39), PeerConsoleSurface, Color(0xFF0B121B)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        ApuGold.copy(alpha = 0.56f),
                        Color.White.copy(alpha = 0.10f),
                        ApuGoldDeep.copy(alpha = 0.25f),
                    ),
                ),
                shape,
            )
            .apuPremiumGloss(shape, intensity = 0.3f, topFraction = 0.34f)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PeerGoldDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        ApuGold.copy(alpha = 0.68f),
                        ApuGoldLight.copy(alpha = 0.38f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

private fun peerTierLabel(score: Int): Int = when {
    score >= 75 -> R.string.peer_tier_excellent
    score >= 50 -> R.string.peer_tier_good
    score >= 25 -> R.string.peer_tier_average
    else -> R.string.peer_tier_weak
}

private fun peerTierColor(score: Int): Color = when {
    score >= 75 -> PeerHealthy
    score >= 50 -> ApuGoldLight
    score >= 25 -> PeerCaution
    else -> PeerWeak
}

private fun compactPeerId(peerId: String): String = if (peerId.length > 18) {
    "${peerId.take(10)}…${peerId.takeLast(5)}"
} else {
    peerId
}

private fun percent(value: Double): String = "${(value * 100).toInt()}%"

private fun speed(context: android.content.Context, bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 ->
        (bytesPerSecond / (1024 * 1024)).toString() + " " + context.getString(R.string.speed_unit_mb)
    bytesPerSecond >= 1024 ->
        (bytesPerSecond / 1024).toString() + " " + context.getString(R.string.speed_unit_kb)
    else -> bytesPerSecond.toString() + " " + context.getString(R.string.speed_unit_b)
}
