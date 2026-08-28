package com.vladimir.messenger.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.theme.LocalAppDarkTheme
import com.vladimir.messenger.ui.theme.WallpaperHolder

/**
 * Подложка под чаты, группы, темы, каналы и главный список. Если пользователь
 * выбрал свои обои в настройках - показываем их, иначе сдержанный арт
 * «децентрализованная сеть» в тон действующей теме (день/ночь).
 */
@Composable
fun ChatWallpaper() {
    val customUri by WallpaperHolder.uri.collectAsState()
    val context = LocalContext.current
    val customBitmap = remember(customUri) {
        if (customUri == null) {
            null
        } else {
            try {
                context.contentResolver.openInputStream(Uri.parse(customUri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    if (customBitmap != null) {
        Image(
            bitmap = customBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        val dark = LocalAppDarkTheme.current
        Image(
            painter = painterResource(
                if (dark) R.drawable.chat_wallpaper_dark else R.drawable.chat_wallpaper_light
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
