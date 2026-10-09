package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties


/**
 * Выбор аватара для профиля и сообществ.
 *
 * Готовые изображения, камера и галерея объединены в один APU-диалог: светлая
 * карточка, золотые акценты и небольшие рамки вокруг каждого круглого пресета
 * сохраняют читаемость на любых обоях и в тёмной теме.
 */
@Composable
fun AvatarPickerDialog(
    context: Context,
    onPickUri: (String) -> Unit,
    onPickGallery: () -> Unit,
    /** Снять новое фото камерой телефона. */
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ids = remember(context) {
        (1..50).mapNotNull { i ->
            val id = context.resources.getIdentifier(
                String.format("avatar_std_%02d", i), "drawable", context.packageName,
            )
            if (id != 0) id else null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = ApuBubbleSurfaceColor,
            contentColor = ApuBubbleTextColor,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, ApuBubbleAccentColor.copy(alpha = 0.42f)),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = ApuBubbleAccentColor,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.av_choose_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ApuBubbleTextColor,
                        )
                        Text(
                            text = stringResource(R.string.av_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                // Сетка остаётся прокручиваемой: все 50 изображений доступны,
                // но диалог не вырастает за пределы компактного экрана.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(ids.size) { idx ->
                        val resId = ids[idx]
                        val label = stringArrayResource(R.array.avatar_preset_labels).getOrElse(idx) { stringResource(R.string.settings_avatar) + " ${idx + 1}" }
                        AvatarPresetTile(
                            resId = resId,
                            label = label,
                            onClick = {
                                val name = context.resources.getResourceEntryName(resId)
                                onPickUri(
                                    "android.resource://${context.packageName}/drawable/$name",
                                )
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AvatarSourceButton(
                        label = stringResource(R.string.av_take_photo),
                        icon = Icons.Default.PhotoCamera,
                        onClick = onTakePhoto,
                        modifier = Modifier.weight(1f),
                    )
                    AvatarSourceButton(
                        label = stringResource(R.string.settings_from_gallery),
                        icon = Icons.Default.Image,
                        onClick = onPickGallery,
                        modifier = Modifier.weight(1f),
                    )
                }

                ApuPremiumContentButton(
                    onClick = onDismiss,
                    style = DiagnosticsActionStyle.QUIET,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Круглая мини-карточка одного встроенного изображения. */
@Composable
private fun AvatarPresetTile(
    resId: Int,
    label: String,
    onClick: () -> Unit,
) {
    // Ячейка сетки может быть шире картинки на планшете; сам аватар всегда
    // остаётся круглым и центрированным, а не растягивается в овал.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(ApuBubbleAccentColor.copy(alpha = 0.06f), CircleShape)
                .border(1.dp, ApuBubbleAccentColor.copy(alpha = 0.22f), CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(resId),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

/** Два равноправных действия для собственной фотографии. */
@Composable
private fun AvatarSourceButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ApuPremiumContentButton(
        onClick = onClick,
        style = DiagnosticsActionStyle.QUIET,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
