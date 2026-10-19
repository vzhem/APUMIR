package com.vladimir.messenger.ui.screens.call

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.call.CallIncoming
import com.vladimir.messenger.data.call.CallStateMachine
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.apuGoldBrush
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
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
    val remoteVideo by viewModel.remoteVideo.collectAsStateWithLifecycle()
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
    fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(hasCameraPermission()) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted && state.video && !state.cameraOn) viewModel.toggleCamera()
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission()) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(state.video) {
        if (state.video && !cameraGranted) cameraLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { viewModel.ensureStarted() }

    // «Принять» с системного уведомления: тот же путь, что кнопка на экране.
    val autoAnswer = (context as? android.app.Activity)?.intent
        ?.getBooleanExtra(CallIncoming.EXTRA_ANSWER, false) == true
    LaunchedEffect(autoAnswer, state.phase) {
        if (!autoAnswer || state.phase != CallStateMachine.Phase.INCOMING) return@LaunchedEffect
        (context as? android.app.Activity)?.intent?.removeExtra(CallIncoming.EXTRA_ANSWER)
        if (hasMicPermission()) viewModel.accept()
        else {
            pendingAccept = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Конец: показываем причину русским текстом и уходим. Если за эти полторы
    // секунды начался НОВЫЙ звонок (быстрый перезвон), экран не сворачиваем.
    // Отдельная ловушка рассинхрона: машину уже снесли, а экран остался —
    // тогда уходим сразу, кнопкой «завершить» дорываться не придётся.
    val entryCallId = remember { state.callId }
    LaunchedEffect(state.phase, state.endText, state.callId) {
        if (entryCallId != null && state.callId == null) {
            onLeaveCall()
            return@LaunchedEffect
        }
        if (state.phase == CallStateMachine.Phase.ENDED) {
            delay(1500)
            if (viewModel.uiState.value.phase == CallStateMachine.Phase.ENDED) {
                onLeaveCall()
            }
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

    val view = LocalView.current
    DisposableEffect(state.video, state.phase) {
        val keep = state.video &&
            state.phase != CallStateMachine.Phase.ENDED &&
            state.phase != CallStateMachine.Phase.IDLE
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }

    // Демонстрация экрана: системное разрешение -> захват -> кадры тем же
    // видео-путём, что камера.
    val screenCapturer = remember {
        ScreenShareCapturer(context.applicationContext) { viewModel.sendVideoFrame(it) }
    }
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            if (screenCapturer.start(result.resultCode, data)) viewModel.setScreenSharing(true)
        }
    }
    fun stopScreenShare() {
        screenCapturer.stop()
        if (viewModel.uiState.value.screenSharing) viewModel.setScreenSharing(false)
    }
    DisposableEffect(state.screenSharing) {
        if (!state.screenSharing) screenCapturer.stop()
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { screenCapturer.stop() }
    }

    var frontCamera by remember { mutableStateOf(true) }
    val mediaLive = state.phase == CallStateMachine.Phase.CONNECTING ||
        state.phase == CallStateMachine.Phase.ACTIVE
    val inCall = state.phase != CallStateMachine.Phase.ENDED &&
        state.phase != CallStateMachine.Phase.IDLE
    val showLocalCamera = state.video && state.cameraOn && cameraGranted && inCall && !state.screenSharing
    val showCameraOffPip = state.video && inCall && !showLocalCamera && !state.screenSharing

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        if (state.video) {
            CallRemoteFrame(
                packed = remoteVideo,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(56.dp))

                // Имя собеседника на полосочке (как шапка чата). Владелец
                // 2026-10-07: «Теперь в таком стиле нужно переделать всё
                // приложение» — та же премиальная поверхность, что шапки,
                // диалоги и строки списков: подъём, золотая нить, блеск под
                // текстом.
                val nameShape = RoundedCornerShape(18.dp)
                Box(
                    modifier = Modifier
                        .apuPremiumLift(6.dp, nameShape)
                        .apuBubbleSurface(shape = nameShape)
                        .apuPremiumThread(shape = nameShape, inset = 18.dp)
                        .apuPremiumGloss(nameShape, intensity = 0.5f, topFraction = 0.6f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        state.peerName.ifBlank { shortId(state.peerId) },
                        fontWeight = FontWeight.SemiBold,
                        color = ApuBubbleTextColor,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    statusText(state, nowMs),
                    color = if (state.video) Color.White.copy(alpha = 0.9f) else ApuBubbleMutedColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.phase == CallStateMachine.Phase.ACTIVE && state.recovering) {
                    Text(
                        stringResource(R.string.call_reconnecting),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.phase == CallStateMachine.Phase.ACTIVE && state.slowTransport) {
                    Text(
                        when {
                            state.viaUdp -> stringResource(R.string.call_via_direct)
                            state.viaBroker -> stringResource(R.string.call_via_broker)
                            else -> stringResource(R.string.call_slow_link)
                        },
                        color = ApuBubbleMutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!state.video) {
                    Spacer(modifier = Modifier.height(48.dp))

                    // Аватар-круг с первой буквой: объёмное золотое кольцо и
                    // блеск по верхней кромке (тот же приём, что у знака VIP и у
                    // золотых плиток), буква остаётся на своей подложке и читается.
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .apuPremiumLift(10.dp, CircleShape, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(3.dp, apuGoldBrush(), CircleShape)
                            .apuPremiumGloss(CircleShape, intensity = 0.5f, topFraction = 0.55f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            state.peerName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
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
                    // Красная трубка ВСЕГДА уводит с экрана: если машина звонка
                    // уже мертва, менеджер повторно ничего не делает, а экран
                    // обязан закрыться сам (приёмка: «залип кружок, кнопка мертва»).
                    onRed = {
                        viewModel.hangupOrReject()
                        onLeaveCall()
                    },
                    onMute = viewModel::toggleMute,
                    onSpeaker = viewModel::toggleSpeaker,
                    onCamera = {
                        if (state.cameraOn) {
                            viewModel.toggleCamera()
                        } else if (!cameraGranted) {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            viewModel.toggleCamera()
                        }
                    },
                    onScreenShare = {
                        if (state.screenSharing) {
                            stopScreenShare()
                        } else {
                            com.vladimir.messenger.service.CallService.startForScreenShare(context)
                            val projectionManager = context.getSystemService(
                                android.media.projection.MediaProjectionManager::class.java,
                            )
                            if (projectionManager != null) {
                                shareLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(72.dp))
            }
        }
        if (showLocalCamera || showCameraOffPip || state.screenSharing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 140.dp)
                    .size(width = 108.dp, height = 144.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A)),
            ) {
                if (showLocalCamera) {
                    CallLocalPreview(
                        send = mediaLive,
                        front = frontCamera,
                        onJpeg = { viewModel.sendVideoFrame(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { frontCamera = !frontCamera },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.call_flip_camera),
                            tint = Color.White,
                        )
                    }
                } else {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = stringResource(R.string.call_camera_on),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                    )
                }
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
    onCamera: () -> Unit,
    onScreenShare: () -> Unit,
) {
    val gap = if (state.video) 16.dp else 32.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.phase) {
            CallStateMachine.Phase.INCOMING -> {
                RoundCallButton(
                    background = MaterialTheme.colorScheme.error,
                    description = stringResource(R.string.call_decline),
                    onClick = onRed,
                ) {
                    Icon(Icons.Default.CallEnd, stringResource(R.string.call_decline), tint = Color.White)
                }
                RoundCallButton(
                    background = Color(0xFF2E9E4F),
                    description = stringResource(R.string.call_accept),
                    onClick = onAccept,
                ) {
                    Icon(Icons.Default.Call, stringResource(R.string.call_accept), tint = Color.White)
                }
            }

            CallStateMachine.Phase.ACTIVE, CallStateMachine.Phase.CONNECTING -> {
                MuteCallButton(state, onMute)
                if (state.video) CameraCallButton(state, onCamera)
                RoundCallButton(
                    background = MaterialTheme.colorScheme.error,
                    description = stringResource(R.string.call_end),
                    onClick = onRed,
                    big = true,
                ) {
                    Icon(Icons.Default.CallEnd, stringResource(R.string.call_end), tint = Color.White)
                }
                SpeakerCallButton(state, onSpeaker)
            }

            else -> {
                // OFFERING / RINGING: гудки в трубку; динамик — отдельная кнопка.
                if (state.phase != CallStateMachine.Phase.ENDED) {
                    if (state.video) CameraCallButton(state, onCamera)
                    SpeakerCallButton(state, onSpeaker)
                    RoundCallButton(
                        background = MaterialTheme.colorScheme.error,
                        description = stringResource(R.string.call_cancel),
                        onClick = onRed,
                        big = true,
                    ) {
                        Icon(Icons.Default.CallEnd, stringResource(R.string.call_cancel), tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MuteCallButton(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    onMute: () -> Unit,
) {
    RoundCallButton(
        background = if (state.muted) MaterialTheme.colorScheme.error
        else Color(0xFFF5F7FA).copy(alpha = 0.95f),
        description = if (state.muted) stringResource(R.string.call_mic_on) else stringResource(R.string.call_mic_off),
        onClick = onMute,
    ) {
        Icon(
            if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
            if (state.muted) stringResource(R.string.call_mic_on) else stringResource(R.string.call_mic_off),
            tint = if (state.muted) Color.White else ApuBubbleMutedColor,
        )
    }
}

@Composable
private fun SpeakerCallButton(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    onSpeaker: () -> Unit,
) {
    RoundCallButton(
        background = if (state.speaker) MaterialTheme.colorScheme.primary
        else Color(0xFFF5F7FA).copy(alpha = 0.95f),
        description = if (state.speaker) stringResource(R.string.call_speaker_off) else stringResource(R.string.call_speaker),
        onClick = onSpeaker,
    ) {
        Icon(
            Icons.Default.VolumeUp,
            stringResource(R.string.call_speaker),
            tint = if (state.speaker) MaterialTheme.colorScheme.onPrimary
            else ApuBubbleMutedColor,
        )
    }
}

@Composable
private fun CameraCallButton(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    onCamera: () -> Unit,
) {
    RoundCallButton(
        background = if (!state.cameraOn) MaterialTheme.colorScheme.error
        else Color(0xFFF5F7FA).copy(alpha = 0.95f),
        description = if (state.cameraOn) stringResource(R.string.call_camera_off) else stringResource(R.string.call_camera_on),
        onClick = onCamera,
    ) {
        Icon(
            if (state.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
            if (state.cameraOn) stringResource(R.string.call_camera_off) else stringResource(R.string.call_camera_on),
            tint = if (state.cameraOn) ApuBubbleMutedColor else Color.White,
        )
    }
}

@Composable
private fun ScreenShareCallButton(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    onScreenShare: () -> Unit,
) {
    RoundCallButton(
        background = if (state.screenSharing) MaterialTheme.colorScheme.primary
        else Color(0xFFF5F7FA).copy(alpha = 0.95f),
        description = if (state.screenSharing) stringResource(R.string.call_stop_share)
        else stringResource(R.string.call_share_screen),
        onClick = onScreenShare,
    ) {
        Icon(
            if (state.screenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
            if (state.screenSharing) stringResource(R.string.call_stop_share)
            else stringResource(R.string.call_share_screen),
            tint = if (state.screenSharing) MaterialTheme.colorScheme.onPrimary
            else ApuBubbleMutedColor,
        )
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
    // Кнопки звонка — как золотые плитки: «стоят» над обоями (мягкая тень),
    // светлая кромка и блеск по верхней кромке. Значок рисуется ПОВЕРХ
    // блеска, поэтому трубка и микрофон остаются чёткими.
    Box(
        modifier = Modifier
            .size(size)
            .apuPremiumLift(8.dp, CircleShape)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .apuPremiumGloss(CircleShape, intensity = 0.55f, topFraction = 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(size)) {
            content()
        }
    }
}

@Composable
private fun statusText(
    state: com.vladimir.messenger.data.call.CallManager.CallUiState,
    nowMs: Long,
): String = when (state.phase) {
    CallStateMachine.Phase.IDLE -> ""
    CallStateMachine.Phase.OFFERING -> stringResource(R.string.cst_offering)
    CallStateMachine.Phase.RINGING -> stringResource(R.string.cst_ringing)
    CallStateMachine.Phase.INCOMING -> stringResource(R.string.cst_incoming)
    CallStateMachine.Phase.CONNECTING -> stringResource(R.string.cst_connecting)
    CallStateMachine.Phase.ACTIVE -> formatElapsed(state.connectedAtMs, nowMs)
    CallStateMachine.Phase.ENDED -> state.endText.ifBlank { stringResource(R.string.cst_ended) }
}

private fun formatElapsed(connectedAtMs: Long, nowMs: Long): String {
    val totalSec = ((nowMs - connectedAtMs).coerceAtLeast(0L)) / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun shortId(peerId: String): String =
    if (peerId.length > 12) "…" + peerId.takeLast(8) else peerId
