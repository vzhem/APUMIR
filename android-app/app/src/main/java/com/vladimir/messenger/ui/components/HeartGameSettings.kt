package com.vladimir.messenger.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.heartgame.HeartGameStore
import com.vladimir.messenger.data.heartgame.HeartGameWire
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HeartGameCounter() {
    val context = LocalContext.current
    val revision by HeartGameStore.revision.collectAsStateWithLifecycle()
    val ready by com.vladimir.messenger.service.CoreStatus.ready.collectAsStateWithLifecycle()
    val id = remember(ready, revision) { HeartGameStore.ownId() } ?: return
    val count = remember(id, revision) { HeartGameStore.score(context, id) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF537F), modifier = Modifier.size(20.dp))
        Text(count.toString(), fontWeight = FontWeight.Bold, color = ApuBubbleAccentColor)
        Text(stringResource(R.string.heart_game_points), style = MaterialTheme.typography.labelMedium)
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
        ApuTextAction(label = stringResource(R.string.heart_game_leaders), onClick = { showBoard = true })
    }
    if (showBoard) HeartGameLeaderboard(id) { showBoard = false }
}

@Composable
private fun HeartGameLeaderboard(id: String?, onDismiss: () -> Unit) {
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
    ApuSettingsDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.heart_game_leaders)) },
        confirmButton = { ApuTextAction(stringResource(R.string.heart_game_close), onClick = onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.heart_game_board_hint), style = MaterialTheme.typography.bodySmall)
                if (loading) ApuPremiumSpinner()
                if (failed) Text(stringResource(R.string.heart_game_save_error))
                if (!loading && entries.none { it.keyId != ownKey }) Text(stringResource(R.string.heart_game_empty))
                // Dialog provides a single bounded scroll container; cap visible top to 100.
                entries.take(100).forEachIndexed { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (index < 3) Icon(Icons.Default.EmojiEvents, stringResource(R.string.heart_game_place, index + 1),
                            tint = listOf(Color(0xFFD9A329), Color(0xFF8B9CAD), Color(0xFFB77942))[index], modifier = Modifier.size(30.dp))
                        else Text("${index + 1}.")
                        Column(Modifier.weight(1f)) {
                            Text(entry.name.ifBlank { entry.keyId.take(12) + "…" }, fontWeight = FontWeight.SemiBold)
                            if (entry.keyId != ownKey) Text(entry.keyId.take(12) + "… · " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updated)),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF537F), modifier = Modifier.size(16.dp))
                        Text(entry.score.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                if (entries.indexOfFirst { it.keyId == ownKey } >= 100) {
                    Text(stringResource(R.string.heart_game_my_place, entries.indexOfFirst { it.keyId == ownKey } + 1,
                        entries.first { it.keyId == ownKey }.score))
                }
            }
        })
}
