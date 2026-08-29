package com.vladimir.messenger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// =============================================================================
// Палитра «тёмный navy + золото» в тон иконке приложения.
// Ночь: глубокий синий фон, золотые акценты, стальная синь для вторичного.
// День: слоновая кость, бронзово-золотой акцент, стальная синь.
// =============================================================================

// Золото и бронза
private val Gold40        = Color(0xFF7A5A10)  // акцент дня (контраст на слоновой кости)
private val Gold80        = Color(0xFFE4B45A)  // акцент ночи
private val GoldOnDark    = Color(0xFF2A1D04)  // текст на золоте ночью
private val GoldOnLight   = Color(0xFFFFF8E6)  // текст на бронзе днём
private val GoldContNight = Color(0xFF4A3814)
private val GoldContOnN   = Color(0xFFF4E3B2)
private val GoldContDay   = Color(0xFFF3E2AE)
private val GoldContOnD   = Color(0xFF463205)

// Стальная синь — вторичный акцент
private val Steel40       = Color(0xFF00509E)
private val Steel80       = Color(0xFF8FB8DE)
private val SteelContN    = Color(0xFF22384E)
private val SteelContOnN  = Color(0xFFCFE4F7)
private val SteelContD    = Color(0xFFD6E7FA)
private val SteelContOnD  = Color(0xFF00243F)

// Янтарь — третичный акцент
private val Amber40       = Color(0xFF8F4A00)
private val Amber80       = Color(0xFFFFB765)

// Ночной navy
private val NavyBg        = Color(0xFF081120)
private val NavySurface   = Color(0xFF0C1830)
private val NavySurfaceV  = Color(0xFF1B2C44)
private val NavyOn        = Color(0xFFE2E9F4)
private val NavyOnVar     = Color(0xFFB9C6DA)
private val NavyOutline   = Color(0xFF3D5271)
private val NavyOutlineV  = Color(0xFF243852)

// Дневная слоновая кость
private val IvoryBg       = Color(0xFFFAF7F0)
private val IvorySurface  = Color(0xFFFDFBF5)
private val IvorySurfaceV = Color(0xFFE9E2D2)
private val IvoryOn       = Color(0xFF1E2430)
private val IvoryOnVar    = Color(0xFF4A4639)
private val IvoryOutline  = Color(0xFF7B7668)
private val IvoryOutlineV = Color(0xFFD8D2C2)

// Error40 и Error80 уже объявлены в Color.kt — здесь только контейнеры.
private val ErrorContD    = Color(0xFFFFDAD6)
private val ErrorContOnD  = Color(0xFF410002)
private val ErrorContN    = Color(0xFF93000A)
private val ErrorContOnN  = Color(0xFFFFDAD6)

private val DayIvoryScheme = lightColorScheme(
    primary             = Gold40,
    onPrimary           = GoldOnLight,
    primaryContainer    = GoldContDay,
    onPrimaryContainer  = GoldContOnD,
    secondary           = Steel40,
    onSecondary         = Color.White,
    secondaryContainer  = SteelContD,
    onSecondaryContainer= SteelContOnD,
    tertiary            = Amber40,
    onTertiary          = Color.White,
    tertiaryContainer   = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2E1500),
    error               = Error40,
    onError             = Color.White,
    errorContainer      = ErrorContD,
    onErrorContainer    = ErrorContOnD,
    background          = IvoryBg,
    onBackground        = IvoryOn,
    surface             = IvorySurface,
    onSurface           = IvoryOn,
    surfaceVariant      = IvorySurfaceV,
    onSurfaceVariant    = IvoryOnVar,
    outline             = IvoryOutline,
    outlineVariant      = IvoryOutlineV,
    inverseSurface      = Color(0xFF303036),
    inverseOnSurface      = Color(0xFFF1F0F4),
    inversePrimary      = Gold80,
)

private val NightNavyScheme = darkColorScheme(
    primary             = Gold80,
    onPrimary           = GoldOnDark,
    primaryContainer    = GoldContNight,
    onPrimaryContainer  = GoldContOnN,
    secondary           = Steel80,
    onSecondary         = Color(0xFF10243A),
    secondaryContainer  = SteelContN,
    onSecondaryContainer= SteelContOnN,
    tertiary            = Amber80,
    onTertiary          = Color(0xFF4A2500),
    tertiaryContainer   = Color(0xFF6B3800),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error               = Error80,
    onError             = Color(0xFF690005),
    errorContainer      = ErrorContN,
    onErrorContainer    = ErrorContOnN,
    background          = NavyBg,
    onBackground        = NavyOn,
    surface             = NavySurface,
    onSurface           = NavyOn,
    surfaceVariant      = NavySurfaceV,
    onSurfaceVariant    = NavyOnVar,
    outline             = NavyOutline,
    outlineVariant      = NavyOutlineV,
    inverseSurface      = NavyOn,
    inverseOnSurface    = NavySurface,
    inversePrimary      = Gold40,
)

// Всё закруглённое: карточки, поля, кнопки, диалоги.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small      = RoundedCornerShape(14.dp),
    medium     = RoundedCornerShape(18.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Тёмная ли тема сейчас действует — экраны выбирают по ней подложку и арты. */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

@Composable
fun P2PMessengerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme     = if (darkTheme) NightNavyScheme else DayIvoryScheme
    val messengerColors = if (darkTheme) NightMessengerColors else DayMessengerColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalMessengerColors provides messengerColors,
        LocalAppDarkTheme    provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = MessengerTypography,
            shapes      = AppShapes,
            content     = content,
        )
    }
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

private val DayMessengerColors = MessengerColors(
    messageBubbleOwn       = Color(0xFFEFC975),  // золотистый пузырь своих
    messageBubbleOther     = Color(0xFFFFFFFF),  // белые пузыри чужих
    messageBubbleOwnText   = Color(0xFF3A2A05),
    messageBubbleOtherText = Color(0xFF1E2430),
    statusOnline           = StatusOnline,
    statusOffline          = StatusOffline,
    statusConnecting       = StatusConnecting,
    statusDegraded         = StatusDegraded,
)

private val NightMessengerColors = MessengerColors(
    messageBubbleOwn       = Gold80,
    // Раунд 41: ночью чужие пузыри тоже светлые с тёмным текстом - текст
    // читается на любой подложке (просьба владельца).
    messageBubbleOther     = Color(0xFFF2F4F7),
    messageBubbleOwnText   = GoldOnDark,
    messageBubbleOtherText = Color(0xFF1E2430),
    statusOnline           = StatusOnline,
    statusOffline          = StatusOffline,
    statusConnecting       = StatusConnecting,
    statusDegraded         = StatusDegraded,
)

val LocalMessengerColors = staticCompositionLocalOf { DayMessengerColors }
