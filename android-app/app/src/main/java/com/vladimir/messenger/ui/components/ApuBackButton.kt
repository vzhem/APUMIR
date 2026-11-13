package com.vladimir.messenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R

/**
 * A persistent, accessible way back, including when the app runs in a launcher
 * without Android's navigation gestures. The light surface and fixed dark icon
 * remain readable over both built-in and user-supplied wallpapers.
 */
@Composable
fun ApuBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ApuHeaderButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
        onClick = onClick,
        modifier = modifier,
        size = 48.dp,
    )
}
