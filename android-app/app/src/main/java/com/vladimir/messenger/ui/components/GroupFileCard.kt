package com.vladimir.messenger.ui.components

// =============================================================================
// GROUPFILECARD.KT - карточка файла сообщества (рой, этапы 9-10)
// =============================================================================
// Одна и та же карточка в пузыре сообщения группы (GroupChatScreen) и под
// постом канала (ChannelScreen): имя, размер, ход приёма, «Скачать»,
// «Спросить у другого», «Сохранить в папку», «Поделиться»; у автора -
// сколько участников уже получили файл. Состояние собирает экран
// (FileCardState.of), здесь только рисование.
// =============================================================================

import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.group.GroupFileSwarm
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.util.GroupFileMarker

/** Что карточка файла (этап 9) знает о файле и его передаче на этом телефоне. */
data class FileCardState(
    val info: GroupFileMarker.Info,
    /** Моя входящая передача этого файла или null, если ещё не просил. */
    val transfer: FileTransferEntity?,
    /** Просьба ушла, передачи ещё нет. */
    val pending: Boolean,
    /** Приём начался, но куски давно не приходят: раздающий пропал. */
    val stalled: Boolean,
    /** Скольким участникам я (автор) уже отдал файл целиком. */
    val seeded: Int,
    /** Картинка: принятая копия или моя авторская. */
    val previewFile: java.io.File?,
    val onDownload: () -> Unit,
    val onSave: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    /** Раунд 124: файл (гифка) уже принят - можно в избранное. */
    val onFavorite: (() -> Unit)? = null,
    /** Файл мой (я автор): карточка без «Скачать», со счётчиком получивших. */
    val isFromMe: Boolean = false,
) {
    companion object {
        /**
         * Собрать состояние карточки из передач чата ([transfers]) и списка
         * просьб ([pendingKeys]). Одна логика для группы и канала: моя
         * входящая - готовая, иначе самая живая (после смены сида строк
         * может быть две), иначе хоть какая-то.
         */
        fun of(
            chatId: String,
            info: GroupFileMarker.Info,
            isFromMe: Boolean,
            transfers: List<FileTransferEntity>,
            pendingKeys: Set<String>,
            receivedFileFor: (FileTransferEntity) -> java.io.File?,
            authorCopyFor: (sha256: String) -> java.io.File?,
            onDownload: () -> Unit,
            onSave: (FileTransferEntity) -> Unit,
            onShare: (file: java.io.File) -> Unit,
            /** Раунд 124: добавить файл в избранное (когда он принят). */
            onFavorite: ((FileTransferEntity) -> Unit)? = null,
            nowMs: Long = System.currentTimeMillis(),
            /** Скольким участникам отдана общая копия (K2): у автора строки COMPLETE на каждого больше нет. */
            servedCount: Int = 0,
        ): FileCardState {
            val mine = transfers.filter { it.fileSha256 == info.sha256 }
            val transfer = mine.firstOrNull { it.direction == "INCOMING" && it.state == "COMPLETE" }
                ?: mine.filter { it.direction == "INCOMING" && it.state != "FAILED" }.maxByOrNull { it.updatedAtMs }
                ?: mine.lastOrNull { it.direction == "INCOMING" }
            val complete = transfer != null && transfer.state == "COMPLETE"
            val stalled = transfer != null && !complete && transfer.state != "FAILED" &&
                nowMs - transfer.updatedAtMs >= GroupFileSwarm.STALL_MS
            // Файл у меня: принятая копия или моя авторская.
            val localFile = when {
                complete -> receivedFileFor(transfer!!)
                isFromMe -> authorCopyFor(info.sha256)
                else -> null
            }
            return FileCardState(
                info = info,
                transfer = transfer,
                pending = GroupFileMarker.key(chatId, info.sha256) in pendingKeys,
                stalled = stalled,
                seeded = if (isFromMe) mine.count { it.direction == "OUTGOING" && it.state == "COMPLETE" } + servedCount else 0,
                previewFile = localFile?.takeIf {
                    info.mediaType.startsWith("image/") || GroupFileMarker.isGif(info)
                },
                onDownload = onDownload,
                onSave = if (complete) {
                    { onSave(transfer!!) }
                } else {
                    null
                },
                onShare = localFile?.let { f -> { onShare(f) } },
                onFavorite = if (complete && onFavorite != null) {
                    { onFavorite(transfer!!) }
                } else {
                    null
                },
                isFromMe = isFromMe,
            )
        }
    }
}

/**
 * Карточка файла (рой, этап 9): имя, размер, ход приёма и кнопки
 * «Скачать» / «Сохранить в папку» / «Поделиться». У автора - сколько
 * участников уже получили файл.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun GroupFileCard(
    state: FileCardState,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    /** Долгое нажатие на картинку: реакции/меню пузыря (групповой чат). */
    onLongPress: (() -> Unit)? = null,
) {
    val info = state.info
    val transfer = state.transfer
    val complete = transfer != null && transfer.direction == "INCOMING" && transfer.state == "COMPLETE"
    val previewPath = state.previewFile?.absolutePath
    var previewBitmap by remember(previewPath) {
        mutableStateOf(AvatarBitmaps.cachedFile(previewPath, sampleSize = 2))
    }
    LaunchedEffect(previewPath) {
        if (previewBitmap == null && previewPath != null) {
            previewBitmap = AvatarBitmaps.loadFile(previewPath, sampleSize = 2)
        }
    }
    var showFull by remember(previewPath) { mutableStateOf(false) }
    if (showFull && previewPath != null) {
        PhotoViewer(
            photos = listOf(PhotoSource.File(previewPath)),
            onDismiss = { showFull = false },
        )
    }
    // Раунд 168: Box-обёртка - в правом верхнем углу карточки живут
    // «три точки» действий (владелец: прежние кнопки под стикером
    // не помещались и рвались посреди слова).
    var showActions by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth(),
    ) {
    // Раунд 169: стикер парит в чате - без подложки, скруглений и полей
    // карточки (владелец: «без лишних рамок и фонов»).
    val stickerFloating = GroupFileMarker.isSticker(info) &&
        GroupFileMarker.isAnimatedImage(info) && previewPath != null
    Column(
        modifier = Modifier
            .then(if (stickerFloating) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
            .then(
                if (stickerFloating) Modifier
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            )
            .padding(if (stickerFloating) 0.dp else 8.dp),
    ) {
        val bitmap = previewBitmap
        if (GroupFileMarker.isAnimatedImage(info) && previewPath != null) {
            // Гифка и анимированный webp-стикер: Coil с декодерами
            // (MessengerApplication.newImageLoader) крутит анимацию сам.
            AsyncImage(
                model = java.io.File(previewPath),
                contentDescription = info.displayName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { showFull = true },
                        onLongClick = onLongPress,
                    ),
            )
            Spacer(Modifier.height(6.dp))
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = info.displayName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { showFull = true },
                        onLongClick = onLongPress,
                    ),
            )
            Spacer(Modifier.height(6.dp))
        }
        // Имя и размер нужны, только пока картинки ещё нет (идёт приём).
        // Картинка на месте - она и есть сообщение (решение владельца).
        val hasPreview = bitmap != null ||
            (GroupFileMarker.isAnimatedImage(info) && previewPath != null)
        if (!hasPreview) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIconFor(info.mediaType), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(GroupFileMarker.formatSize(info.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            // Кнопка «Скачать»: пока файл не просили и он не идёт.
            if (!isFromMe && transfer == null && !state.pending) {
                IconButton(onClick = state.onDownload, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = "Скачать")
                }
            }
        }
        val status = when {
            isFromMe -> if (state.seeded > 0) "Получили: ${state.seeded}" else "Участники запросят файл у вас"
            complete -> "Получено ✓"
            state.stalled -> "Раздающий не отвечает (${transfer!!.completedChunks}/${transfer.chunkCount})"
            transfer != null -> when (transfer.state) {
                "FAILED" -> "Ошибка приёма — нажмите «Скачать» ещё раз"
                "OFFERED", "TRANSFERRING", "VERIFYING" ->
                    "Приём… ${transfer.completedChunks}/${transfer.chunkCount}"
                else -> "Ожидание…"
            }
            state.pending -> "Запрошено, ждём раздающего…"
            else -> ""
        }
        // С картинкой на месте служебные строки не нужны: «Получено ✓»
        // очевидно, остальное покажем, только пока что-то идёт не так.
        val statusVisible = when {
            !hasPreview -> status.isNotEmpty()
            isFromMe -> status.startsWith("Получили") || status.startsWith("Раздающ")
            else -> status.contains("…") || status.startsWith("Ошибка") || status.startsWith("Раздающ")
        }
        if (statusVisible) {
            Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (transfer != null && !complete && transfer.chunkCount > 0 && transfer.state != "FAILED") {
            LinearProgressIndicator(
                progress = { (transfer.completedChunks.toFloat() / transfer.chunkCount.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (transfer != null && (transfer.state == "FAILED" || state.stalled) && !isFromMe) {
            TextButton(onClick = state.onDownload, contentPadding = PaddingValues(0.dp)) {
                Text(if (state.stalled) "Спросить у другого" else "Скачать снова", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
        // Раунд 168: «три точки» в правом верхнем углу карточки; тап -
        // три горизонтальных пузыря в гамме APU (как в «Избранном»).
        val cardHasPreview = previewBitmap != null ||
            (GroupFileMarker.isAnimatedImage(info) && previewPath != null)
        val hasActions = state.onSave != null || state.onShare != null ||
            state.onFavorite != null
        if (cardHasPreview && hasActions) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .clickable { showActions = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Действия",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .widthIn(min = 210.dp),
                ) {
                    val save = state.onSave
                    if (save != null) {
                        CardActionBubble("Сохранить в папку", Icons.Filled.Save) {
                            showActions = false
                            save()
                        }
                    }
                    val share = state.onShare
                    if (share != null) {
                        CardActionBubble("Поделиться", Icons.Filled.Share) {
                            showActions = false
                            share()
                        }
                    }
                    val favorite = state.onFavorite
                    if (favorite != null) {
                        CardActionBubble("В избранное", Icons.Filled.Star) {
                            showActions = false
                            favorite()
                        }
                    }
                }
            }
        }
    }
}

/** Пузырь действия карточки (раунд 168): золотой ряд, иконка + подпись. */
@Composable
private fun CardActionBubble(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** Значок по типу файла: картинка, видео, звук, PDF, прочее. */
fun fileIconFor(mediaType: String): ImageVector = when {
    mediaType.startsWith("image/") -> Icons.Filled.ImageIcon
    mediaType.startsWith("video/") -> Icons.Filled.Movie
    mediaType.startsWith("audio/") -> Icons.Filled.MusicNote
    mediaType == "application/pdf" -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}
