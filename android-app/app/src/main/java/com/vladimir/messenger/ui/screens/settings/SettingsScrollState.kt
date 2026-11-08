package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val state = rememberLazyListState()
    val isRoot = menuKey == SettingsScrollMemory.ROOT
    val memory = if (isRoot) {
        rememberSaveable(
            saver = listSaver<SettingsScrollMemory, Int>(
                save = { it.flatten() },
                restore = { SettingsScrollMemory.fromFlatten(it) },
            ),
        ) { SettingsScrollMemory() }
    } else null
    val menu = menuKey

    // Позицию корневого меню запоминаем по ходу прокрутки: тогда при возврате
    // из любого раздела человек увидит ту же строку, где остановился.
    if (isRoot) {
        LaunchedEffect(menu) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    memory!!.remember(menu, SettingsScrollMemory.Anchor(index, offset))
                }
        }
    }

    // Смена меню — сразу на своё место: корневое — там, где оставили; вложенное
    // — с верха списка. Ждём первый замер списка: до него scrollToItem
    // обрезался бы о ещё не собранные строки нового раздела.
    LaunchedEffect(menu) {
        val anchor = if (isRoot) memory!!.anchorFor(menu) else SettingsScrollMemory.Anchor.TOP
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        state.scrollToItem(anchor.index, anchor.offset)
    }
    return state
}
