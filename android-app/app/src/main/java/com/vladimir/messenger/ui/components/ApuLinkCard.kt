package com.vladimir.messenger.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R

/**
 * Карточка ссылки под сообщением: домен крупно, путь и подпись «Открыть ссылку»
 * мельче. Страницу не загружаем — адрес уже у нас, сеть не нужна. Нажатие
 * открывает ссылку так же, как нажатие по ней в тексте.
 */
@Composable
fun ApuLinkCard(url: String, textColor: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val full = remember(url) { if (url.startsWith("http")) url else "https://$url" }
    val uri = remember(full) { runCatching { Uri.parse(full) }.getOrNull() }
    val host = remember(uri, url) { uri?.host?.removePrefix("www.") ?: url }
    val path = remember(uri) { uri?.path?.takeIf { it.length > 1 }?.let { it.take(60) } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.55f))
            .clickable {
                uri?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it)) }
            }
            .padding(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        ApuPremiumIconTile(icon = Icons.Filled.Link, size = 36.dp, corner = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                path ?: stringResource(R.string.link_preview_open),
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
