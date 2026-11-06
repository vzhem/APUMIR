package com.vladimir.messenger.ui.screens.settings

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.VideoAvatarRankGate
import com.vladimir.messenger.ui.components.AvatarCropDialog
import com.vladimir.messenger.ui.components.AvatarPickerDialog
import com.vladimir.messenger.ui.components.VideoAvatarComposer
import com.vladimir.messenger.ui.theme.AvatarHolder
import com.vladimir.messenger.util.AvatarFiles

/**
 * Всё, что происходит после нажатия на свой аватар: выбор картинки, снимок
 * камерой, обрезка и создание видеоаватара.
 *
 * Вынесено из `SettingsScreen.kt`: тот файл упирается в потолок god-файлов, а
 * здесь — законченный поток со своими состояниями (выбранный файл, снимок,
 * ожидание видео), который профилю нужен целиком или не нужен вовсе.
 *
 * Окно выбора гаснет на время системных окон (галерея, камера) и редактора
 * видео, но поток остаётся живым: состояние обрезки и результат видео лежат
 * здесь, а не в списке настроек.
 */
@Composable
internal fun ProfileAvatarDialogs(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Само окно выбора: гаснет, когда человек ушёл в галерею/камеру/видео.
    var pickerVisible by remember { mutableStateOf(true) }
    // Картинка, для которой сейчас выбирают область. null - окна обрезки нет.
    var avatarToCrop by remember { mutableStateOf<Bitmap?>(null) }
    // Куда камера положит снимок: URI нужен и при запуске, и при разборе.
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var showVideoFlow by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (failure: Exception) {
                // Провайдер не даёт постоянного доступа — это штатно: читаем разово ниже.
                android.util.Log.d("ProfileAvatar", "No persistable permission for the image", failure)
            }
            // Не сохраняем сразу: сперва человек выбирает область.
            avatarToCrop = AvatarFiles.readForCrop(context, uri)
        }
        if (avatarToCrop == null) onDismiss()
    }

    val photoTaker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val shot = pendingPhotoUri
        if (ok && shot != null) {
            avatarToCrop = AvatarFiles.readForCrop(context, shot)
        }
        pendingPhotoUri = null
        if (avatarToCrop == null) onDismiss()
    }

    // Разрешение спрашиваем только когда человек нажал «Снять фото»: просить
    // камеру заранее, на всякий случай, - плохая манера.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val target = AvatarFiles.captureTarget(context)
            pendingPhotoUri = target
            photoTaker.launch(target)
        } else {
            onDismiss()
        }
    }

    if (pickerVisible) {
        AvatarPickerDialog(
            context = context,
            onPickUri = { uri ->
                AvatarHolder.set(context, uri)
                onDismiss()
            },
            onPickGallery = {
                pickerVisible = false
                avatarPicker.launch("image/*")
            },
            onPickVideo = {
                pickerVisible = false
                showVideoFlow = true
            },
            // Видеоаватар открывается с «Круга друзей»: человек видит причину
            // до записи, а не после неё.
            videoLockedHint = if (VideoAvatarRankGate.canSet(context)) {
                null
            } else {
                VideoAvatarRankGate.lockedNotice(context)
            },
            onTakePhoto = {
                pickerVisible = false
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.CAMERA,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    val target = AvatarFiles.captureTarget(context)
                    pendingPhotoUri = target
                    photoTaker.launch(target)
                } else {
                    cameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            },
            onDismiss = onDismiss,
        )
    }

    // Выбор области: и для снимка с камеры, и для картинки из галереи.
    avatarToCrop?.let { source ->
        AvatarCropDialog(
            source = source,
            onConfirm = { cropped ->
                AvatarFiles.saveCropped(context, cropped)
                    ?.let { AvatarHolder.set(context, it) }
                avatarToCrop = null
                onDismiss()
            },
            onDismiss = {
                avatarToCrop = null
                onDismiss()
            },
        )
    }

    // Видеоаватар: камера или галерея, редактор видеоисторий, установка.
    if (showVideoFlow) {
        VideoAvatarComposer(
            onDismiss = {
                showVideoFlow = false
                onDismiss()
            },
            onInstalled = {
                showVideoFlow = false
                android.widget.Toast.makeText(
                    context, R.string.av_video_set, android.widget.Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            },
        )
    }
}
