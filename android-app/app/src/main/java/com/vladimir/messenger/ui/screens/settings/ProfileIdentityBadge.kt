package com.vladimir.messenger.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.util.ProfileCallsign

/**
 * Компактная плашка постоянного цифрового кода профиля (`482-915`) и раскрываемого
 * полного адреса узла (`pk_…`) на экране профиля.
 */
@Composable
internal fun ProfileIdentityBadge(fingerprint: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showFingerprint by remember { mutableStateOf(false) }
    val callsign = remember(fingerprint) { ProfileCallsign.of(fingerprint) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!callsign.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(ApuBubbleAccentColor.copy(alpha = 0.10f))
                    .clickable(role = Role.Button) {
                        clipboard.setText(AnnotatedString(callsign))
                        Toast.makeText(
                            context,
                            context.getString(R.string.dpb_callsign_copied, callsign),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dpb_callsign_value_tpl, callsign),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = ApuBubbleAccentColor,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = ApuBubbleAccentColor,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        ApuTextAction(
            label = stringResource(if (showFingerprint) R.string.profile_hide_id else R.string.profile_show_id),
            onClick = { showFingerprint = !showFingerprint },
        )
        if (showFingerprint) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ApuBubbleAccentColor.copy(alpha = 0.04f))
                    .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = ApuBubbleMutedColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
