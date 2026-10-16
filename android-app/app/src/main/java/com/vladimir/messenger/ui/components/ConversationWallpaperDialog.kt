package com.vladimir.messenger.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.wallpaper.ConversationWallpaper
import com.vladimir.messenger.ui.theme.LocalAppDarkTheme
import com.vladimir.messenger.ui.theme.WallpaperTint
import com.vladimir.messenger.ui.theme.WallpaperTintHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ConversationWallpaperDialog(scope: String, shared: String? = null,
    onSetShared: (suspend (String) -> Result<Unit>)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var forAll by remember { mutableStateOf(false) }
    var personalChoice by remember(scope, shared) {
        mutableStateOf(ConversationWallpaper.personal(context, scope) ?: shared.orEmpty())
    }
    var sharedChoice by remember(scope, shared) { mutableStateOf(shared.orEmpty()) }
    val choice = if (forAll) sharedChoice else personalChoice
    fun choose(value: String) {
        if (forAll) sharedChoice = value else personalChoice = value
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutineScope.launch {
            busy = true
            try { choose(ConversationWallpaper.import(context, uri)); error = null }
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
    val globalTint by WallpaperTintHolder.tint.collectAsState()
    val dark = LocalAppDarkTheme.current
    val selectedTint = when {
        choice.startsWith("preset:") -> WallpaperTint.fromStored(choice.removePrefix("preset:"))
        choice.isBlank() -> globalTint
        else -> null
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
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                WallpaperTint.entries.forEach { tint ->
                    ConversationWallpaperTintSwatch(
                        option = tint,
                        dark = dark,
                        selected = selectedTint == tint,
                        enabled = !busy,
                        name = stringResource(tint.labelRes),
                        onClick = { choose("preset:${tint.storedValue}") },
                    )
                }
            }
            selectedTint?.let { Text(stringResource(it.labelRes), color = ApuBubbleAccentColor) }
            ApuTextAction(label = stringResource(if (forAll) R.string.conversation_wallpaper_reset_shared
                else R.string.conversation_wallpaper_follow), enabled = !busy, onClick = { apply(null) })
            if (busy) Text(stringResource(R.string.conversation_wallpaper_wait))
            error?.let { Text(it) }
        }
    }
}

@Composable
private fun ConversationWallpaperTintSwatch(
    option: WallpaperTint,
    dark: Boolean,
    selected: Boolean,
    enabled: Boolean,
    name: String,
    onClick: () -> Unit,
) {
    val fill = if (option == WallpaperTint.CLASSIC) {
        if (dark) Brush.verticalGradient(listOf(Color(0xFF16233A), Color(0xFF0A1018)))
        else Brush.verticalGradient(listOf(Color(0xFFB5D0AA), Color(0xFFD0DEB7)))
    } else Brush.verticalGradient(option.colorsFor(dark))
    Column(
        modifier = Modifier.width(64.dp)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp)
                .apuPremiumLift(if (selected) 6.dp else 3.dp, CircleShape, ApuGold.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(fill)
                .border(if (selected) 3.dp else 1.dp,
                    if (selected) ApuGold else Color.White.copy(alpha = 0.72f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, null,
                tint = if (dark) ApuGold else ApuGoldInk, modifier = Modifier.size(22.dp))
        }
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = if (enabled) ApuBubbleTextColor else ApuBubbleMutedColor)
    }
}
