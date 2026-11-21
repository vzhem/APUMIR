package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * Диалог подтверждения версии при ручной публикации APK-файла в раздачу соседям.
 * Пока манифест читается (`reading = true`), кнопка «Поделиться» заблокирована
 * и выводится подсказка, что версия вот-вот заполнится.
 */
@Composable
internal fun ApkVersionDialog(
    title: String,
    initialVersion: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    fileName: String? = null,
    sizeText: String? = null,
    reading: Boolean = false,
) {
    var version by remember(initialVersion) { mutableStateOf(initialVersion) }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (fileName != null) {
                    Text(
                        fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (sizeText != null) {
                    Text(
                        stringResource(R.string.size_label_tpl, sizeText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reading) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_reading_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.settings_version_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ApuBubbleField(
                    value = version,
                    onValueChange = { version = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_version_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.settings_share_btn),
                onClick = { onConfirm(version) },
                enabled = !reading && version.isNotBlank(),
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}
