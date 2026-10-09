package com.vladimir.messenger.ui.screens.saved

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
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

import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.vladimir.messenger.ui.components.ApuScrollbar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import com.vladimir.messenger.data.repository.SavedItemsRepository
import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vladimir.messenger.ui.components.ApuPremiumFloatingActionButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onBackClick: () -> Unit,
    /** Открыть оригинал: личный чат, группу с темой или ленту канала. */
    onOpenOrigin: (item: SavedItemEntity) -> Unit = {},
    /**
     * Нижняя панель разделов. Приходит снаружи, из навигации: экран не знает
     * маршрутов и не должен их знать. Пустая по умолчанию, чтобы превью и
     * тесты обходились без навигации.
     */
    bottomBar: @Composable () -> Unit = {},
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    // Раунд 163: выбор стикера для избранного.
    var showStickerPicker by remember { mutableStateOf(false) }
    var showGifCatalog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SavedItemEntity?>(null) }
    // Черновик нижней панели ввода: пишем заметку прямо здесь, как сообщение в
    // чате (просьба владельца от 2026-10-06). Раньше текст можно было ввести
    // только через «+» → «Заметка» в диалоге.
    var draft by remember { mutableStateOf("") }
    // Пока клавиатура открыта, панель разделов уступает место: так в чате и
    // так остаётся больше строк списка. Фокус вернётся - панель вернётся.
    var inputFocused by remember { mutableStateOf(false) }

    // Раунд 126: «+» добавляет не только заметку - файл и гифку с телефона,
    // гифку из внешнего каталога, любую свою гифку из библиотеки.
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.addLocalFile(uri) }
    // Раунд 164: свой стикер (картинка) и альбом .zip - в библиотеку.
    var stickerTick by remember { mutableStateOf(0) }
    val stickerPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.addStickerFromUri(uri) { stickerTick += 1 }
    }
    val stickerZipPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.addStickersFromZip(uri) { stickerTick += 1 }
    }
    val gifPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.addOwnGif(uri) }

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
    val fileFallbackName = stringResource(R.string.sv_file_lc)
    LaunchedEffect(uiState.pendingLocalExport) {
        uiState.pendingLocalExport?.let { exportPicker.launch(it.fileName.ifBlank { fileFallbackName }) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        // Раунд 266 (как в чате): клавиатура не закрывает поле ввода - список
        // сжимается над ней, панель ввода остаётся видимой.
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = Color.Transparent,
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Пишем в избранное как в чате: поле и кнопка «Отправить».
                    SavedInputBar(
                        text = draft,
                        onTextChange = { draft = it },
                        onSend = {
                            viewModel.addNote(draft)
                            draft = ""
                        },
                        onFocusChange = { focused -> inputFocused = focused },
                    )
                    // Панель разделов не мешает набору: пока печатаешь, она
                    // уходит, освобождая место под клавиатурой.
                    if (!inputFocused) bottomBar()
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                    ),
                    title = { Text(stringResource(R.string.chat_saved), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
            floatingActionButton = {
                ApuPremiumFloatingActionButton(
                    onClick = { showAddMenu = true },
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.mtproxy_add_btn),
                )
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
                            Text(stringResource(R.string.sv_empty), style = MaterialTheme.typography.titleMedium)
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

                else -> {
                    // Бегунок справа: видно, где мы в длинном списке.
                    val scrollState = rememberLazyListState()
                    // Раунд 187: закреплённое - карточкой над списком.
                    val scope = rememberCoroutineScope()
                    val pinnedItems = uiState.items.filter { it.isPinned }
                    Box(modifier = Modifier.fillMaxSize()) {
                    // Раунд 188: панель закрепа ПОСТОЯННО видна над списком -
                    // раньше уезжала вместе с лентой (владелец: «закрепы всегда
                    // видны в верхней части экрана»).
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                        if (pinnedItems.isNotEmpty()) {
                            SavedPinnedBar(
                                pinned = pinnedItems,
                                onTap = { id ->
                                    val idx = uiState.items.indexOfFirst { it.id == id }
                                    if (idx >= 0) scope.launch {
                                        scrollState.animateScrollToItem(idx)
                                    }
                                },
                                onUnpin = { viewModel.togglePin(it) },
                            )
                        }
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        // Снизу больше места: круглая кнопка «+» висит поверх
                        // списка и накрывала «Поделиться» и текст у последней
                        // записи - как раньше в ленте канала.
                        contentPadding = PaddingValues(
                            start = 2.dp,
                            end = 2.dp,
                            top = 8.dp,
                            bottom = 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(uiState.items, key = { it.id }) { item ->
                            SavedItemBubble(
                                item = item,
                                previewProvider = { viewModel.previewFor(item) },
                                onShare = { viewModel.share(item) },
                                onExport = { viewModel.requestExport(item) },
                                onDelete = { confirmDelete = item },
                                onTogglePin = { viewModel.togglePin(item) },
                                onOpenOrigin = if (item.originId.isNotBlank()) {
                                    { onOpenOrigin(item) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    }
                    ApuScrollbar(state = scrollState)
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

    if (showAddMenu) {
        // Раунд 163: действия - золотыми пузырями друг под другом
        // (владелец: «красивые пузыри горизонтальные в нашем стиле»).
        ApuSettingsDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text(stringResource(R.string.sv_add)) },
            text = {
                Column {
                    SavedAddBubble(stringResource(R.string.sv_file_phone)) {
                        showAddMenu = false
                        docPicker.launch(arrayOf("*/*"))
                    }
                    Spacer(Modifier.height(8.dp))
                    SavedAddBubble(stringResource(R.string.sv_gif_phone)) {
                        showAddMenu = false
                        gifPicker.launch("image/gif")
                    }
                    Spacer(Modifier.height(8.dp))
                    SavedAddBubble(stringResource(R.string.sv_gif_catalog)) {
                        showAddMenu = false
                        viewModel.onGifCatalogOpened()
                        showGifCatalog = true
                    }
                    Spacer(Modifier.height(8.dp))
                    SavedAddBubble(stringResource(R.string.sv_gif_own)) {
                        showAddMenu = false
                        viewModel.onGifCatalogOpened()
                        viewModel.setGifTab("swarm")
                        showGifCatalog = true
                    }
                    Spacer(Modifier.height(8.dp))
                    // Раунд 163: стикеры из библиотеки - в избранное.
                    SavedAddBubble(stringResource(R.string.sv_stickers)) {
                        showAddMenu = false
                        showStickerPicker = true
                    }
                    Spacer(Modifier.height(8.dp))
                    SavedAddBubble(stringResource(R.string.sv_note)) {
                        showAddMenu = false
                        showNoteDialog = true
                    }
                }
            },
            confirmButton = {
                ApuTextAction(label = stringResource(R.string.action_close), onClick = { showAddMenu = false })
            },
        )
    }

    // Раунд 163: выбор стикера из библиотеки - добавить в избранное.
    if (showStickerPicker) {
        var stickers by remember { mutableStateOf<List<com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry>?>(null) }
        // Раунд 164: после добавления своих стикеров сетка обновляется.
        LaunchedEffect(stickerTick) {
            viewModel.stickersOnce { stickers = it }
        }
        ApuSettingsDialog(
            onDismissRequest = { showStickerPicker = false },
            title = { Text(stringResource(R.string.sv_pick_sticker)) },
            text = {
                Column {
                    // Раунд 164: добавить свои - по одному или альбомом .zip.
                    SavedAddBubble(stringResource(R.string.sv_add_sticker)) {
                        stickerPicker.launch("image/*")
                    }
                    Spacer(Modifier.height(6.dp))
                    SavedAddBubble(stringResource(R.string.sv_add_album)) {
                        stickerZipPicker.launch("*/*")
                    }
                    Spacer(Modifier.height(10.dp))
                    val list = stickers
                    when {
                        list == null -> Text(stringResource(R.string.action_loading))
                        list.isEmpty() -> Text(
                            stringResource(R.string.sv_lib_empty)
                        )
                    else -> {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(list.size) { i ->
                                val entry = list[i]
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.85f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            RoundedCornerShape(14.dp),
                                        )
                                        .clickable {
                                            showStickerPicker = false
                                            viewModel.addSticker(entry)
                                        }
                                        .height(86.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    coil.compose.AsyncImage(
                                        model = entry.file,
                                        contentDescription = entry.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(6.dp),
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_close), onClick = { showStickerPicker = false })
            },
        )
    }

    if (showGifCatalog) {
        com.vladimir.messenger.ui.components.GifCatalogDialog(
            tab = uiState.gifTab,
            onTab = { viewModel.setGifTab(it) },
            myGifs = uiState.myGifs,
            swarmGifs = emptyList(),
            swarmStatus = null,
            items = uiState.gifItems,
            next = uiState.gifNext,
            loading = uiState.gifLoading,
            error = uiState.gifError,
            notice = uiState.gifNotice,
            onSearch = { viewModel.searchGifs(it) },
            onMore = { viewModel.searchGifs("", more = true) },
            onAttach = { item ->
                showGifCatalog = false
                viewModel.addCatalogGif(item)
            },
            onAttachLocal = { entry ->
                showGifCatalog = false
                viewModel.addLibraryGif(entry)
            },
            onRequestSwarm = { },
            onAddOwnGif = { uri -> viewModel.addOwnGif(uri) },
            onDismiss = {
                showGifCatalog = false
                viewModel.closeGifCatalog()
            },
        )
    }

    confirmDelete?.let { item ->
        ApuSettingsDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.sv_remove_q)) },
            text = {
                Text(
                    if (item.kind == SavedItemsRepository.KIND_FILE) {
                        // Важно объяснить: файл останется в чате, пропадёт только ссылка.
                        stringResource(R.string.sv_remove_body)
                    } else {
                        stringResource(R.string.sv_remove_short)
                    }
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.channel_remove),
                    onClick = {
                    viewModel.delete(item.id)
                    confirmDelete = null
                },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { confirmDelete = null })
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
    /** Есть куда вернуться - показываем «Перейти к оригиналу». */
    onOpenOrigin: (() -> Unit)? = null,
    /** Раунд 187: закрепить/открепить запись. */
    onTogglePin: () -> Unit = {},
) {
    val time = remember(item.savedAtMs) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.savedAtMs))
    }
    val isFile = item.kind == SavedItemsRepository.KIND_FILE
    // Раунд 210: «три точки» записи - все действия в одном выпадающем меню.
    var showRowMenu by remember { mutableStateOf(false) }
    // Раунд 169: сохранённый стикер - парит без пузыря, анимированной
    // картинкой (как в чатах).
    val isStickerItem = isFile && (
        item.mediaType.equals("image/webp", ignoreCase = true) ||
            item.mediaType.equals("video/webm", ignoreCase = true) ||
            item.fileName.lowercase().endsWith(".webp") ||
            item.fileName.lowercase().endsWith(".webm") ||
            item.fileName.startsWith(stringResource(R.string.sv_sticker))
        )

    ApuBubble(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        transparent = isStickerItem,
    ) {
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
            // Раунд 126/169: гифка и стикер в избранном ЖИВУТ - крутятся
            // анимацией (объявлено до блока просмотра - он на них ссылается).
            val isGifItem = item.mediaType.equals("image/gif", ignoreCase = true) ||
                item.fileName.lowercase().endsWith(".gif")
            val isLiveItem = isGifItem || isStickerItem
            var showFull by remember(previewPath) { mutableStateOf(false) }
            if (showFull && previewPath != null) {
                // Раунд 172: живые стикеры/гифки увеличиваются анимированными.
                if (isLiveItem) {
                    com.vladimir.messenger.ui.components.StickerViewer(
                        file = java.io.File(previewPath),
                        onDismiss = { showFull = false },
                    )
                } else {
                    com.vladimir.messenger.ui.components.PhotoViewer(
                        photos = listOf(com.vladimir.messenger.ui.components.PhotoSource.File(previewPath)),
                        onDismiss = { showFull = false },
                    )
                }
            }
            if (isLiveItem && previewPath != null) {
                // Раунд 170: гифки/webp крутит Coil, webm-стикеры покадрово.
                com.vladimir.messenger.ui.components.StickerAnimated(
                    file = java.io.File(previewPath),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showFull = true },
                )
            } else if (shownBitmap != null) {
                Image(
                    bitmap = shownBitmap.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showFull = true },
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
            // Пост канала с фотографиями: снимки идут первыми, как в ленте.
            // Нажатие - на весь экран с увеличением.
            val photos = remember(item.id, item.photos) { item.photoList() }
            var viewerIndex by remember(item.id) { mutableStateOf<Int?>(null) }
            viewerIndex?.let { start ->
                com.vladimir.messenger.ui.components.PhotoViewer(
                    photos = photos.map { com.vladimir.messenger.ui.components.PhotoSource.Encoded(it) },
                    initialIndex = start,
                    onDismiss = { viewerIndex = null },
                )
            }
            photos.forEachIndexed { index, b64 ->
                val bitmap = com.vladimir.messenger.ui.components.AvatarBitmaps.rememberAvatar(b64)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Фото ${index + 1} из ${photos.size}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { viewerIndex = index },
                    )
                }
            }
            if (item.text.isNotBlank()) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = ApuBubbleMutedColor,
                modifier = Modifier.weight(1f),
            )
            // Раунд 210: «три точки» записи - то же выпадающее меню, что у
            // сообщений в чатах (владелец: меню должно быть и в избранном).
            // Пункты - уже существующие действия, ничего нового не заводим.
            Box {
                IconButton(onClick = { showRowMenu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.sv_actions),
                        modifier = Modifier.size(18.dp),
                        tint = ApuBubbleMutedColor,
                    )
                }
                // Раунд 211: пункты - золотыми пузырями (общий стиль точек).
                ApuActionsMenu(
                    expanded = showRowMenu,
                    onDismiss = { showRowMenu = false },
                    actions = buildList {
                        if (isFile) {
                            add(ApuAction(stringResource(R.string.sv_save_phone), Icons.Filled.Download) { onExport() })
                        }
                        add(ApuAction(stringResource(R.string.admin_share), Icons.Filled.Share) { onShare() })
                        if (onOpenOrigin != null) {
                            add(ApuAction(stringResource(R.string.sv_goto_original), Icons.Filled.OpenInNew) { onOpenOrigin?.invoke() })
                        }
                        add(ApuAction(if (item.isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin), Icons.Filled.PushPin) { onTogglePin() })
                        add(ApuAction(stringResource(R.string.sv_unsave), Icons.Filled.Delete, destructive = true) { onDelete() })
                    },
                )
            }
            if (!isFile && item.photos.isNotBlank()) {
                // Репост сохранённого поста дальше - вместе с фотографиями.
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.admin_share),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (isFile) {
                IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.sv_save_phone),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.admin_share),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onOpenOrigin != null) {
                IconButton(onClick = onOpenOrigin, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.sv_goto_original),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // Раунд 187: закрепить/открепить; золотая булавка = закреплено.
            IconButton(onClick = onTogglePin, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (item.isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
                    modifier = Modifier.size(18.dp),
                    tint = if (item.isPinned) MaterialTheme.colorScheme.primary
                           else ApuBubbleMutedColor,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.sv_unsave),
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
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sv_note_self)) },
        text = {
            ApuBubbleField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.sv_text)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_save),
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f ГБ", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

/**
 * Раунд 163: пузырь-кнопка меню «Добавить в избранное» в гамме APU -
 * золотая заливка, белая жирная надпись (как пузыри приглашения).
 */
@Composable
private fun SavedAddBubble(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/**
 * Раунд 187: карточка закреплённого над списком «Избранного».
 * Тап по строке - лента прыгает к записи; крестик - открепить.
 */
@Composable
private fun SavedPinnedBar(
    pinned: List<SavedItemEntity>,
    onTap: (String) -> Unit,
    onUnpin: (SavedItemEntity) -> Unit,
) {
    if (pinned.isEmpty()) return
    ApuBubble(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.sv_pinned),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        pinned.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onTap(item.id) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = if (item.kind == SavedItemsRepository.KIND_FILE) {
                        item.fileName.ifBlank { stringResource(R.string.sv_file) }
                    } else {
                        item.text.replace("\n", " ").take(80)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onUnpin(item) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.menu_unpin),
                        modifier = Modifier.size(16.dp),
                        tint = ApuBubbleMutedColor,
                    )
                }
            }
        }
    }
}

/**
 * Нижняя панель ввода «Избранного»: пишем заметку прямо здесь, как сообщение в
 * чате (просьба владельца от 2026-10-06: «в избранное нужно писать как в чате с
 * кнопкой отправить; когда пишешь, чтобы клавиатура не перекрывала»).
 *
 * Сделана по образцу панели чата, а не как новая форма:
 * - тот же светлый пузырь с золотой рамкой ([apuBubbleSurface] на поле);
 * - поле с подсказкой и кнопка «Отправить» ПОД ним во всю ширину — активная
 *   залита золотом, неактивная светлая с серым текстом;
 * - `.imePadding()` на панели: клавиатура её не накрывает (в чате этот приём
 *   проверен на телефоне).
 *
 * Текст сохраняется тем же путём, что и «+» → «Заметка» ([SavedViewModel.addNote]
 * → `SavedItemsRepository.saveText`), поэтому пустое поле ничего не пишет.
 */
@Composable
private fun SavedInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Тот же приём, что в панели чата: BasicTextField(state) - он же проводит
    // вставку картинок со стикер-клавиатуры в contentReceiver, а не показывает
    // системный тост «приложение не поддерживает вставку изображений».
    val inputState = rememberTextFieldState(text)
    LaunchedEffect(text) {
        if (inputState.text.toString() != text) {
            inputState.edit { replace(0, length, text) }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { inputState.text.toString() }.collect { onTextChange(it) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 8.dp)
            // Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».
            // Панель ввода избранного — та же премиальная поверхность, что строки
            // списка и пузыри: подъём, единая подложка, золотая нить, блеск под
            // текстом (сам текст остаётся тёмными чернилами).
            .apuPremiumLift(6.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            state = inputState,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF1E2430)),
            cursorBrush = SolidColor(Color(0xFF1E2430)),
            decorator = object : TextFieldDecorator {
                @Composable
                override fun Decoration(content: @Composable () -> Unit) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .apuBubbleSurface(color = Color.White)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (inputState.text.isEmpty()) {
                            Text(
                                stringResource(R.string.sv_note_hint),
                                color = Color(0xFF5A6472),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        content()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> onFocusChange(state.isFocused) },
        )

        Spacer(Modifier.height(6.dp))

        // Кнопка читает активность прямо из inputState: как в чате, она
        // загорается сразу при первом символе, без задержки на рекомпозицию.
        val canSend = inputState.text.isNotBlank()
        ApuTextAction(
            label = stringResource(R.string.action_send),
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
