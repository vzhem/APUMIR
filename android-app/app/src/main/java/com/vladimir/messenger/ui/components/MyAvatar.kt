package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.ui.theme.AvatarHolder

@Composable
fun MyAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    size: Int = 52,
) {
    val context = LocalContext.current
    val avatarUri by AvatarHolder.uri.collectAsState()
    // Чтение файла и разбор картинки - вне главного потока. Раньше это шло
    // прямо в отрисовке: свой аватар читался с диска на каждом показе экрана
    // настроек и на каждой смене вкладки, подвешивая рисование.
    var bitmap by remember(avatarUri) {
        mutableStateOf(AvatarBitmaps.cachedUri(avatarUri))
    }
    LaunchedEffect(avatarUri) {
        if (bitmap == null && avatarUri != null) {
            bitmap = AvatarBitmaps.loadUri(context, avatarUri)
        }
    }

    // Локальная копия: у делегата (var ... by) умного приведения к non-null
    // не бывает, поэтому обращение к .asImageBitmap() без неё не собирается.
    val shown = bitmap
    // Мой аккаунт в элите: вокруг аватарки — то же объёмное золотое кольцо, что
    // у собеседников в списке чатов, в группах и каналах. Владелец 2026-10-07:
    // «чтобы аккаунт VIP должно быть видно везде».
    val selfVip = rememberSelfVip()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (selfVip) VipRingWidth else 0.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = stringResource(R.string.settings_avatar),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Avatar(name = displayName, modifier = Modifier.fillMaxSize(), size = size)
            }
        }
        if (selfVip) {
            VipRing(Modifier.matchParentSize())
        }
    }
}
