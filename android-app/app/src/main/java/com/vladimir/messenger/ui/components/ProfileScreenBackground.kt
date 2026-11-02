package com.vladimir.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.profile.ProfileBackgroundStore

/**
 * Background for the owner's profile screens.
 *
 * A locally chosen picture wins over the normal APU/chat wallpaper. When there
 * is no picture (or it cannot be decoded), the standard wallpaper is shown.
 * The image is decoded off the main thread and a light theme veil keeps cards
 * and text readable, exactly like the regular chat wallpaper.
 */
@Composable
fun ProfileScreenBackground(active: Boolean = true) {
    val path by ProfileBackgroundStore.path.collectAsStateWithLifecycle()
    val effective = if (active) path else null
    var bitmap by remember(effective) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(effective) {
        bitmap = ProfileBackgroundStore.decode(effective)
    }
    val shown = bitmap
    if (shown == null) {
        ChatWallpaper()
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.28f)),
            )
        }
    }
}
