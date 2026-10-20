package com.vladimir.messenger.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.heartgame.HeartGameStore
import com.vladimir.messenger.ui.theme.LocalAppDarkTheme
import com.vladimir.messenger.data.heartgame.HeartGameWire
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Цвета счётчика сердечек поверх обоев: внутри светлых пузырей текст тёмный,
 * а на главной и в списке настроек счётчик стоит прямо на обоях — в тёмной
 * теме тёмный текст там пропадал. Поэтому цвет выбирается по теме.
 */
@Composable
private fun heartWallpaperTextColor(): Color =
    if (LocalAppDarkTheme.current) Color(0xFFE2E9F4) else ApuBubbleTextColor

@Composable
private fun heartWallpaperMutedColor(): Color =
    if (LocalAppDarkTheme.current) Color(0xFFB9C6DA) else ApuBubbleMutedColor

@Composable
private fun heartWallpaperAccentColor(): Color =
    if (LocalAppDarkTheme.current) Color(0xFFF0C96A) else ApuBubbleAccentColor

@Composable
fun HeartGameCounter(onlyWhenEnabled: Boolean = false) {
    val context = LocalContext.current
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    val ready by com.vladimir.messenger.service.CoreStatus.ready.collectAsStateWithLifecycle()
    val id = remember(ready, revision) { HeartGameStore.ownId() }
    val enabled = remember(id, revision) { id?.let { HeartGameStore.enabled(context, it) } ?: false }
    var place by remember(id) { mutableStateOf<Int?>(null) }
    val you = stringResource(R.string.heart_game_you)
    LaunchedEffect(id, revision, you) {
        place = null
        val account = id ?: return@LaunchedEffect
        try {
            val ownKey = HeartGameStore.ownKeyId(context) ?: return@LaunchedEffect
            val own = HeartGameWire.Entry(account, you, HeartGameStore.score(context, account),
                System.currentTimeMillis(), "", ownKey)
            val ranked = HeartGameWire.ranked(HeartGameStore.board(context, account)
                .filter { it.keyId != ownKey } + own)
            place = ranked.indexOfFirst { it.keyId == ownKey }.takeIf { it >= 0 }?.plus(1)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { place = null }
    }
    val account = id ?: return
    if (onlyWhenEnabled && !enabled) return
    val count = remember(account, revision) { HeartGameStore.score(context, account) }
    Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF537F), modifier = Modifier.size(20.dp))
            Text(count.toString(), fontWeight = FontWeight.Bold, color = heartWallpaperAccentColor())
            Text(stringResource(R.string.heart_game_points), style = MaterialTheme.typography.labelMedium,
                color = heartWallpaperTextColor())
        }
        Text(stringResource(R.string.heart_game_my_place_short, place?.toString() ?: "—"),
            style = MaterialTheme.typography.labelSmall, color = heartWallpaperMutedColor())
    }
}

@Composable
fun HeartGameHeaderStatus(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    val ready by com.vladimir.messenger.service.CoreStatus.ready.collectAsStateWithLifecycle()
    val id = remember(ready, revision) { if (ready) HeartGameStore.ownId() else null } ?: return
    val enabled = remember(id, revision) { HeartGameStore.enabled(context, id) }
    val score = remember(id, revision) { HeartGameStore.score(context, id) }
    var place by remember(id) { mutableStateOf<Int?>(null) }
    val you = stringResource(R.string.heart_game_you)
    LaunchedEffect(id, revision, you, enabled) {
        place = null
        if (!enabled) return@LaunchedEffect
        try {
            val ownKey = HeartGameStore.ownKeyId(context) ?: return@LaunchedEffect
            val own = HeartGameWire.Entry(id, you, HeartGameStore.score(context, id),
                System.currentTimeMillis(), "", ownKey)
            val ranked = HeartGameWire.ranked(HeartGameStore.board(context, id)
                .filter { it.keyId != ownKey } + own)
            place = ranked.indexOfFirst { it.keyId == ownKey }.takeIf { it >= 0 }?.plus(1)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { place = null }
    }
    if (!enabled) return
    Row(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.Favorite, null, tint = Color(0xFFE0245E), modifier = Modifier.size(13.dp))
        Text(score.toString(), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = heartWallpaperTextColor())
        Text("·", style = MaterialTheme.typography.labelSmall, color = heartWallpaperMutedColor())
        Text(stringResource(R.string.heart_game_header_place, place?.toString() ?: "—"),
            style = MaterialTheme.typography.labelSmall, color = heartWallpaperMutedColor(), maxLines = 1)
    }
}

@Composable
fun HeartGameSettings() {
    val context = LocalContext.current
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    val ready by com.vladimir.messenger.service.CoreStatus.ready.collectAsStateWithLifecycle()
    val id = remember(ready, revision) { HeartGameStore.ownId() }
    val enabled = remember(id, revision) { id?.let { HeartGameStore.enabled(context, it) } ?: false }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showBoard by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshResult by remember {
        mutableStateOf<com.vladimir.messenger.data.heartgame.HeartGameNetwork.RefreshResult?>(null)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.heart_game_title), Modifier.weight(1f), fontWeight = FontWeight.Bold)
            ApuPremiumSwitch(enabled, onCheckedChange = { value ->
                val account = id
                if (!busy && account != null) {
                    busy = true
                    scope.launch {
                        try { if (!HeartGameStore.setEnabled(context, account, value))
                            Toast.makeText(context, R.string.heart_game_save_error, Toast.LENGTH_SHORT).show()
                        } finally { busy = false }
                    }
                }
            }, enabled = !busy && id != null)
        }
        Text(stringResource(R.string.heart_game_hint), style = MaterialTheme.typography.bodySmall)
        HeartGameCounter()
        ApuTextAction(
            label = stringResource(if (refreshing) R.string.heart_game_refresh_working else R.string.heart_game_refresh),
            enabled = !refreshing && id != null,
            onClick = {
                refreshing = true
                scope.launch {
                    try {
                        val network = dagger.hilt.android.EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            com.vladimir.messenger.MainActivityEntryPoint::class.java,
                        ).heartGameNetwork()
                        refreshResult = network.refreshWithOnlinePeers()
                    } catch (_: Exception) {
                        refreshResult = com.vladimir.messenger.data.heartgame.HeartGameNetwork
                            .RefreshResult(false, 0, 0, 0)
                    } finally { refreshing = false }
                }
            })
        Text(stringResource(R.string.heart_game_refresh_hint), style = MaterialTheme.typography.bodySmall)
        refreshResult?.let { result ->
            Text(
                when {
                    !result.ready -> stringResource(R.string.heart_game_refresh_not_ready)
                    result.targets == 0 -> stringResource(R.string.heart_game_refresh_none_online)
                    else -> stringResource(R.string.heart_game_refresh_done,
                        result.online, result.targets, result.sentScore, result.sentRequests)
                },
                style = MaterialTheme.typography.labelSmall)
        }
        ApuTextAction(label = stringResource(R.string.heart_game_leaders), onClick = { showBoard = true })
    }
    if (showBoard) HeartGameLeaderboardDialog(id) { showBoard = false }
}

@Composable
fun HeartGameLeaderboardDialog(id: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    var entries by remember(id) { mutableStateOf<List<HeartGameWire.Entry>>(emptyList()) }
    var ownKey by remember(id) { mutableStateOf("local:$id") }
    var loading by remember(id) { mutableStateOf(true) }
    var failed by remember(id) { mutableStateOf(false) }
    val you = stringResource(R.string.heart_game_you)
    LaunchedEffect(id, revision, you) {
        if (id == null) { loading = false; return@LaunchedEffect }
        try {
            ownKey = HeartGameStore.ownKeyId(context) ?: "local:$id"
            val own = HeartGameWire.Entry(id, you, HeartGameStore.score(context, id), System.currentTimeMillis(), "", ownKey)
            entries = HeartGameWire.ranked(HeartGameStore.board(context, id).filter { it.keyId != ownKey } + own)
            failed = false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val ownIndex = entries.indexOfFirst { it.keyId == ownKey }
    val ownPlace = ownIndex + 1
    val rows: List<Pair<Int, HeartGameWire.Entry>> =
        if (ownIndex < 100) entries.take(100).mapIndexed { i, e -> (i + 1) to e }
        else entries.take(99).mapIndexed { i, e -> (i + 1) to e } + (100 to entries[ownIndex])
    val listHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).coerceIn(200f, 420f).dp
    ApuSettingsDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.heart_game_leaders)) },
        confirmButton = { ApuTextAction(stringResource(R.string.heart_game_close), onClick = onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.heart_game_board_hint), style = MaterialTheme.typography.bodySmall)
                if (loading) ApuPremiumSpinner()
                if (failed) Text(stringResource(R.string.heart_game_save_error))
                if (!loading && entries.none { it.keyId != ownKey }) Text(stringResource(R.string.heart_game_empty))
                // Таблица на 100 мест. Если вы ниже 100-го, вы занимаете 100-ю строку
                // с настоящим местом в подписи. Лента прокручивается внутри окна.
                if (rows.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().height(listHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rows, key = { it.first }) { (place, entry) ->
                            val isMe = entry.keyId == ownKey
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("$place", modifier = Modifier.width(36.dp), fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End)
                                if (place <= 3) Icon(Icons.Default.EmojiEvents, stringResource(R.string.heart_game_place, place),
                                    tint = listOf(Color(0xFFD9A329), Color(0xFF8B9CAD), Color(0xFFB77942))[place - 1],
                                    modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name.ifBlank { entry.keyId.take(12) + "…" }, fontWeight = FontWeight.SemiBold)
                                    if (isMe) {
                                        Text(stringResource(R.string.heart_game_you_place, ownPlace),
                                            style = MaterialTheme.typography.labelSmall, color = ApuBubbleAccentColor)
                                    } else {
                                        Text(entry.keyId.take(12) + "… · " +
                                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updated)),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF537F), modifier = Modifier.size(16.dp))
                                Text(entry.score.toString(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        })
}
