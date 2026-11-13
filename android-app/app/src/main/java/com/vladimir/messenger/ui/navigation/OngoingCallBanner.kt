package com.vladimir.messenger.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.call.CallStateMachine
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

internal fun canReturnToCall(phase: CallStateMachine.Phase, onCallScreen: Boolean): Boolean =
    !onCallScreen && phase != CallStateMachine.Phase.IDLE && phase != CallStateMachine.Phase.ENDED

/** Keep the return control above, rather than on top of, the current screen. */
@Composable
internal fun CallAwareNavContainer(
    phase: CallStateMachine.Phase,
    onCallScreen: Boolean,
    onReturn: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val showBanner = canReturnToCall(phase, onCallScreen)
    Box(Modifier.fillMaxSize()) {
        ChatWallpaper()
        Column(Modifier.fillMaxSize()) {
            if (showBanner) OngoingCallBanner(onReturn, Modifier.statusBarsPadding())
            val body = Modifier.weight(1f).fillMaxWidth()
            // The banner already occupies the status bar inset. The screen
            // underneath must not add it for a second time.
            content(if (showBanner) body.consumeWindowInsets(WindowInsets.statusBars) else body)
        }
    }
}

/** A hidden call remains controllable without a phone notification shade. */
@Composable
internal fun OngoingCallBanner(onReturn: () -> Unit, modifier: Modifier = Modifier) {
    ApuPremiumContentButton(
        onClick = onReturn,
        style = DiagnosticsActionStyle.PRIMARY,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row {
            Icon(Icons.Default.Call, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.call_return_to_call), fontWeight = FontWeight.SemiBold)
        }
    }
}
