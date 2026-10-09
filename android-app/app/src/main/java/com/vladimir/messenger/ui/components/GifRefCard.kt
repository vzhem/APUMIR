package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// GIFREFCARD.KT - карточка-ссылка на гифку в чате (раунд 128)
// =============================================================================
// Два абонента обмениваются только ССЫЛКАМИ на гифку (от лица отправителя).
// Байты каждый телефон тихо подтягивает с хранителей сети, и карточка в чате
// оживает: пока гифки нет - «загружается из сети…», приехала - анимирует.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vladimir.messenger.data.gif.GifLibrary

@Composable
fun GifRefCard(
    content: String,
    modifier: Modifier = Modifier,
    /** Карточка просит VM тихо подтянуть гифку, если её нет в библиотеке. */
    onEnsure: (String) -> Unit = {},
    /**
     * Раунд 210: «три точки» в углу гифки - то же меню действий сообщения,
     * что у текстовых пузырей (владелец: меню должно быть и у гифок).
     * null - точки не рисуются.
     */
    onMenu: (() -> Unit)? = null,
) {
    val sha = remember(content) { GifLibrary.gifRefSha(content) }
    if (sha == null) {
        // Не должно случаться: маркер проверяет экран до вызова карточки.
        Text(
            content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Текущее состояние байтов + оживание, когда гифка приедет с хранителей.
    val file by produceState<java.io.File?>(initialValue = null, sha) {
        value = GifLibrary.gifFile(context, sha)?.takeIf { it.isFile }
        GifLibrary.arrivalsFlow().collect { arrived ->
            if (arrived == sha) {
                value = GifLibrary.gifFile(context, sha)?.takeIf { it.isFile }
            }
        }
    }
    LaunchedEffect(sha) { onEnsure(sha) }

    var showFull by remember(sha) { mutableStateOf(false) }
    val shownFile = file
    if (showFull && shownFile != null) {
        PhotoViewer(
            photos = listOf(PhotoSource.File(shownFile.absolutePath)),
            onDismiss = { showFull = false },
        )
    }
    Column(
        modifier = modifier
            .widthIn(max = 300.dp)
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        if (shownFile != null) {
            // Гифка на месте - живёт и анимирует, как обычная гифка в чате.
            AsyncImage(
                model = shownFile,
                contentDescription = stringResource(R.string.cd_gif),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .apuBubbleSurface()
                    .clickable { showFull = true },
            )
        } else {
            // Ссылка доехала, байты ещё подтягиваются с хранителей сети.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .apuBubbleSurface()
                    .padding(vertical = 22.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ApuBubbleAccentColor)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gifref_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleMutedColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Раунд 210: «три точки» в правом верхнем углу гифки - как у картинок
        // в личке (р44/209): тёмный кружок, белый глиф, тап открывает меню.
        if (onMenu != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { onMenu?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.sv_actions),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        }
    }
}
