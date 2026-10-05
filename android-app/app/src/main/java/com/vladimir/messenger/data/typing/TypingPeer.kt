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

    // ── р249: «печатает…» в группах и темах канала ───────────────────────────

    /**
     * Ключ группового индикатора: «группа|тема|узел».
     *
     * Тема в ключе обязательна: в канале с обсуждениями человек может печатать
     * в комментариях одного поста, и в ленте это показывать не нужно.
     */
    private val _groupTyping = MutableStateFlow<Set<String>>(emptySet())

    /** Кто сейчас печатает в группах (ключи [groupKey]). */
    val groupTyping: StateFlow<Set<String>> = _groupTyping.asStateFlow()

    /** Когда от этого узла в последний раз приходил групповой пакет. */
    private val groupLastSeen = ConcurrentHashMap<String, Long>()

    fun groupKey(groupId: String, topicId: String, senderId: String): String =
        "$groupId|$topicId|$senderId"

    /** Участник печатает в группе: показать индикатор и продлить его жизнь. */
    fun groupTyping(groupId: String, topicId: String, senderId: String, typing: Boolean) {
        if (groupId.isBlank() || senderId.isBlank()) return
        val key = groupKey(groupId, topicId, senderId)
        if (!typing) {
            groupLastSeen.remove(key)
            _groupTyping.value = _groupTyping.value - key
            return
        }
        groupLastSeen[key] = System.currentTimeMillis()
        _groupTyping.value = _groupTyping.value + key
        scope.launch {
            delay(TTL_MS + 200)
            val at = groupLastSeen[key] ?: return@launch
            if (System.currentTimeMillis() - at >= TTL_MS) {
                groupLastSeen.remove(key)
                _groupTyping.value = _groupTyping.value - key
            }
        }
    }

    /** Участник перестал печатать (или отправил сообщение). */
    fun groupStopped(groupId: String, topicId: String, senderId: String) {
        groupTyping(groupId, topicId, senderId, false)
    }

    /** Узлы, печатающие прямо сейчас в этой группе (или в этой теме группы). */
    fun typingMembers(groupId: String, topicId: String): Set<String> {
        if (groupId.isBlank()) return emptySet()
        val prefix = "$groupId|$topicId|"
        return _groupTyping.value
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .toSet()
    }
}
