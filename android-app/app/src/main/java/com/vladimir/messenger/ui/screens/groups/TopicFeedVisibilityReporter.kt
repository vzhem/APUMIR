package com.vladimir.messenger.ui.screens.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Сообщает модели, что человек видит на самом деле, и ничего не рисует.
 *
 * Без этого автоматом выбранная тема «прочитывалась» прямо из списка тем:
 * модель запускала ленту General и снимала непрочитанное, хотя внутрь никто не
 * заходил. Тогда гасли и тревожные сообщения SOS (владелец, 2026-10-30).
 *
 * Отдельный файл, а не строки в `GroupChatScreen`: тот на потолке god-файла.
 */
@Composable
internal fun TopicFeedVisibilityReporter(
    viewModel: GroupChatViewModel,
    /** Список тем на экране: тогда лента не видна и ничего не прочитывается. */
    topicsListOnScreen: Boolean,
    /** Тема открытой ленты; `null` — список тем. */
    topicId: String?,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(topicId, topicsListOnScreen, resumed) {
        viewModel.setFeedVisible(if (topicsListOnScreen || !resumed) null else topicId, resumed)
    }
    // Ушли с экрана — модель больше ничего не прочитывает.
    DisposableEffect(Unit) {
        onDispose { viewModel.setFeedVisible(null, resumed = false) }
    }
}
