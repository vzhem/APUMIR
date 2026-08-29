package com.vladimir.messenger.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Присланные аватары контактов: ownerId -> JPEG в base64. Заполняется из
 * роевого реестра (таблица avatars) при старте и обновляется, когда приходит
 * новый аватар. Экраны читают поток и показывают картинку вместо инициалов.
 */
object AvatarStore {
    private val _avatars = MutableStateFlow<Map<String, String>>(emptyMap())
    val avatars: StateFlow<Map<String, String>> = _avatars.asStateFlow()

    fun put(ownerId: String, dataB64: String) {
        _avatars.update { it + (ownerId to dataB64) }
    }

    fun putAll(entries: Map<String, String>) {
        _avatars.update { it + entries }
    }
}
