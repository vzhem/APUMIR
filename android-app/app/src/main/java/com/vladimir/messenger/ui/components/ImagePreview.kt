package com.vladimir.messenger.ui.components

// =============================================================================
// IMAGEPREVIEW.KT — картинка внутри пузыря сообщения
// =============================================================================
// Coil уже был в зависимостях (coil-compose 2.7.0), но не использовался нигде:
// сообщения с картинками показывались текстом. Здесь одна точка отрисовки,
// чтобы и личный чат, и темы группы показывали картинки одинаково.
// =============================================================================

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

@Composable
fun ImagePreview(
    model: Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        // Картинка грузится из сети: показываем, что идёт загрузка, и честно
        // пишем, если не загрузилось, вместо пустого места.
        loading = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        },
        error = {
            Text(
                "Картинка не загрузилась",
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
