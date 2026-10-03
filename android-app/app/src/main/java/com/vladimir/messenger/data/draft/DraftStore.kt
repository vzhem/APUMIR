package com.vladimir.messenger.data.draft

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * р236: черновики сообщений - недописанный текст поля ввода.
 *
 * Ключ черновика выводится из УСТОЙЧИВОГО идентификатора (для личного чата -
 * адрес собеседника), поэтому на двух устройствах одной личности один и тот же
 * текст лежит под одним и тем же ключом - и его можно переносить зеркалом, не
 * заглядывая в базу (идентификаторы чатов у устройств СВОИ).
 *
 * Хранится в SharedPreferences: это не переписка, а незакрытая строка ввода.
 */
object DraftStore {

    private const val TAG = "DraftStore"
    private const val PREFS = "apu_drafts"

    /** Ключ черновика личного чата: адрес собеседника один на всех устройствах. */
    fun dmKey(contactId: String): String = "p:" + contactId

    /** Ключ черновика группы/канала: идентификатор группы общий. */
    fun groupKey(groupId: String): String = "g:" + groupId

    @Volatile private var prefs: SharedPreferences? = null

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Черновики: ключ -> текст (пустой текст означает «черновика нет»). */
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    /** Вызывается один раз при старте приложения. */
    fun attach(context: Context) {
        if (prefs != null) return
        runCatching {
            val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = store
            _drafts.value = store.all.entries
                .mapNotNull { (key, value) -> (value as? String)?.takeIf { it.isNotEmpty() }?.let { key to it } }
                .toMap()
            Log.i(TAG, "черновиков загружено: ${_drafts.value.size}")
        }.onFailure { Log.w(TAG, "черновики не загрузились: ${it.message}") }
    }

    fun load(key: String): String = _drafts.value[key].orEmpty()

    /** Сохранить черновик (пустой текст = удалить запись). */
    fun save(key: String, text: String) {
        if (key.isBlank()) return
        val current = _drafts.value[key].orEmpty()
        if (current == text) return
        val edit = prefs?.edit()
        if (text.isEmpty()) {
            edit?.remove(key)
            _drafts.value = _drafts.value - key
        } else {
            edit?.putString(key, text)
            _drafts.value = _drafts.value + (key to text)
        }
        runCatching { edit?.apply() }
    }

    fun clear(key: String) = save(key, "")
}
