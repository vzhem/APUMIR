package com.vladimir.messenger.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary             = Blue40,
    onPrimary           = Neutral100,
    primaryContainer    = Blue90,
    onPrimaryContainer  = Blue10,
    secondary           = Green40,
    onSecondary         = Neutral100,
    secondaryContainer  = Green90,
    onSecondaryContainer= Green10,
    tertiary            = Purple40,
    onTertiary          = Neutral100,
    tertiaryContainer   = Purple80,
    onTertiaryContainer = Purple10,
    error               = Error40,
    onError             = Neutral100,
    errorContainer      = Error90,
    onErrorContainer    = Error10,
    background          = Neutral99,
    onBackground        = Neutral10,
    surface             = Neutral99,
    onSurface           = Neutral10,
    surfaceVariant      = NeutralVariant90,
    onSurfaceVariant    = NeutralVariant30,
    outline             = NeutralVariant40,
    outlineVariant      = NeutralVariant80,
    inverseSurface      = Neutral20,
    inverseOnSurface    = Neutral95,
    inversePrimary      = Blue80,
)

private val DarkColorScheme = darkColorScheme(
    primary             = Blue80,
    onPrimary           = Blue20,
    primaryContainer    = Blue30,
    onPrimaryContainer  = Blue90,
    secondary           = Green80,
    onSecondary         = Green20,
    secondaryContainer  = Green30,
    onSecondaryContainer= Green90,
    tertiary            = Purple80,
    onTertiary          = Purple10,
    tertiaryContainer   = Purple40,
    onTertiaryContainer = Neutral100,
    error               = Error80,
    onError             = Error10,
    errorContainer      = Error40,
    onErrorContainer    = Error90,
    background          = Neutral10,
    onBackground        = Neutral90,
    surface             = Neutral10,
    onSurface           = Neutral90,
    surfaceVariant      = NeutralVariant20,
    onSurfaceVariant    = NeutralVariant80,
    outline             = NeutralVariant40,
    outlineVariant      = NeutralVariant30,
    inverseSurface      = Neutral90,
    inverseOnSurface    = Neutral20,
    inversePrimary      = Blue40,
)

@Composable
fun P2PMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = MessengerTypography,
        content     = content
    )
}

data class MessengerColors(
    val messageBubbleOwn: Color,
    val messageBubbleOther: Color,
    val messageBubbleOwnText: Color,
    val messageBubbleOtherText: Color,
    val statusOnline: Color,
    val statusOffline: Color,
    val statusConnecting: Color,
    val statusDegraded: Color,
)

val LocalMessengerColors = staticCompositionLocalOf {
    MessengerColors(
        messageBubbleOwn       = MessageBubbleOwn,
        messageBubbleOther     = MessageBubbleOther,
        messageBubbleOwnText   = Color.White,
        messageBubbleOtherText = Color.Black,
        statusOnline           = StatusOnline,
        statusOffline          = StatusOffline,
        statusConnecting       = StatusConnecting,
        statusDegraded         = StatusDegraded,
    )
}
