package com.vladimir.messenger.ui.components

// =============================================================================
// NETWORKSTATUSBAR.KT — Полоска статуса соединения
// =============================================================================
// Показывается вверху экрана когда нет соединения или оно деградировано.
// Анимированное появление/исчезновение.
//
// Аналог Telegram: жёлтая полоска "Connecting..." / "Waiting for network"
// =============================================================================

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.repository.NetworkStatus
import com.vladimir.messenger.ui.theme.StatusConnecting
import com.vladimir.messenger.ui.theme.StatusDegraded
import com.vladimir.messenger.ui.theme.StatusOffline

@Composable
fun NetworkStatusBar(
    status: NetworkStatus,
    modifier: Modifier = Modifier,
) {
    // Показываем полоску только при проблемах
    val isVisible = status != NetworkStatus.Connected

    AnimatedVisibility(
        visible = isVisible,
        enter   = expandVertically(),
        exit    = shrinkVertically(),
        modifier = modifier,
    ) {
        val (backgroundColor, icon, text) = when (status) {
            NetworkStatus.Disconnected -> Triple(
                StatusOffline,
                Icons.Default.SignalWifiOff,
                "Нет соединения"
            )
            NetworkStatus.Connecting -> Triple(
                StatusConnecting,
                Icons.Default.CloudOff,
                "Подключение..."
            )
            NetworkStatus.Degraded -> Triple(
                StatusDegraded,
                Icons.Default.SyncProblem,
                "Через ретранслятор"
            )
            NetworkStatus.Connected -> Triple(
                Color.Transparent,
                Icons.Default.CloudOff,
                ""
            )
        }

        // Полоска стоит над обоями APU: сплошная заливка выглядела чужой
        // заплаткой поверх картины. Поэтому цвет статуса кладём полупрозрачным
        // слоем ПОВЕРХ тех же обоев - подложка остаётся видна, а смысл цвета
        // (жёлтый - подключаемся, красный - нет связи) сохраняется.
        Box(modifier = Modifier.fillMaxWidth()) {
            // matchParentSize, а не fillMaxSize: иначе подложка растянула бы
            // саму полоску на весь экран - её высоту задаёт строка с текстом.
            Box(modifier = Modifier.matchParentSize()) { ChatWallpaper() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor.copy(alpha = 0.72f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = Color.White,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text  = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )

            // Анимированные точки для "Подключение..."
            if (status == NetworkStatus.Connecting) {
                AnimatedDots()
            }
            }
        }
    }
}

// Анимированные три точки "..."
@Composable
private fun AnimatedDots() {
    var dotCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            dotCount = (dotCount + 1) % 4
        }
    }

    Text(
        text  = ".".repeat(dotCount),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.width(20.dp),
    )
}