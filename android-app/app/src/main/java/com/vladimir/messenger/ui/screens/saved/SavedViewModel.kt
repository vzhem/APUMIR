package com.vladimir.messenger.ui.screens.saved

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.SavedItemEntity
import com.vladimir.messenger.data.repository.SavedItemsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavedUiState(
    val items: List<SavedItemEntity> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
    /** Файл, для которого человек выбирает, куда выгрузить. */
    val pendingExport: FileTransferEntity? = null,
    /** Локально добавленный файл - выгрузка наружу без передачи. */
    val pendingLocalExport: SavedItemEntity? = null,
    // ── Окно гифок (раунд 126): каталог и мои гифки ──
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    val gifError: String? = null,
)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repository: SavedItemsRepository,
    private val fileTransferDao: FileTransferDao,
    private val fileTransferRouter: FileTransferRouter,
    private val botApi: com.vladimir.messenger.service.BotApi,
    private val chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedUiState())
    val uiState: StateFlow<SavedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    fun addNote(text: String) {
        viewModelScope.launch {
            repository.saveText(text)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val item = repository.get(id)
            repository.delete(id)
            // Своя копия файла больше не нужна - место освобождаем. Ссылки на
            // библиотеку гифок (local:gif) не трогаем: гифка живёт в сети.
            if (item?.transferId?.startsWith("local:doc:") == true) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        java.io.File(java.io.File(appContext.filesDir, "saved_files"), item.transferId.removePrefix("local:doc:"))
                            .delete()
                    }
                }
            }
            _uiState.update { it.copy(message = "Удалено из избранного") }
        }
    }

    private fun shareLocalFile(src: java.io.File, mediaType: String, displayName: String) {
        viewModelScope.launch {
            runCatching {
                val dir = java.io.File(appContext.cacheDir, "shared").apply { mkdirs() }
                val dst = java.io.File(dir, displayName.ifBlank { src.name })
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { src.copyTo(dst, overwrite = true) }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext, appContext.packageName + ".fileprovider", dst,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mediaType)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(
                    android.content.Intent.createChooser(intent, "Поделиться")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                _uiState.update { st -> st.copy(message = "Не удалось поделиться") }
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Картинка для плитки. Файл ищется по transferId: в самой записи избранного
     * пути нет, иначе она сломалась бы при переносе хранилища.
     */
    suspend fun previewFor(item: SavedItemEntity): java.io.File? {
        if (item.transferId?.startsWith("local:") == true) return localFileOf(item)
        val transfer = transferOf(item) ?: return null
        return fileTransferRouter.previewFileFor(transfer)
    }

    /** Отдать файл (или сохранённый пост с фотографиями) системному меню «Поделиться». */
    fun share(item: SavedItemEntity) {
        if (item.kind != SavedItemsRepository.KIND_FILE) {
            sharePost(item)
            return
        }
        viewModelScope.launch {
            val localSrc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localFileOf(item) }
            if (localSrc != null) {
                shareLocalFile(localSrc, item.mediaType.ifBlank { "application/octet-stream" }, item.fileName)
                return@launch
            }
            val transfer = transferOf(item)
            if (transfer == null) {
                _uiState.update { it.copy(message = "Файл больше не доступен") }
                return@launch
            }
            val src = fileTransferRouter.receivedFileFor(transfer)
            if (src == null) {
                _uiState.update { it.copy(message = "Файл больше не доступен") }
                return@launch
            }
            runCatching {
                val dir = java.io.File(appContext.cacheDir, "shared").apply { mkdirs() }
                val dst = java.io.File(dir, transfer.displayName)
                src.copyTo(dst, overwrite = true)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext, appContext.packageName + ".fileprovider", dst,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(transfer.mediaType)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(
                    android.content.Intent.createChooser(intent, "Поделиться")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                _uiState.update { st -> st.copy(message = "Не удалось поделиться") }
            }
        }
    }

    /**
     * Сохранённый пост канала одним сообщением: картинка (одно фото или сетка
     * из всех) с текстом подписью; без фото - просто текст. Ссылки на пост у
     * записи нет, поэтому подпись - только текст (см. PhotoShare).
     */
    private fun sharePost(item: SavedItemEntity) {
        viewModelScope.launch {
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.vladimir.messenger.util.PhotoShare.writeShareImage(appContext, item.photoList(), item.id)
                }.getOrNull()
            }
            com.vladimir.messenger.util.PhotoShare.copyToClipboard(appContext, "Пост APU", item.text)
            val intent = when {
                uri != null -> com.vladimir.messenger.util.PhotoShare.buildImageIntent(
                    uri,
                    com.vladimir.messenger.util.PhotoShare.captionFor(item.text, link = null),
                )
                item.text.isNotBlank() -> com.vladimir.messenger.util.PhotoShare.buildTextIntent(item.text)
                else -> {
                    _uiState.update { st -> st.copy(message = "Нечем поделиться") }
                    return@launch
                }
            }
            if (!com.vladimir.messenger.util.PhotoShare.open(appContext, intent, "Поделиться")) {
                _uiState.update { st -> st.copy(message = "Не удалось поделиться") }
            }
        }
    }

    /** Человек нажал «Сохранить в телефон» - спрашиваем, куда. */
    fun requestExport(item: SavedItemEntity) {
        viewModelScope.launch {
            if (item.transferId?.startsWith("local:") == true) {
                if (localFileOf(item) == null) {
                    _uiState.update { it.copy(message = "Файл больше не доступен") }
                } else {
                    _uiState.update { it.copy(pendingLocalExport = item) }
                }
                return@launch
            }
            val transfer = transferOf(item)
            if (transfer == null) {
                _uiState.update { it.copy(message = "Файл больше не доступен") }
                return@launch
            }
            _uiState.update { it.copy(pendingExport = transfer) }
        }
    }

    fun onExportTargetPicked(target: android.net.Uri?) {
        val localItem = _uiState.value.pendingLocalExport
        if (localItem != null) {
            _uiState.update { it.copy(pendingLocalExport = null) }
            if (target == null) return
            viewModelScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val src = localFileOf(localItem) ?: return@withContext false
                        appContext.contentResolver.openOutputStream(target)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        } ?: false
                        true
                    }.getOrDefault(false)
                }
                _uiState.update {
                    it.copy(message = if (ok) "Сохранено: " + localItem.fileName else "Не удалось сохранить файл")
                }
            }
            return
        }
        val transfer = _uiState.value.pendingExport
        _uiState.update { it.copy(pendingExport = null) }
        if (target == null || transfer == null) return
        viewModelScope.launch {
            val ok = runCatching { fileTransferRouter.exportReceivedFile(transfer, target) }
                .getOrDefault(false)
            _uiState.update {
                it.copy(
                    message = if (ok) {
                        "Сохранено: " + transfer.displayName
                    } else {
                        "Не удалось сохранить файл"
                    },
                )
            }
        }
    }

    private suspend fun transferOf(item: SavedItemEntity): FileTransferEntity? {
        val id = item.transferId ?: return null
        if (id.startsWith("local:")) return null
        return runCatching { fileTransferDao.getTransfer(id) }.getOrNull()
    }

    // ── Раунд 126: добавляем в избранное сами ───────────────────────────

    /** Файл локальной записи: «local:gif:<sha>» - из библиотеки гифок, «local:doc:<имя>» - своя копия. */
    private fun localFileOf(item: SavedItemEntity): java.io.File? {
        val ref = item.transferId ?: return null
        return when {
            ref.startsWith("local:gif:") ->
                com.vladimir.messenger.data.gif.GifLibrary.gifFile(appContext, ref.removePrefix("local:gif:"))
                    ?.takeIf { it.isFile }
            ref.startsWith("local:doc:") ->
                java.io.File(java.io.File(appContext.filesDir, "saved_files"), ref.removePrefix("local:doc:"))
                    .takeIf { it.isFile }
            else -> null
        }
    }

    /** Файл, который человек выбрал в хранилище телефона: копия в saved_files. */
    fun addLocalFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val resolver = appContext.contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext null
                    if (bytes.isEmpty() || bytes.size > 200L * 1024 * 1024) return@withContext null
                    val name = queryDisplayName(resolver, uri) ?: "файл_${System.currentTimeMillis() / 1000}"
                    val dir = java.io.File(appContext.filesDir, "saved_files").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
                    val disk = java.io.File(dir, java.util.UUID.randomUUID().toString().take(8) + "_" + safe)
                    disk.writeBytes(bytes)
                    val mediaType = resolver.getType(uri) ?: "application/octet-stream"
                    val result = repository.saveLocalFile(
                        fileName = name,
                        mediaType = mediaType,
                        sizeBytes = bytes.size.toLong(),
                        storageRef = "local:doc:" + disk.name,
                        sourceTitle = "С телефона",
                    )
                    if (result == com.vladimir.messenger.data.repository.SaveResult.Saved) name else null
                }.getOrNull()
            }
            _uiState.update {
                it.copy(
                    message = when {
                        saved != null -> "Добавлено в избранное: $saved"
                        else -> "Не удалось добавить файл"
                    },
                )
            }
        }
    }

    /** Гифка из хранилища телефона: в библиотеку (и в каталог сети) + в избранное. */
    fun addOwnGif(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext false
                    if (bytes.isEmpty() || bytes.size > 30 * 1024 * 1024) return@withContext false
                    val name = queryDisplayName(appContext.contentResolver, uri)
                        ?: "своя_${System.currentTimeMillis() / 1000}.gif"
                    val added = com.vladimir.messenger.data.gif.GifLibrary.add(
                        appContext, bytes, null, "своя", name,
                    ) ?: return@withContext false
                    favoriteLibraryGif(added, "С телефона")
                }.getOrDefault(false)
            }
            _uiState.update {
                it.copy(message = if (ok) "Гифка добавлена - в избранном и в нашей сети" else "Не вышло: нужен файл GIF до 30 МБ")
            }
        }
    }

    /** Гифка из внешнего каталога: скачиваем, селим в библиотеку, в избранное. */
    fun addCatalogGif(item: com.vladimir.messenger.data.gif.GifItem) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    // Уже в библиотеке (качали раньше) - просто в избранное.
                    val sha = com.vladimir.messenger.data.gif.GifLibrary.shaForGiphyId(appContext, item.id)
                    val entry = if (sha != null) {
                        com.vladimir.messenger.data.gif.GifLibrary.bySha(appContext, sha)
                    } else {
                        val bytes = botApi.downloadGif(item.gif) ?: return@runCatching false
                        com.vladimir.messenger.data.gif.GifLibrary.add(appContext, bytes, item.id, "каталог", null)
                    } ?: return@runCatching false
                    favoriteLibraryGif(entry, "Из каталога")
                }.getOrDefault(false)
            }
            _uiState.update {
                it.copy(message = if (ok) "Гифка добавлена в избранное" else "Не удалось скачать гифку")
            }
        }
    }

    /** Моя гифка (уже лежит в библиотеке) - в избранное. */
    fun addLibraryGif(entry: com.vladimir.messenger.data.gif.GifLibEntry) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { favoriteLibraryGif(entry, "Мои гифки") }.getOrDefault(false)
            }
            _uiState.update {
                it.copy(message = if (ok) "Уже в избранном" else "Не удалось добавить")
            }
        }
    }

    private suspend fun favoriteLibraryGif(
        entry: com.vladimir.messenger.data.gif.GifLibEntry,
        source: String,
    ): Boolean {
        val result = repository.saveLocalFile(
            fileName = entry.displayName.ifBlank { "gif_${entry.sha256.take(8)}.gif" },
            mediaType = "image/gif",
            sizeBytes = entry.sizeBytes,
            storageRef = "local:gif:" + entry.sha256,
            sourceTitle = source,
        )
        if (result == com.vladimir.messenger.data.repository.SaveResult.Saved) {
            // Заодно гифка объявляется в каталоге сети.
            runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(appContext, chatRepository, force = false)
            }
            return true
        }
        return result == com.vladimir.messenger.data.repository.SaveResult.AlreadySaved
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()

    // ── Окно гифок: поиск и мои гифки ────────────────────────────────────

    fun onGifCatalogOpened() {
        viewModelScope.launch {
            val my = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.entries(appContext)
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(myGifs = my) }
        }
    }

    fun setGifTab(tab: String) {
        _uiState.update { it.copy(gifTab = tab) }
    }

    fun searchGifs(query: String, more: Boolean = false) {
        if (_uiState.value.gifLoading) return
        _uiState.update {
            it.copy(
                gifLoading = true,
                gifError = null,
                gifItems = if (more) it.gifItems else emptyList(),
            )
        }
        viewModelScope.launch {
            val result = runCatching { botApi.gifSearch(query, if (more) _uiState.value.gifNext else "") }
                .getOrNull()
            _uiState.update { state ->
                if (result == null) {
                    state.copy(
                        gifLoading = false,
                        gifError = "Каталог недоступен: сервер не отвечает или ключ GIF ещё не настроен",
                    )
                } else {
                    val (items, next) = result
                    if (items.isEmpty() && state.gifItems.isEmpty()) {
                        state.copy(gifLoading = false, gifError = "Ничего не нашлось")
                    } else {
                        state.copy(
                            gifLoading = false,
                            gifError = null,
                            gifItems = (state.gifItems + items).distinctBy { it.id },
                            gifNext = next,
                        )
                    }
                }
            }
        }
    }

    fun closeGifCatalog() {
        _uiState.update { it.copy(gifItems = emptyList(), gifNext = "", gifError = null) }
    }
}
