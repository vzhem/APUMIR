package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R

/** The name owns the remaining width. Height grows with wrapped text, not a fixed app-bar slot. */
@Composable
internal fun ConversationHeader(
    name: String,
    status: String,
    active: Boolean,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    actions: List<ApuAction>,
    modifier: Modifier = Modifier,
    avatar: (@Composable () -> Unit)? = null,
    vip: Boolean = false,
) {
    var expanded by remember(name) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().then(apuGlassBarModifier())
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ApuHeaderIconBubble(onClick = onBack, contentDescription = stringResource(R.string.action_back),
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
        ApuHeaderBubble(Modifier.weight(1f), onClick = onProfile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) { avatar(); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    // No ellipsis or line limit: long names wrap, including at large system font sizes.
                    Text(name, modifier = Modifier.fillMaxWidth().testTag("conversation-header-name"),
                        style = apuVipNameStyle(MaterialTheme.typography.titleMedium, vip), fontWeight = FontWeight.SemiBold,
                        color = if (vip) Color(0xFF8A5F00) else ApuBubbleTextColor, softWrap = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = if (active) ApuBubbleAccentColor else ApuBubbleMutedColor)
                        if (vip) { Spacer(Modifier.width(4.dp)); ApuVipBadge(compact = true) }
                    }
                }
            }
        }
        Box {
            ApuHeaderIconBubble(onClick = { expanded = !expanded }, contentDescription = stringResource(R.string.chat_more),
                modifier = Modifier.size(48.dp).testTag("conversation-header-more")) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            ApuActionsMenu(expanded = expanded, onDismiss = { expanded = false }, actions = actions)
        }
    }
}
