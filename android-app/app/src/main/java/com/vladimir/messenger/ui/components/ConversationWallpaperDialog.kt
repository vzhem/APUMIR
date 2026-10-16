package com.vladimir.messenger.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.wallpaper.ConversationWallpaper
import com.vladimir.messenger.ui.theme.WallpaperTint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ConversationWallpaperDialog(scope: String, shared: String? = null,
    onSetShared: (suspend (String) -> Result<Unit>)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var forAll by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf(ConversationWallpaper.personal(context, scope) ?: shared.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutineScope.launch {
            busy = true
            try { choice = ConversationWallpaper.import(context, uri); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    fun apply(value: String?) {
        coroutineScope.launch {
            busy = true
            error = null
            try {
                if (forAll) checkNotNull(onSetShared).invoke(value.orEmpty()).getOrThrow()
                else ConversationWallpaper.setPersonal(context, scope, value)
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось изменить фон" }
            finally { busy = false }
        }
    }
    ApuPremiumDialog(title = stringResource(R.string.conversation_wallpaper),
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = stringResource(R.string.conversation_wallpaper_apply),
        confirmEnabled = !busy, dismissEnabled = !busy, onConfirm = { apply(choice) }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.conversation_wallpaper_priority))
            if (onSetShared != null) {
                ApuPremiumChip(label = stringResource(R.string.conversation_wallpaper_personal), selected = !forAll,
                    enabled = !busy, onClick = { forAll = false })
                ApuPremiumChip(label = stringResource(R.string.conversation_wallpaper_shared), selected = forAll,
                    enabled = !busy, onClick = { forAll = true })
            } else Text(stringResource(R.string.conversation_wallpaper_personal))
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                ChatWallpaper(shared = choice)
                Text(stringResource(R.string.conversation_wallpaper_preview), modifier = Modifier.padding(16.dp))
            }
            ApuPremiumButton(label = stringResource(R.string.conversation_wallpaper_gallery), enabled = !busy,
                onClick = { picker.launch("image/*") })
            WallpaperTint.entries.forEach { tint ->
                ApuPremiumChip(label = stringResource(tint.labelRes), selected = choice == "preset:${tint.storedValue}",
                    enabled = !busy, onClick = { choice = "preset:${tint.storedValue}" })
            }
            ApuTextAction(label = stringResource(if (forAll) R.string.conversation_wallpaper_reset_shared
                else R.string.conversation_wallpaper_follow), enabled = !busy, onClick = { apply(null) })
            if (busy) Text(stringResource(R.string.conversation_wallpaper_wait))
            error?.let { Text(it) }
        }
    }
}
