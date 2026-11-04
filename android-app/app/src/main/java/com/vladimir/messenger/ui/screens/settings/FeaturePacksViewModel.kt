package com.vladimir.messenger.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.featurepacks.FeaturePackSeeder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Состояния пакетов для раздела «Дополнительные функции»: что скачано,
 * что качается из роя или с официального сайта, что раздаётся соседям.
 * Логики здесь нет — только мост к [FeaturePackSeeder].
 */
@HiltViewModel
class FeaturePacksViewModel @Inject constructor(
    private val seeder: FeaturePackSeeder,
) : ViewModel() {

    val states: StateFlow<Map<String, FeaturePackSeeder.PackState>> = seeder.states

    fun request(packId: String) {
        viewModelScope.launch { seeder.request(packId) }
    }

    fun cancel(packId: String) {
        viewModelScope.launch { seeder.cancel(packId) }
    }
}
