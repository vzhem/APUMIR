package com.vladimir.messenger.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Свои обои читаются с диска и разбираются в картинку. Подложка стоит на
    // КАЖДОМ экране, поэтому на главном потоке это било по всему приложению:
    // каждый переход и каждая смена вкладки заново открывали файл.
    var customBitmap by remember(customUri) {
        mutableStateOf(AvatarBitmaps.cachedUri(customUri))
    }
    LaunchedEffect(customUri) {
        if (customBitmap == null && customUri != null) {
            customBitmap = AvatarBitmaps.loadUri(context, customUri)
        }
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
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
        // Лёгкая вуаль в тон темы: подложка прозрачнее, текст и панели читаются.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.material3.MaterialTheme.colorScheme.background
                        .copy(alpha = 0.28f)
                )
        )
    }
}
