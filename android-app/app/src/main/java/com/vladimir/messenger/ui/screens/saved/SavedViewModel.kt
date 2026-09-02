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
)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repository: SavedItemsRepository,
    private val fileTransferDao: FileTransferDao,
    private val fileTransferRouter: FileTransferRouter,
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
            repository.delete(id)
            _uiState.update { it.copy(message = "Удалено из избранного") }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Картинка для плитки. Файл ищется по transferId: в самой записи избранного
     * пути нет, иначе она сломалась бы при переносе хранилища.
     */
    suspend fun previewFor(item: SavedItemEntity): java.io.File? {
        val transfer = transferOf(item) ?: return null
        return fileTransferRouter.previewFileFor(transfer)
    }

    /** Отдать файл системному меню «Поделиться». */
    fun share(item: SavedItemEntity) {
        viewModelScope.launch {
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

    /** Человек нажал «Сохранить в телефон» - спрашиваем, куда. */
    fun requestExport(item: SavedItemEntity) {
        viewModelScope.launch {
            val transfer = transferOf(item)
            if (transfer == null) {
                _uiState.update { it.copy(message = "Файл больше не доступен") }
                return@launch
            }
            _uiState.update { it.copy(pendingExport = transfer) }
        }
    }

    fun onExportTargetPicked(target: android.net.Uri?) {
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
        return runCatching { fileTransferDao.getTransfer(id) }.getOrNull()
    }
}
