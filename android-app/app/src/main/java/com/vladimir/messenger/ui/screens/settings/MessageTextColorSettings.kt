package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.textcolor.TextColorPalette
import com.vladimir.messenger.data.textcolor.TextColorSettingsStore
import com.vladimir.messenger.ui.components.ApuPremiumChoiceRow
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuPremiumNoticeDialog
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuSettingsCard

/** Settings for outgoing message ink, the profile name ink, and the reader filter. */
@Composable
fun MessageTextColorSettingsCard(onNameColorChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val revision by TextColorSettingsStore.revision.collectAsStateWithLifecycle()
    var selectedScope by remember { mutableStateOf<String?>(null) }
    var showResetDone by remember { mutableStateOf(false) }
    var showDefaultDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { TextColorSettingsStore.initialize(context) }

    val scopes = listOf(
        ColorScope(
            key = "direct",
            title = stringResource(R.string.text_color_personal),
            icon = Icons.Default.Person,
            scope = TextColorSettingsStore.categoryScope("direct"),
        ),
        ColorScope(
            key = "group",
            title = stringResource(R.string.text_color_group),
            icon = Icons.Default.Groups,
            scope = TextColorSettingsStore.categoryScope("group"),
        ),
        ColorScope(
            key = "channel",
            title = stringResource(R.string.text_color_channel),
            icon = Icons.Default.Campaign,
            scope = TextColorSettingsStore.categoryScope("channel"),
        ),
        ColorScope(
            key = "topic",
            title = stringResource(R.string.text_color_topic),
            icon = Icons.Default.Topic,
            scope = TextColorSettingsStore.categoryScope("topic"),
        ),
    )

    ApuSettingsCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            ApuSettingsItem(
                icon = Icons.Default.FormatColorText,
                title = stringResource(R.string.text_color_section_title),
                subtitle = stringResource(R.string.text_color_section_hint),
            )
            scopes.forEachIndexed { index, item ->
                if (index > 0) ApuSettingsDivider()
                ApuSettingsItem(
                    icon = item.icon,
                    title = item.title,
                    subtitle = colorLabel(context, TextColorSettingsStore.textColor(context, item.scope)),
                    onClick = { selectedScope = item.scope },
                )
            }
            ApuSettingsDivider()
            ApuSettingsItem(
                icon = Icons.Default.Person,
                title = stringResource(R.string.text_color_name_title),
                subtitle = stringResource(
                    R.string.text_color_selected,
                    colorLabel(context, TextColorSettingsStore.ownNameColor(context)),
                ),
                onClick = { selectedScope = NAME_SCOPE },
            )
            ApuSettingsDivider()
            var filterEnabled by remember(revision) {
                mutableStateOf(TextColorSettingsStore.readOnlySelectedColor(context))
            }
            val selectedRead = TextColorSettingsStore.selectedReadColor(context)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.text_color_filter_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.text_color_filter_hint,
                            colorLabel(context, selectedRead),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ApuPremiumSwitch(
                    checked = filterEnabled,
                    onCheckedChange = {
                        filterEnabled = it
                        TextColorSettingsStore.setReadOnlySelectedColor(context, it)
                    },
                )
            }
            ApuSettingsItem(
                icon = Icons.Default.FilterAlt,
                title = stringResource(R.string.text_color_filter_choose),
                subtitle = colorLabel(context, selectedRead),
                onClick = { selectedScope = READ_SCOPE },
            )
            ApuSettingsDivider()
            ApuSettingsItem(
                icon = androidx.compose.material.icons.Icons.Default.RestartAlt,
                title = stringResource(R.string.settings_reset_colors),
                subtitle = stringResource(R.string.settings_reset_colors_hint),
                onClick = {
                    TextColorSettingsStore.resetAll(context)
                    onNameColorChanged()
                    showResetDone = true
                },
            )
        }
    }

    if (showResetDone) {
        ApuPremiumNoticeDialog(
            title = stringResource(R.string.settings_reset_colors_done_title),
            message = stringResource(R.string.settings_reset_colors_done_body),
            onDismiss = { showResetDone = false },
            icon = Icons.Default.CheckCircle,
        )
    }
    if (showDefaultDone) {
        ApuPremiumNoticeDialog(
            title = stringResource(R.string.settings_reset_done_title),
            message = stringResource(R.string.settings_default_done_body),
            onDismiss = { showDefaultDone = false },
            icon = Icons.Default.CheckCircle,
        )
    }

    selectedScope?.let { scope ->
        val title = when (scope) {
            NAME_SCOPE -> stringResource(R.string.text_color_name_title)
            READ_SCOPE -> stringResource(R.string.text_color_filter_choose)
            else -> scopes.firstOrNull { it.scope == scope }?.title
                ?: stringResource(R.string.text_color_section_title)
        }
        ApuPremiumDialog(
            title = title,
            onDismiss = { selectedScope = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Text(
                stringResource(R.string.text_color_dialog_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Each choice is an APU card row; the dialog provides the scroll surface.
            TextColorPalette.choices.forEach { choice ->
                val current = when (scope) {
                    NAME_SCOPE -> TextColorSettingsStore.ownNameColor(context)
                    READ_SCOPE -> TextColorSettingsStore.selectedReadColor(context)
                    else -> TextColorSettingsStore.textColor(context, scope)
                }
                ApuPremiumChoiceRow(
                    title = stringResource(choice.labelRes),
                    subtitle = null,
                    selected = current == choice.id,
                    onClick = {
                        when (scope) {
                            NAME_SCOPE -> {
                                TextColorSettingsStore.setOwnNameColor(context, choice.id)
                                onNameColorChanged()
                            }
                            READ_SCOPE -> {
                                TextColorSettingsStore.setSelectedReadColor(context, choice.id)
                                if (choice.id == TextColorPalette.DEFAULT_ID) {
                                    TextColorSettingsStore.setReadOnlySelectedColor(context, false)
                                }
                            }
                            else -> TextColorSettingsStore.setTextColor(context, scope, choice.id)
                        }
                        if (choice.id == TextColorPalette.DEFAULT_ID) {
                            showDefaultDone = true
                        }
                        selectedScope = null
                    },
                    icon = Icons.Default.FormatColorText,
                )
            }
        }
        }
    }
}

private data class ColorScope(
    val key: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val scope: String,
)

private const val NAME_SCOPE = "profile-name"
private const val READ_SCOPE = "reader-filter"

private fun colorLabel(context: android.content.Context, id: String): String =
    context.getString(TextColorPalette.choice(id).labelRes)
