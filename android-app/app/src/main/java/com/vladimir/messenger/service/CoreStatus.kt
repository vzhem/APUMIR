package com.vladimir.messenger.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Раунд 263: честный статус холодного старта ядра для интерфейса.
 * Сервис публикует этапы подъёма, сплэш и экраны показывают, что происходит,
 * вместо «пустого приложения, которое будто зависло». Объект живёт в процессе:
 * при тёплом старте (ядро уже поднято) заставка вообще не показывается.
 */
object CoreStatus {

    private val _stage = MutableStateFlow("Поднимаем ядро…")
    /** Текущий этап человеческими словами. */
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** true, когда ядро поднято (или честный ограниченный режим). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun report(text: String) {
        _stage.value = text
    }

    fun markReady() {
        _ready.value = true
    }
}
