package com.vladimir.messenger.ui.screens.mtproxy

import com.vladimir.messenger.R
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.service.MtProxyHealthChecker
import com.vladimir.messenger.service.TelegramChannelScraper
import com.vladimir.messenger.domain.model.MtProtoProxy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MtProxyUiState(
    val proxies: List<MtProtoProxy> = emptyList(),
    /** Раунд 196: всего прокси в пуле (список показывает не больше DISPLAY_LIMIT). */
    val totalInPool: Int = 0,
    val isLoading: Boolean = true,
    val isChecking: Boolean = false,
    val isCollecting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class MtProxyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MtProxyRepository,
    private val healthChecker: MtProxyHealthChecker,
    private val scraper: TelegramChannelScraper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MtProxyUiState())
    val uiState: StateFlow<MtProxyUiState> = _uiState.asStateFlow()

    /** Раунд 196: сколько прокси показываем на экране (остальные в пуле). */
    private companion object {
        const val DISPLAY_LIMIT = 500
    }

    init {
        viewModelScope.launch {
            repo.observeAll()
                .catch { e -> Log.e("MtProxyVM", "Error observing proxies", e) }
                .collect { proxies ->
                    _uiState.update {
                        // Раунд 196 (владелец): выводимый список - не больше
                        // 500 лучших (сортировка в DAO). Пул целиком нужен
                        // автопилоту, а не экрану: слабые телефоны не
                        // рисуют тысячи карточек.
                        it.copy(
                            proxies = proxies.take(DISPLAY_LIMIT),
                            totalInPool = proxies.size,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun addProxy(input: String) {
        if (!manualProxyEntitled()) {
            _uiState.update { it.copy(message = context.getString(R.string.mp_manual_rank)) }
            return
        }
        viewModelScope.launch {
            val id = repo.addFromString(input, source = "MANUAL")
            if (id != null) {
                _uiState.update { it.copy(message = context.getString(R.string.mp_added)) }
            } else {
                _uiState.update { it.copy(message = context.getString(R.string.mp_bad_format)) }
            }
        }
    }

    fun importFromClipboard(clipboardText: String?) {
        if (clipboardText.isNullOrBlank()) {
            _uiState.update {
                it.copy(message = context.getString(R.string.mp_clipboard_empty))
            }
            return
        }

        viewModelScope.launch {
            val count = repo.importMultiple(clipboardText, source = "IMPORT")
            if (count > 0) {
                _uiState.update { it.copy(message = context.getString(R.string.mp_imported, count)) }
            } else {
                _uiState.update {
                    it.copy(message = context.getString(R.string.mp_clipboard_none))
                }
            }
        }
    }

    /**
     * Безопасно получить текст из буфера обмена.
     * Возвращает null если доступ запрещён или буфер пуст.
     */
    fun getClipboardText(clipboardManager: androidx.compose.ui.platform.ClipboardManager): String? {
        return try {
            clipboardManager.getText()?.text
        } catch (e: SecurityException) {
            android.util.Log.w("MtProxyVM", "Clipboard access denied", e)
            null
        } catch (e: Exception) {
            android.util.Log.w("MtProxyVM", "Clipboard error", e)
            null
        }
    }

    fun setActive(id: String) {
        if (!manualProxyEntitled()) {
            _uiState.update { it.copy(message = context.getString(R.string.mp_manual_rank)) }
            return
        }
        viewModelScope.launch {
            repo.setActive(id)
            _uiState.update { it.copy(message = context.getString(R.string.mp_selected)) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            _uiState.update { it.copy(message = context.getString(R.string.mp_deleted)) }
        }
    }

    /** Раунд 196: кнопка «удалить нерабочие» - сразу все «✗ Нерабочий». */
    fun cleanupDead() {
        viewModelScope.launch {
            val deleted = repo.cleanupDeadNow()
            _uiState.update {
                it.copy(
                    message = if (deleted > 0) {
                        "Удалено нерабочих: $deleted (добавленные вручную не тронуты)"
                    } else {
                        context.getString(R.string.mpv_none_found)
                    }
                )
            }
        }
    }


    fun checkOne(proxy: MtProtoProxy) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val result = healthChecker.checkOne(proxy)
            val msg = if (result.success) {
                "OK: ${proxy.host}:${proxy.port} (${result.latencyMs}ms)"
            } else {
                "FAIL: ${proxy.host}:${proxy.port} — ${result.error}"
            }
            _uiState.update { it.copy(isChecking = false, message = msg) }
        }
    }

    fun checkAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val results = healthChecker.checkAll()
            val ok = results.count { it.success }
            _uiState.update {
                it.copy(
                    isChecking = false,
                    message = context.getString(R.string.mp_checked, ok, results.size)
                )
            }
        }
    }

    fun checkAllAndPickBest() {
        if (!automaticProxyEntitled()) {
            _uiState.update { it.copy(message = context.getString(R.string.mp_auto_rank)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val best = healthChecker.checkAllAndPickBest()
            val msg = if (best != null) {
                "Лучший прокси выбран: ${best.proxy.host}:${best.proxy.port} (${best.latencyMs}ms)"
            } else {
                context.getString(R.string.mpv_no_working)
            }
            _uiState.update { it.copy(isChecking = false, message = msg) }
        }
    }


    fun collectNow() {
        if (!automaticProxyEntitled()) {
            _uiState.update { it.copy(message = context.getString(R.string.mp_collect_rank)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isCollecting = true, message = context.getString(R.string.mp_collecting)) }
            try {
                val results = scraper.collectAll()
                val totalAdded = results.sumOf { it.added }
                val totalParsed = results.sumOf { it.parsed }

                _uiState.update { it.copy(message = context.getString(R.string.mp_found_added, totalParsed, totalAdded)) }

                // 1. Удалить старые (> 7 дней без success)
                val stale = repo.cleanupStale(7)
                // 2. Ограничить до 500
                val excess = repo.enforcePoolLimit(500)
                // 3. Healthcheck топ-50
                healthChecker.checkAll()
                // 4. Cleanup мёртвых
                val dead = repo.cleanupDead()

                val total = repo.getAll().size
                val msg = "Добавлено: $totalAdded. Удалено: ${stale}cтарых, ${excess}лишних, ${dead}мёртвых. В пуле: $total"
                _uiState.update { it.copy(isCollecting = false, message = msg) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCollecting = false, message = context.getString(R.string.mp_error, e.message))
                }
            }
        }
    }

    private fun automaticProxyEntitled(): Boolean =
        FileTransferRankPolicy.canUseAutomaticProxy(
            ReferralRankStore.qualifiedDirectCount(context)
        )

    private fun manualProxyEntitled(): Boolean =
        FileTransferRankPolicy.canUseManualProxy(
            ReferralRankStore.qualifiedDirectCount(context)
        )

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
