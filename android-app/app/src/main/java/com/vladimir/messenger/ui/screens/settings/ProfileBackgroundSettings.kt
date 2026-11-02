package com.vladimir.messenger.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.ProfileBackgroundStore
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ProfileScreenBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Profile entry point: one card, one dialog, no growth of SettingsScreen.kt. */
@Composable
fun ProfileBackgroundSettingsCard() {
    var open by remember { mutableStateOf(false) }
    val path by ProfileBackgroundStore.path.collectAsStateWithLifecycle()
    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ApuSettingsItem(
            icon = Icons.Default.Wallpaper,
            title = stringResource(R.string.profile_background_title),
            subtitle = stringResource(
                if (path == null) R.string.profile_background_hint else R.string.profile_background_custom_set,
            ),
            onClick = { open = true },
        )
    }
    if (open) {
        ProfileBackgroundDialog(onDismiss = { open = false })
    }
}

@Composable
private fun ProfileBackgroundDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val path by ProfileBackgroundStore.path.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = false
                saved = false
                resetDone = false
                try {
                    ProfileBackgroundStore.import(context, uri)
                    saved = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("ProfileBackground", "Cannot import profile background", failure)
                    error = true
                } finally {
                    busy = false
                }
            }
        }
    }

    ApuPremiumDialog(
        title = stringResource(R.string.profile_background_title),
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = stringResource(R.string.action_close),
        onConfirm = { if (!busy) onDismiss() },
        confirmEnabled = !busy,
        dismissLabel = null,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.profile_background_local_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                ProfileScreenBackground()
                Text(
                    stringResource(R.string.profile_background_preview),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            ApuPremiumButton(
                label = stringResource(R.string.profile_background_choose),
                enabled = !busy,
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            if (path != null) {
                ApuTextAction(
                    label = stringResource(R.string.profile_background_reset),
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = false
                            saved = false
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    ProfileBackgroundStore.clear(context)
                                }
                                resetDone = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            if (busy) Text(stringResource(R.string.profile_background_wait))
            if (saved) Text(stringResource(R.string.profile_background_saved))
            if (resetDone) Text(stringResource(R.string.profile_background_reset_done))
            if (error) Text(stringResource(R.string.profile_background_error))
            Spacer(Modifier.height(2.dp))
        }
    }
}
