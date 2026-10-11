package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

/**
 * Мягкая «переливающаяся» заливка для заглушек загрузки: вместо пустого экрана
 * пользователь видит очертания будущих сообщений — так делают премиальные мессенджеры.
 */
fun Modifier.apuSkeleton(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "apuSkeleton")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing), RepeatMode.Restart),
        label = "apuSkeletonShift",
    )
    val base = Color(0xFFE6ECF3)
    val glow = Color.White.copy(alpha = 0.85f)
    background(
        Brush.linearGradient(
            colors = listOf(base, glow, base),
            start = Offset(shift * 400f, 0f),
            end = Offset(shift * 400f + 300f, 0f),
        ),
    )
}

/** Заглушка одной строки сообщения: аватар и две полосы текста. */
@Composable
fun ApuSkeletonMessageRow(incoming: Boolean, modifier: Modifier = Modifier) {
    val bubble = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        if (incoming) {
            Spacer(Modifier.size(36.dp).clip(CircleShape).apuSkeleton())
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .width(if (incoming) 200.dp else 170.dp)
                .clip(bubble)
                .apuSkeleton()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).apuSkeleton())
            Spacer(Modifier.fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(5.dp)).apuSkeleton())
        }
    }
}

/** Экран загрузки ленты: несколько заглушек с чередованием входящих и исходящих. */
@Composable
fun ApuSkeletonChatFeed(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        repeat(6) { index ->
            ApuSkeletonMessageRow(incoming = index % 2 == 0)
        }
    }
}
