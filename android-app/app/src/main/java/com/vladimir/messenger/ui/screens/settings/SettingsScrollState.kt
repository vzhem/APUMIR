package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Список настроек: корневое меню помнит позицию скролла, а каждый раздел
 * открывается всегда с верха.
 *
 * Владелец 2026-11-08: «когда заходишь по меню — всегда появляется сверху.
 * А когда возвращаешься в главное меню — оставь запоминание, где были.
 * Ну как у топовых мессенджеров».
 *
 * Правило:
 *   - корневое меню (`ROOT`, `section == null`) помнит, где человек оставил
 *     список, и при возврате из любого раздела показывает ту же строку;
 *   - любой вложенный раздел («Чаты», «Уведомления» и остальные) при входе
 *     открывается с верхней позиции, его скролл не запоминается.
 *
 * Здесь только обвязка Compose; само правило «какое меню где стояло» живёт в
 * [SettingsScrollMemory] и проверяется обычным JVM-тестом.
 */
@Composable
internal fun rememberSettingsScrollState(menuKey: String): LazyListState {
    // У каждого входа в раздел новый список: данные и замер предыдущего списка
    // не могут преждевременно обрезать позицию при возврате в длинный корень.
    val state = key(menuKey) { rememberLazyListState() }
    val restored = remember(state) { mutableStateOf(false) }
    val isRoot = menuKey == SettingsScrollMemory.ROOT
    // Память живёт и пока открыт подраздел. Условный rememberSaveable раньше
    // удалял её из композиции при входе в раздел и терял корневую позицию.
    val memory = rememberSaveable(
        saver = listSaver<SettingsScrollMemory, Int>(
            save = { it.flatten() },
            restore = { SettingsScrollMemory.fromFlatten(it) },
        ),
    ) { SettingsScrollMemory() }
    if (isRoot) {
        DisposableEffect(state) {
            onDispose {
                // Последний кадр сохраняется до отмены наблюдателя, но не
                // заменяет прежнюю позицию, если восстановление ещё не началось.
                if (restored.value) {
                    memory.remember(menuKey, SettingsScrollMemory.Anchor(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset))
                }
            }
        }
    }

    LaunchedEffect(menuKey, state) {
        val anchor = if (isRoot) memory.anchorFor(menuKey) else SettingsScrollMemory.Anchor.TOP
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        state.scrollToItem(anchor.index, anchor.offset)
        restored.value = true
        // Сначала восстановить, потом наблюдать: иначе первый snapshotFlow
        // перезаписал бы сохранённую позицию начальной строкой нового списка.
        if (isRoot) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    memory.remember(menuKey, SettingsScrollMemory.Anchor(index, offset))
                }
        }
    }
    return state
}
