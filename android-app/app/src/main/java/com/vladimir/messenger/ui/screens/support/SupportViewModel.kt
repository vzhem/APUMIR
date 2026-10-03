package com.vladimir.messenger.ui.screens.support

// =============================================================================
// SUPPORTVIEWMODEL.KT — состояние экрана «Поддержать разработчика»
// =============================================================================

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.support.SupportReminder
import com.vladimir.messenger.data.support.SupportWay
import com.vladimir.messenger.data.support.SupportWays
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние экрана поддержки. */
data class SupportUiState(
    /** Способы перевода (из сервиса). Пусто - черновое «список настраивается». */
    val ways: List<SupportWay> = emptyList(),
    /** true - способы грузятся (первая попытка). */
    val loading: Boolean = true,
    /** Напоминание: надо/не надо, периодичность, день. */
    val reminder: SupportReminder.State = SupportReminder.State(),
)

@HiltViewModel
class SupportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportUiState())
    val uiState: StateFlow<SupportUiState> = _uiState.asStateFlow()

    init {
        refresh()
        _uiState.value = _uiState.value.copy(reminder = SupportReminder.state(appContext))
    }

    /** Свежие способы перевода (сеть -> кэш -> пусто). */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val ways = SupportWays.load(appContext, force)
            _uiState.value = _uiState.value.copy(ways = ways, loading = false)
        }
    }

    /** Надо/не надо напоминать. */
    fun setReminderEnabled(enabled: Boolean) {
        updateReminder { it.copy(enabled = enabled) }
    }

    /** Периодичность: 1 или 3 месяца. */
    fun setReminderPeriod(months: Int) {
        updateReminder { it.copy(periodMonths = months.coerceIn(1, 3)) }
    }

    /** День месяца (1..28): 29-31 нет, чтобы ни один месяц не пропускался. */
    fun setReminderDay(day: Int) {
        updateReminder { it.copy(day = day.coerceIn(1, 28)) }
    }

    /** «Показать пример напоминания» - сразу показать, как выглядит. */
    fun sendTestNotification() {
        SupportReminder.sendTestNotification(appContext)
    }

    private fun updateReminder(change: (SupportReminder.State) -> SupportReminder.State) {
        val next = change(_uiState.value.reminder)
        SupportReminder.save(appContext, next)
        _uiState.value = _uiState.value.copy(reminder = next)
    }
}
