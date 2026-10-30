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
 * Список настроек, который помнит, где стоял каждый раздел.
 *
 * Владелец 2026-10-29: «когда возвращаешься в предыдущее меню, оно не всегда
 * должно быть в верхней части — в каком положении переходил, в такое же и
 * возвращалось». До этого на любую смену раздела стоял `scrollToItem(0)`, и
 * назад человек попадал в начало списка.
 *
 * Здесь только обвязка Compose; само правило «какое меню где стояло» живёт в
 * [SettingsScrollMemory] и проверяется обычным JVM-тестом.
 */
@Composable
internal fun rememberSettingsScrollState(menuKey: String): LazyListState {
    val state = rememberLazyListState()
    val memory = rememberSaveable(
        saver = listSaver<SettingsScrollMemory, Int>(
            save = { it.flatten() },
            restore = { SettingsScrollMemory.fromFlatten(it) },
        ),
    ) { SettingsScrollMemory() }
    val menu = menuKey

    // Позицию запоминаем по ходу прокрутки: тогда неважно, куда человек ушёл —
    // в раздел, в соседний экран навигации или в другой чат.
    LaunchedEffect(menu) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                memory.remember(menu, SettingsScrollMemory.Anchor(index, offset))
            }
    }
    // Смена меню — сразу на своё место: возврат туда, где человек был, а новый
    // раздел начинается сверху (сохранённой позиции у него ещё нет).
    LaunchedEffect(menu) {
        val anchor = memory.anchorFor(menu)
        // Ждём первый замер списка: до него scrollToItem обрезался бы о ещё не
        // собранные строки нового раздела.
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        state.scrollToItem(anchor.index, anchor.offset)
    }
    return state
}
