package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember

/**
 * Keep the root list's own saveable state alive while a section or a nested
 * route is open. Reusing one list state for different item sets allowed a
 * short section to clamp the root index to zero before it was saved.
 *
 * A section uses a separate, non-saveable state keyed by its name, so each
 * entry (including returning from a nested route) starts at the top. The root
 * state's built-in LazyListState saver also handles activity recreation.
 */
@Composable
internal fun rememberSettingsScrollState(menuKey: String): LazyListState {
    val root = rememberLazyListState()
    val section = key(menuKey) { remember { LazyListState() } }
    return if (menuKey == SettingsScrollMemory.ROOT) root else section
}
