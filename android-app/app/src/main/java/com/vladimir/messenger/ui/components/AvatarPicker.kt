package com.vladimir.messenger.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Выбор аватара: сетка из 50 стандартных картинок в фирменном стиле
 * «ночь + золото» плюс кнопка «Из галереи». Используется и в профиле
 * (Настройки), и для групп/каналов (админ-кабинет) — раунд 42.
 */
@Composable
fun AvatarPickerDialog(
    context: Context,
    onPickUri: (String) -> Unit,
    onPickGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ids = remember(context) {
        (1..50).mapNotNull { i ->
            val id = context.resources.getIdentifier(
                String.format("avatar_std_%02d", i), "drawable", context.packageName
            )
            if (id != 0) id else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите аватар") },
        text = {
            Column {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    items(ids.size) { idx ->
                        val resId = ids[idx]
                        Image(
                            painter = painterResource(resId),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(54.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val name = context.resources.getResourceEntryName(resId)
                                    onPickUri(
                                        "android.resource://" + context.packageName +
                                            "/drawable/" + name
                                    )
                                },
                        )
                    }
                }
                TextButton(onClick = onPickGallery) {
                    Text("Из галереи")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
