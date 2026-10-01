package com.vladimir.messenger.data.typing

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * р235: кто из собеседников сейчас «печатает…».
 *
 * Состояние живёт только в памяти и само гаснет: пакет «печатает» приходит
 * раз в пару секунд, пока человек набирает текст, а индикатор держится
 * [TTL_MS] после последнего пакета. Ничего не сохраняется в базу - это
 * мимолётное состояние, как и в любом мессенджере.
 */
object TypingPeer {

    /** Сколько держать индикатор после последнего пакета «печатает». */
    private const val TTL_MS = 6_000L

    private val _typing = MutableStateFlow<Set<String>>(emptySet())

    /** Идентификаторы собеседников, которые сейчас печатают. */
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()

    /** Когда от собеседника в последний раз приходил пакет «печатает». */
    private val lastSeen = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Пришёл пакет «печатает»: показать индикатор и продлить его жизнь. */
    fun peerTyping(peerId: String) {
        if (peerId.isBlank()) return
        lastSeen[peerId] = System.currentTimeMillis()
        _typing.value = _typing.value + peerId
        scope.launch {
            delay(TTL_MS + 200)
            // Гасим только если за это время не пришёл свежий пакет.
            val at = lastSeen[peerId] ?: return@launch
            if (System.currentTimeMillis() - at >= TTL_MS) {
                lastSeen.remove(peerId)
                _typing.value = _typing.value - peerId
            }
        }
    }

    /** Пришёл пакет «перестал» (или собеседник отправил сообщение). */
    fun peerStopped(peerId: String) {
        if (peerId.isBlank()) return
        lastSeen.remove(peerId)
        _typing.value = _typing.value - peerId
    }

    /** Печатает ли этот собеседник прямо сейчас. */
    fun isTyping(peerId: String): Boolean = _typing.value.contains(peerId)
}
