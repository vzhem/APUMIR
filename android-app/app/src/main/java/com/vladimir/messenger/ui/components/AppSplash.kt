package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import android.app.Activity
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vladimir.messenger.service.CoreStatus
import com.vladimir.messenger.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// Имя с префиксом Splash: в этом пакете теперь живёт общая золотая палитра
// приложения (ApuPremium.kt, SplashGold = 0xFFF2B836) — совпадать имена не должны.
private val SplashGold = Color(0xFFE4B45A)
private val SplashSteel = Color(0xFF91A8C5)
private const val SPLASH_MAX_MILLIS = 10_000L
private const val SPLASH_MIN_MILLIS = 900L
private const val SPLASH_FADE_MILLIS = 420

/** Sharp digital-core scene. Readiness, warm-start bypass and timeout are unchanged. */
@Composable
fun AppSplash(onFinished: () -> Unit) {
    val coreStage by CoreStatus.stage.collectAsState()
    var leaving by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(SPLASH_FADE_MILLIS),
        label = "splash-fade",
    )

    // Monotonic clock: changing the wall clock must not extend startup.
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        kotlinx.coroutines.withTimeoutOrNull(SPLASH_MAX_MILLIS) {
            CoreStatus.ready.first { it }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed < SPLASH_MIN_MILLIS) delay(SPLASH_MIN_MILLIS - elapsed)
        leaving = true
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            delay(SPLASH_FADE_MILLIS.toLong())
            onFinished()
        }
    }

    // The art is always navy, even in day mode; restore the normal app bars on exit.
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val darkTheme = LocalAppDarkTheme.current
    if (window != null) {
        val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
        val originalNavigationMode = remember(controller) { controller.isAppearanceLightNavigationBars }
        SideEffect {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        DisposableEffect(controller, darkTheme) {
            onDispose {
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = originalNavigationMode
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(SplashNavy),
    ) {
        val heroSize = SplashOrbitGeometry.heroSizeDp(maxWidth.value, maxHeight.value)
        SplashCoreScene(
            modifier = Modifier
                .size(heroSize.dp)
                .align(Alignment.Center)
                .offset(y = 4.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding().padding(top = 20.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "APU",
                color = Color(0xFFF5E8C9),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 7.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.splash_tagline_core), color = SplashSteel, fontSize = 13.sp, letterSpacing = 0.5.sp)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(targetState = coreStage, label = "splash-stage") { stage ->
                Text(
                    text = stage,
                    color = SplashGold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.splash_subtitle_network),
                color = SplashSteel,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Раунд 263: тонкая полоска «Ядро подключается…» поверх экрана, пока ядро
 * ещё доподнимается в фоне. Пустой список не должен выглядеть поломкой.
 */
@Composable
fun CoreWarmBar() {
    val ready by CoreStatus.ready.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = !ready,
        enter = androidx.compose.animation.expandVertically() +
            androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() +
            androidx.compose.animation.fadeOut(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(SplashGold.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = SplashGold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.splash_connecting),
                color = SplashGold,
                fontSize = 12.sp,
            )
        }
    }
}
