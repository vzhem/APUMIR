package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.location.MapApp
import com.vladimir.messenger.data.location.MapAppPreferenceStore
import com.vladimir.messenger.ui.components.ApuPremiumChoiceRow
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.mapAppLabel
import com.vladimir.messenger.util.MapAppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One extracted card: SettingsScreen gets one wiring line, not another settings implementation. */
@Composable
fun MapAppSettingsCard() {
    val context = LocalContext.current
    val selected by MapAppPreferenceStore.app.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(emptyMap<MapApp, Boolean>()) }
    LaunchedEffect(Unit) { MapAppPreferenceStore.initialize(context) }
    LaunchedEffect(open) {
        if (open) {
            available = withContext(Dispatchers.IO) {
                MapApp.entries.associateWith { MapAppLauncher.isAvailable(context, it) }
            }
        }
    }

    ApuSettingsCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ApuSettingsItem(
            icon = Icons.Default.Map,
            title = stringResource(R.string.map_settings_title),
            subtitle = stringResource(R.string.map_settings_selected, mapAppLabel(selected)),
            onClick = { open = true },
        )
    }
    if (open) {
        ApuPremiumDialog(
            title = stringResource(R.string.map_settings_title),
            onDismiss = { open = false },
            dismissLabel = stringResource(R.string.action_close),
            onDismissClick = { open = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.map_settings_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MapApp.entries.forEach { app ->
                    val installed = available[app]
                    ApuPremiumChoiceRow(
                        title = mapAppLabel(app),
                        subtitle = when (app) {
                            MapApp.ASK -> stringResource(R.string.map_app_ask_hint)
                            MapApp.BROWSER -> stringResource(R.string.map_app_browser_hint)
                            else -> stringResource(
                                if (installed == false) R.string.map_app_not_installed else R.string.map_app_native_hint,
                            )
                        },
                        selected = selected == app,
                        icon = Icons.Default.Map,
                        onClick = {
                            MapAppPreferenceStore.set(context, app)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
