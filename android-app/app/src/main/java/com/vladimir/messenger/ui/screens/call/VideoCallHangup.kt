package com.vladimir.messenger.ui.screens.call

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.call.CallStateMachine

/** One prominent hangup target throughout an actual video call, including ringing. */
@StringRes
internal fun videoHangupLabel(phase: CallStateMachine.Phase): Int? = when (phase) {
    CallStateMachine.Phase.IDLE, CallStateMachine.Phase.ENDED -> null
    CallStateMachine.Phase.INCOMING -> R.string.call_decline
    CallStateMachine.Phase.OFFERING, CallStateMachine.Phase.RINGING -> R.string.call_cancel
    CallStateMachine.Phase.CONNECTING, CallStateMachine.Phase.ACTIVE -> R.string.call_end
}

@Composable
internal fun VideoCallHangup(phase: CallStateMachine.Phase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = videoHangupLabel(phase) ?: return
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape)
                .background(Color(0xFFD32F2F))
                .border(1.dp, Color.White, CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(label), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = stringResource(label),
                tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(stringResource(label), color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
