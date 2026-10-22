package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.voice.VoiceEffect

/** Выбор популярного эффекта изменения голоса для голосовых сообщений и звонков. */
@Composable
fun VoiceEffectPickerDialog(
    selected: VoiceEffect,
    onSelected: (VoiceEffect) -> Unit,
    onDismiss: () -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_effect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.voice_effect_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleMutedColor,
                )
                VoiceEffect.entries.chunked(2).forEach { rowEffects ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowEffects.forEach { effect ->
                            ApuPremiumChip(
                                label = effect.symbol + " " + stringResource(
                                    if (effect == VoiceEffect.ORIGINAL) {
                                        R.string.voice_effect_original_default
                                    } else {
                                        effect.labelRes
                                    },
                                ),
                                selected = effect == selected,
                                onClick = {
                                    onSelected(effect)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowEffects.size == 1) {
                            Row(modifier = Modifier.weight(1f)) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.voice_effect_done),
                onClick = onDismiss,
            )
        },
    )
}
