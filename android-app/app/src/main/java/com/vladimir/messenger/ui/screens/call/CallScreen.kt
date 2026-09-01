package com.vladimir.messenger.ui.screens.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.call.CallStateMachine
import com.vladimir.messenger.ui.components.ChatWallpaper
import kotlinx.coroutines.delay

/**
 * Экран звонка (CALLS_BOOTSTRAP.md, 8.6): один на обе роли.
 * ЕДИНЫЙ СТИЛЬ APU: подложка ChatWallpaper на весь экран, прозрачный Scaffold,
 * имя на белой полосочке с золотой рамкой, скруглённые кнопки, всё по-русски.
 */
@Composable
fun CallScreen(
    onLeaveCall: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Микрофон: запрос со входом на экран; «Принять» без разрешения — сначала запрос.
    var pendingAccept by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingAccept) viewModel.accept()
        } else {
            viewModel.micDenied()
        }
        pendingAccept = false
    }
    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (!hasMicPermission()) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { viewModel.ensureStarted() }

    // Конец: показываем причину русским текстом и уходим.
    LaunchedEffect(state.phase, state.endText) {
        if (state.phase == CallStateMachine.Phase.ENDED) {
            delay(1500)
            onLeaveCall()
        }
    }

    // Тикающий таймер разговора.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase, state.connectedAtMs) {
        if (state.phase == CallStateMachine.Phase.ACTIVE) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(56.dp))

                // Имя собеседника на белой полосочке с золотой рамкой (как шапка чата).
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        state.peerName.ifBlank { shortId(state.peerId) },
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E2430),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    statusText(state, nowMs),
                    color = Color(0xFF5A6472),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.phase == CallStateMachine.Phase.ACTIVE && state.recovering) {
                    Text(
                        "Восстановление соединения…",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.phase == CallStateMachine.Phase.ACTIVE && state.slowTransport) {
                    Text(
                        "Медленный канал — собеседник не в одной Wi-Fi сети",
                        color = Color(0xFF5A6472),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Аватар-круг с первой буквой (золотой ободок фирменной темы).
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.peerName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                CallButtons(
                    state = state,
                    onAccept = {
                        if (hasMicPermission()) {
                            viewModel.accept()
                        } else {
                            pendingAccept = true
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRed = viewModel::hangupOrReject,
                    onMute = viewModel::toggleMute,
                    onSpeaker = viewModel::toggleSpeaker,
                )

                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun CallButtons(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    onAccept: () -> Unit,
    onRed: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.phase) {
            CallStateMachine.Phase.INCOMING -> {
                RoundCallButton(
                    background = MaterialTheme.colorScheme.error,
                    description = "Отклонить",
                    onClick = onRed,
                ) {
                    Icon(Icons.Default.CallEnd, "Отклонить", tint = Color.White)
                }
                RoundCallButton(
                    background = Color(0xFF2E9E4F),
                    description = "Принять",
                    onClick = onAccept,
                ) {
                    Icon(Icons.Default.Call, "Принять", tint = Color.White)
                }
            }

            CallStateMachine.Phase.ACTIVE -> {
                RoundCallButton(
                    background = if (state.muted) MaterialTheme.colorScheme.error
                    else Color(0xFFF5F7FA).copy(alpha = 0.95f),
                    description = if (state.muted) "Включить микрофон" else "Выключить микрофон",
                    onClick = onMute,
                ) {
                    Icon(
                        if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        if (state.muted) "Включить микрофон" else "Выключить микрофон",
                        tint = if (state.muted) Color.White else Color(0xFF5A6472),
                    )
                }
                RoundCallButton(
                    background = MaterialTheme.colorScheme.error,
                    description = "Завершить",
                    onClick = onRed,
                    big = true,
                ) {
                    Icon(Icons.Default.CallEnd, "Завершить", tint = Color.White)
                }
                RoundCallButton(
                    background = if (state.speaker) MaterialTheme.colorScheme.primary
                    else Color(0xFFF5F7FA).copy(alpha = 0.95f),
                    description = if (state.speaker) "Громкая связь выкл" else "Громкая связь",
                    onClick = onSpeaker,
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        "Громкая связь",
                        tint = if (state.speaker) MaterialTheme.colorScheme.onPrimary
                        else Color(0xFF5A6472),
                    )
                }
            }

            else -> {
                // OFFERING / RINGING / CONNECTING / ENDED: только красная трубка.
                if (state.phase != CallStateMachine.Phase.ENDED) {
                    RoundCallButton(
                        background = MaterialTheme.colorScheme.error,
                        description = "Отменить",
                        onClick = onRed,
                        big = true,
                    ) {
                        Icon(Icons.Default.CallEnd, "Отменить", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundCallButton(
    background: Color,
    description: String,
    onClick: () -> Unit,
    big: Boolean = false,
    content: @Composable () -> Unit,
) {
    val size = if (big) 72.dp else 64.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(size)) {
            content()
        }
    }
}

private fun statusText(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    nowMs: Long,
): String = when (state.phase) {
    CallStateMachine.Phase.IDLE -> ""
    CallStateMachine.Phase.OFFERING -> "Вызов…"
    CallStateMachine.Phase.RINGING -> "Гудки…"
    CallStateMachine.Phase.INCOMING -> "Входящий звонок"
    CallStateMachine.Phase.CONNECTING -> "Соединение…"
    CallStateMachine.Phase.ACTIVE -> formatElapsed(state.connectedAtMs, nowMs)
    CallStateMachine.Phase.ENDED -> state.endText.ifBlank { "Завершён" }
}

private fun formatElapsed(connectedAtMs: Long, nowMs: Long): String {
    val totalSec = ((nowMs - connectedAtMs).coerceAtLeast(0L)) / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun shortId(peerId: String): String =
    if (peerId.length > 12) "…" + peerId.takeLast(8) else peerId
