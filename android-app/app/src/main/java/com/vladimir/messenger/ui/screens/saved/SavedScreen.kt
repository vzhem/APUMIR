package com.vladimir.messenger.ui.screens.saved

// =============================================================================
// SAVEDSCREEN.KT — «Избранное»: личное хранилище абонента
// =============================================================================
// Сюда попадает всё, что человек переслал себе: файлы и фото из личных чатов,
// сообщения из групп, посты из каналов, свои заметки.
//
// Файлы здесь не копии, а ссылки на уже принятые передачи — поэтому у записи
// файла есть «Сохранить в телефон» (выгрузить наружу через системный выбор
// папки) и «Поделиться».
// =============================================================================

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import com.vladimir.messenger.data.repository.SavedItemsRepository
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ChatWallpaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onBackClick: () -> Unit,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNoteDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SavedItemEntity?>(null) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Куда выгрузить файл наружу - спрашивает система.
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> viewModel.onExportTargetPicked(uri) }
    LaunchedEffect(uiState.pendingExport) {
        uiState.pendingExport?.let { exportPicker.launch(it.displayName) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = { Text("Избранное", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showNoteDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Своя заметка")
                }
            },
        ) { padding ->
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    ApuBubble(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = ApuBubbleMutedColor,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("В избранном пусто", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Перешлите сюда файл, фото или пост из чата, " +
                                    "группы или канала - и он останется у вас.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        SavedItemBubble(
                            item = item,
                            previewProvider = { viewModel.previewFor(item) },
                            onShare = { viewModel.share(item) },
                            onExport = { viewModel.requestExport(item) },
                            onDelete = { confirmDelete = item },
                        )
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        NoteDialog(
            onDismiss = { showNoteDialog = false },
            onSave = { text ->
                viewModel.addNote(text)
                showNoteDialog = false
            },
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Убрать из избранного?") },
            text = {
                Text(
                    if (item.kind == SavedItemsRepository.KIND_FILE) {
                        // Важно объяснить: файл останется в чате, пропадёт только ссылка.
                        "Запись пропадёт из избранного. Сам файл в чате останется."
                    } else {
                        "Запись пропадёт из избранного."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item.id)
                    confirmDelete = null
                }) { Text("Убрать") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }
}

/** Одна запись избранного в пузыре APU. */
@Composable
private fun SavedItemBubble(
    item: SavedItemEntity,
    previewProvider: suspend () -> java.io.File?,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val time = remember(item.savedAtMs) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.savedAtMs))
    }
    val isFile = item.kind == SavedItemsRepository.KIND_FILE

    ApuBubble(modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
        if (item.sourceTitle.isNotBlank()) {
            Text(
                item.sourceTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (isFile) {
            // Картинка читается с диска отдельно: держать её в записи нельзя,
            // список бы вырос в памяти на каждое сохранённое фото.
            val preview by produceState<java.io.File?>(initialValue = null, item.id) {
                value = previewProvider()
            }
            // Разбор картинки - в фоне и через общий кэш.
            val previewPath = preview?.takeIf { it.isFile }?.absolutePath
            var bitmap by remember(previewPath) {
                mutableStateOf(
                    com.vladimir.messenger.ui.components.AvatarBitmaps.cachedFile(previewPath)
                )
            }
            LaunchedEffect(previewPath) {
                if (bitmap == null && previewPath != null) {
                    bitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
                        .loadFile(previewPath)
                }
            }
            // Локальная копия ради умного приведения к non-null.
            val shownBitmap = bitmap
            if (shownBitmap != null) {
                Image(
                    bitmap = shownBitmap.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = ApuBubbleMutedColor,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatSize(item.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
        } else {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = ApuBubbleMutedColor,
                modifier = Modifier.weight(1f),
            )
            if (isFile) {
                IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Сохранить в телефон",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Поделиться",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Убрать из избранного",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметка себе") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Текст") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f ГБ", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}
