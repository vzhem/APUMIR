package com.vladimir.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.security.IdentityHealth

/**
 * «Этот телефон молчит» — плашка вверху списка чатов.
 *
 * Зачем. Пока ключ этого телефона не читается, приложение не отправляет личное
 * открытым текстом: сообщения, истории и зеркало профиля просто ждут. Раньше об
 * этом знали только «Логи», а человек видел тишину и не понимал, на чьей стороне
 * потерялось. Владелец 2026-10-15: «истории не доходят и непонятно почему».
 *
 * Плашку показываем только на настоящую поломку: [IdentityHealth.Reason.READY]
 * (всё в порядке) и [IdentityHealth.Reason.NO_IDENTITY] (первый запуск, личность
 * ещё создаётся) — молчание по делу. Крестик прячет её до перезапуска: человек
 * мог уже всё починить, а навязчивая плашка хуже тишины.
 */
@Composable
fun IdentityMuteRow(
    reason: IdentityHealth.Reason,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Появление — один раз, как у пузыря дня рождения: MutableTransitionState
    // даёт «всплытие» на первом кадре, чего обычный `visible = true` не делает.
    // Крестик гасит её, переводя цель в false: плашка убирается до перезапуска.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val unreadable = reason == IdentityHealth.Reason.UNREADABLE
    val title = stringResource(
        if (unreadable) R.string.identity_mute_title_unreadable
        else R.string.identity_mute_title_timeout,
    )
    val body = stringResource(
        if (unreadable) R.string.identity_mute_body_unreadable
        else R.string.identity_mute_body_timeout,
    )
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
    ) {
        ApuBubbleCard(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            premium = 7.dp,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClick = onRestore)
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(ApuGold.copy(alpha = 0.16f))
                                .border(1.dp, ApuGold.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = ApuGold,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(6.dp))
                    ApuTextAction(
                        label = stringResource(R.string.identity_mute_action),
                        onClick = onRestore,
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button) { shown.targetState = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.identity_mute_hide),
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
