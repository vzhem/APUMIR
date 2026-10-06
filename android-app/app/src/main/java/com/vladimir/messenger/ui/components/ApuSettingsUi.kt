package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Красный акцент для опасных действий и предупреждений (удаление, блокировка,
 * жалоба): тот же тон, что у `error` в фирменной палитре ниже.
 */
val ApuSettingsDangerColor: Color = Color(0xFFA12D3A)

/** Fixed, readable controls inside the light house bubble, including in night mode. */
@Composable
private fun ApuSettingsPalette(content: @Composable () -> Unit) {
    val colours = MaterialTheme.colorScheme.copy(
        primary = ApuBubbleAccentColor,
        onPrimary = Color(0xFFFFF8E6),
        primaryContainer = Color(0xFFF5E8C6),
        onPrimaryContainer = Color(0xFF463205),
        secondary = ApuBubbleLinkColor,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE4EDF8),
        onSecondaryContainer = Color(0xFF263A4C),
        surface = ApuBubbleSurfaceColor,
        onSurface = ApuBubbleTextColor,
        surfaceVariant = Color(0xFFF0EFEA),
        onSurfaceVariant = ApuBubbleMutedColor,
        outline = ApuBubbleAccentColor.copy(alpha = 0.38f),
        outlineVariant = ApuBubbleAccentColor.copy(alpha = 0.16f),
        error = Color(0xFFA12D3A),
        surfaceTint = ApuBubbleAccentColor,
    )
    MaterialTheme(colorScheme = colours, content = content)
}

/** Uses the exact shared bubble, not a second Material-card design. */
@Composable
fun ApuSettingsCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ApuBubbleCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = if (highlighted) Color(0xFFFFF7E5).copy(alpha = 0.94f) else ApuBubbleSurfaceColor,
    ) {
        val scope = this
        ApuSettingsPalette { scope.content() }
    }
}

@Composable
fun ApuSettingsHeader(title: String) {
    ApuHeaderBubble {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ApuBubbleTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ApuSettingsSectionTitle(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ApuBubbleAccentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.apuBubbleSurface(shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun ApuSettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = ApuBubbleAccentColor.copy(alpha = 0.15f),
    )
}

/** Stable crisp icon; the row does not spend battery on an always-running shimmer. */
@Composable
fun ApuSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .background(ApuBubbleAccentColor.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ApuBubbleAccentColor, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = ApuBubbleTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ApuBubbleMutedColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun ApuProfileQuickAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp))
            .background(ApuBubbleAccentColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ApuBubbleAccentColor, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = ApuBubbleAccentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Profile/settings modal shares the same light surface and readable control palette. */
@Composable
fun ApuSettingsDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    ApuSettingsPalette {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            modifier = modifier.border(1.dp, ApuBubbleAccentColor.copy(alpha = 0.30f), ApuBubbleShape),
            shape = ApuBubbleShape,
            containerColor = ApuBubbleSurfaceColor,
            iconContentColor = ApuBubbleAccentColor,
            titleContentColor = ApuBubbleTextColor,
            textContentColor = ApuBubbleMutedColor,
            tonalElevation = 0.dp,
            properties = properties,
        )
    }
}
