package com.vladimir.messenger.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * A section is an in-place screen, not a navigation entry. Button, in-app
 * swipe and Android back must all close it before leaving Settings.
 */
@Composable
internal fun settingsBackAction(
    sectionOpen: Boolean,
    onCloseSection: () -> Unit,
    onLeave: () -> Unit,
): () -> Unit {
    val back = { if (sectionOpen) onCloseSection() else onLeave() }
    BackHandler(enabled = sectionOpen, onBack = back)
    return back
}
