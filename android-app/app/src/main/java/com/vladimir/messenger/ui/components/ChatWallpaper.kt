package com.vladimir.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.theme.LocalAppDarkTheme

/**
 * Подложка под чаты, группы, темы, каналы и главный список: сдержанный арт
 * «децентрализованная сеть многих каналов связи» в тон иконке. Вариант
 * выбирается по действующей теме (день/ночь).
 */
@Composable
fun ChatWallpaper() {
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
