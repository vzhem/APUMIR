package com.vladimir.messenger.ui.components

// =============================================================================
// MYAVATAR.KT
// =============================================================================
// Мой аватар: картинка из галереи (AvatarHolder) либо, если её нет,
// стандартный круг с инициалами. Размер задаёт вызывающий экран через
// modifier (например, Modifier.size(96.dp)).
// =============================================================================

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.ui.theme.AvatarHolder

@Composable
fun MyAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val avatarUri by AvatarHolder.uri.collectAsState()
    val bitmap = remember(avatarUri) {
        if (avatarUri == null) {
            null
        } else {
            try {
                context.contentResolver.openInputStream(Uri.parse(avatarUri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Аватар",
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Avatar(name = displayName, modifier = modifier)
    }
}
