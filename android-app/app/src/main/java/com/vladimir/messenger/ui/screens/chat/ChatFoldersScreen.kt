package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.folder.ChatFolder
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuPremiumChip
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import kotlin.math.roundToInt

private val FolderRowHeight = 56.dp
private val FolderAccent = Color(0xFF1F8FE0)
private val FolderInk = Color(0xFF1E2430)
private val FolderSubInk = Color(0xFF7A8494)

/**
 * «Папки с чатами» как в Telegram: рекомендованные папки в один тап,
 * свои папки с перетаскиванием за ручку, создание и правка с выбором чатов.
 * Папки сразу становятся вкладками главного списка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFoldersScreen(
    onBackClick: () -> Unit,
    viewModel: ChatFoldersViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    val newFolderName = stringResource(R.string.folder_new)
    val personalFolderName = stringResource(R.string.folder_personal)
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatFolder?>(null) }

    if (creating || editing != null) {
        FolderEditor(
            folder = editing,
            options = options,
            onBack = {
                creating = false
                editing = null
            },
            onSave = { name, keys ->
                val target = editing
                if (target != null) viewModel.update(target, name, keys) else viewModel.create(name, keys)
                creating = false
                editing = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folder_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFE9B8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = Color(0xFFE8A317),
                            modifier = Modifier.size(52.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.folder_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolderSubInk,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            item {
                FolderCard(title = stringResource(R.string.folder_recommended)) {
                    RecommendedRow(
                        title = stringResource(R.string.folder_new),
                        description = stringResource(R.string.folder_new_desc),
                        added = folders.any { it.rule == ChatFolder.Rule.UNREAD },
                        onAdd = { viewModel.addRecommended(ChatFolder.Rule.UNREAD, newFolderName) },
                    )
                    HorizontalDivider(color = Color(0xFFEDF0F4))
                    RecommendedRow(
                        title = stringResource(R.string.folder_personal),
                        description = stringResource(R.string.folder_personal_desc),
                        added = folders.any { it.rule == ChatFolder.Rule.PERSONAL },
                        onAdd = { viewModel.addRecommended(ChatFolder.Rule.PERSONAL, personalFolderName) },
                    )
                }
            }

            item {
                FolderCard(title = stringResource(R.string.folder_title)) {
                    // Встроенная вкладка: её нельзя ни переименовать, ни убрать.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FolderRowHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = Color(0xFFC6CDD6),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.folder_all_chats),
                            style = MaterialTheme.typography.bodyLarge,
                            color = FolderInk,
                        )
                    }
                    if (folders.isNotEmpty()) HorizontalDivider(color = Color(0xFFEDF0F4))
                    FolderRows(
                        folders = folders,
                        onEdit = { editing = it },
                        onDelete = { confirmDelete = it },
                        onMove = viewModel::move,
                    )
                    HorizontalDivider(color = Color(0xFFEDF0F4))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FolderRowHeight)
                            .clickable { creating = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AddCircle,
                            contentDescription = null,
                            tint = FolderAccent,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.folder_create),
                            style = MaterialTheme.typography.bodyLarge,
                            color = FolderAccent,
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { folder ->
        ApuSettingsDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.folder_delete)) },
            text = { Text(stringResource(R.string.folder_delete_text, folder.name)) },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_delete),
                    danger = true,
                    onClick = {
                        viewModel.delete(folder.id)
                        confirmDelete = null
                    },
                )
            },
            dismissButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { confirmDelete = null },
                )
            },
        )
    }
}

@Composable
private fun FolderCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = FolderAccent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RecommendedRow(
    title: String,
    description: String,
    added: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = FolderInk)
            Text(description, style = MaterialTheme.typography.bodySmall, color = FolderSubInk)
        }
        if (added) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF3AA655), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.folder_added), color = Color(0xFF3AA655), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            ApuPremiumChip(
                label = stringResource(R.string.folder_add),
                selected = false,
                onClick = onAdd,
            )
        }
    }
}

/**
 * Строки папок с перетаскиванием: долгое нажатие на ручку поднимает строку,
 * движение вверх/вниз меняет порядок — и сразу порядок вкладок на главном.
 */
@Composable
private fun FolderRows(
    folders: List<ChatFolder>,
    onEdit: (ChatFolder) -> Unit,
    onDelete: (ChatFolder) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val foldersState by rememberUpdatedState(folders)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { FolderRowHeight.toPx() }

    Column {
        folders.forEachIndexed { index, folder ->
            val isDragging = folder.id == draggingId
            if (index > 0) HorizontalDivider(color = Color(0xFFEDF0F4))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FolderRowHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(if (isDragging) Color(0xFFF4F8FC) else Color.Transparent)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = Color(0xFF9AA3AF),
                    modifier = Modifier
                        .size(28.dp)
                        .pointerInput(folder.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = folder.id
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    val list = foldersState
                                    val from = list.indexOfFirst { it.id == draggingId }
                                    if (from >= 0) {
                                        val shift = (dragOffset / rowHeightPx).roundToInt()
                                        val to = (from + shift).coerceIn(0, list.lastIndex)
                                        if (to != from) {
                                            onMove(from, to)
                                            dragOffset -= (to - from) * rowHeightPx
                                        }
                                    }
                                },
                            )
                        },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FolderInk,
                        maxLines = 1,
                    )
                    val subtitle = when (folder.rule) {
                        ChatFolder.Rule.UNREAD -> stringResource(R.string.folder_auto_unread)
                        ChatFolder.Rule.PERSONAL -> stringResource(R.string.folder_auto_personal)
                        ChatFolder.Rule.CUSTOM -> stringResource(R.string.folder_chat_count, folder.chatKeys.size)
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FolderSubInk, maxLines = 1)
                }
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = FolderSubInk)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_edit)) },
                            onClick = {
                                menuOpen = false
                                onEdit(folder)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_move_up)) },
                            enabled = index > 0,
                            onClick = {
                                menuOpen = false
                                onMove(index, index - 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_move_down)) },
                            enabled = index < folders.lastIndex,
                            onClick = {
                                menuOpen = false
                                onMove(index, index + 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_delete), color = Color(0xFFE5484D)) },
                            onClick = {
                                menuOpen = false
                                onDelete(folder)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Создание и правка папки: название и, для ручной папки, выбор чатов. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditor(
    folder: ChatFolder?,
    options: List<FolderChatOption>,
    onBack: () -> Unit,
    onSave: (String, Set<String>) -> Unit,
) {
    val untitled = stringResource(R.string.folder_untitled)
    var name by remember(folder?.id) { mutableStateOf(folder?.name ?: "") }
    var selected by remember(folder?.id) { mutableStateOf(folder?.chatKeys ?: emptySet()) }
    val isAuto = folder != null && folder.rule != ChatFolder.Rule.CUSTOM

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        folder?.name ?: stringResource(R.string.folder_create),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            ApuPremiumButton(
                label = stringResource(R.string.action_save),
                onClick = { onSave(name.trim().ifBlank { untitled }, selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ApuBubbleField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.folder_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (isAuto) {
                item {
                    Text(
                        stringResource(
                            if (folder?.rule == ChatFolder.Rule.UNREAD) {
                                R.string.folder_auto_unread
                            } else {
                                R.string.folder_auto_personal
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolderSubInk,
                    )
                }
            } else {
                item {
                    Column {
                        Text(
                            stringResource(R.string.folder_pick_chats),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FolderInk,
                        )
                        Text(
                            stringResource(R.string.folder_pick_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = FolderSubInk,
                        )
                    }
                }
                items(options, key = { it.key }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (option.key in selected) {
                                    selected - option.key
                                } else {
                                    selected + option.key
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = option.key in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + option.key else selected - option.key
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge, color = FolderInk, maxLines = 1)
                            Text(
                                stringResource(
                                    when {
                                        option.isChannel -> R.string.folder_type_channel
                                        option.isGroup -> R.string.folder_type_group
                                        else -> R.string.folder_type_chat
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = FolderSubInk,
                            )
                        }
                    }
                }
            }
        }
    }
}
