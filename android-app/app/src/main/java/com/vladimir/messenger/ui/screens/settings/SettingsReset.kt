package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.settings.SettingsResetCategory
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * Сброс разделов настроек: карточка и диалог выбора категорий.
 *
 * Вынесено из `SettingsScreen.kt` (потолок god-файлов), когда в настройках
 * появился раздел «Дополнительные функции»: поведение прежнее, экран просто
 * перестал держать этот код в себе.
 */

@Composable
internal fun SettingsResetCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    SettingsCard {
        SettingsItem(
            icon = Icons.Default.RestartAlt,
            title = title,
            subtitle = subtitle,
            onClick = onClick,
        )
    }
}

@Composable
internal fun SettingsResetDialog(
    initialCategories: Set<SettingsResetCategory>,
    selectable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<SettingsResetCategory>) -> Unit,
) {
    var selected by remember(initialCategories) { mutableStateOf(initialCategories) }
    val canConfirm = selected.isNotEmpty()
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        icon = { ApuPremiumIconTile(icon = Icons.Default.RestartAlt, size = 34.dp, corner = 10.dp) },
        title = { Text(stringResource(R.string.settings_reset_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (selectable) R.string.settings_reset_dialog_body else R.string.settings_reset_section_body))
                SettingsResetCategory.entries.forEach { category ->
                    val checked = category in selected
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (selectable) Modifier.clickable {
                                        selected = if (checked) selected - category else selected + category
                                    } else Modifier
                                )
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(categoryLabelRes(category)),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectable) {
                                ApuPremiumSwitch(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        selected = if (value) selected + category else selected - category
                                    },
                                )
                            } else if (checked) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = ApuBubbleAccentColor)
                            }
                        }
                        Text(
                            stringResource(categoryDetailsRes(category)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_reset_protected_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.settings_reset_confirm),
                onClick = { if (canConfirm) onConfirm(selected) },
                enabled = canConfirm,
                danger = true,
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}


internal fun categoryLabelRes(category: SettingsResetCategory): Int = when (category) {
    SettingsResetCategory.CHATS -> R.string.settings_reset_category_chats
    SettingsResetCategory.NOTIFICATIONS -> R.string.settings_reset_category_notifications
    SettingsResetCategory.APPEARANCE -> R.string.settings_reset_category_appearance
    SettingsResetCategory.SECURITY -> R.string.settings_reset_category_security
    SettingsResetCategory.NETWORK -> R.string.settings_reset_category_network
    SettingsResetCategory.FILES -> R.string.settings_reset_category_files
    SettingsResetCategory.HEARTS -> R.string.settings_reset_category_hearts
}

internal fun categoryDetailsRes(category: SettingsResetCategory): Int = when (category) {
    SettingsResetCategory.CHATS -> R.string.settings_reset_details_chats
    SettingsResetCategory.NOTIFICATIONS -> R.string.settings_reset_details_notifications
    SettingsResetCategory.APPEARANCE -> R.string.settings_reset_details_appearance
    SettingsResetCategory.SECURITY -> R.string.settings_reset_details_security
    SettingsResetCategory.NETWORK -> R.string.settings_reset_details_network
    SettingsResetCategory.FILES -> R.string.settings_reset_details_files
    SettingsResetCategory.HEARTS -> R.string.settings_reset_details_hearts
}

