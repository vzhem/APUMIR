package com.vladimir.messenger.data.folder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Папка чатов в стиле Telegram: вкладка поверх общего списка.
 *
 * [Rule.CUSTOM] — ручной набор чатов ([chatKeys]); [Rule.UNREAD] и
 * [Rule.PERSONAL] — автоматические «рекомендованные» папки из экрана
 * настроек: их состав считается из списка на лету, ключи не нужны.
 *
 * Ключ строки списка тот же, что у сортировки главного экрана:
 * `chat:<id>` для личного чата и `group:<id>` для группы/канала.
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val rule: Rule = Rule.CUSTOM,
    val chatKeys: Set<String> = emptySet(),
) {
    enum class Rule { CUSTOM, UNREAD, PERSONAL }

    companion object {
        fun personalKey(chatId: String): String = "chat:$chatId"
        fun groupKey(groupId: String): String = "group:$groupId"
    }
}

/**
 * Папки живут в SharedPreferences: это локальная настройка вида списка,
 * а не переписка, поэтому отдельная таблица ей не нужна. Порядок в списке
 * — порядок вкладок на главном экране.
 */
object ChatFolderStore {
    private const val PREFS = "chat_folders"
    private const val KEY = "folders_json"

    private val _folders = MutableStateFlow<List<ChatFolder>>(emptyList())
    val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()

    @Volatile
    private var loaded = false

    /** Первый экран, которому понадобились папки, поднимет их с диска. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            _folders.value = read(context)
        }
    }

    fun current(context: Context): List<ChatFolder> {
        load(context)
        return _folders.value
    }

    /** Полная замена списка: создание, правка, удаление и перетаскивание. */
    fun save(context: Context, folders: List<ChatFolder>) {
        _folders.value = folders
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("rule", folder.rule.name)
                    .put("keys", JSONArray(folder.chatKeys.toList())),
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    fun add(context: Context, folder: ChatFolder) =
        save(context, current(context) + folder)

    fun update(context: Context, folder: ChatFolder) =
        save(context, current(context).map { if (it.id == folder.id) folder else it })

    fun delete(context: Context, id: String) =
        save(context, current(context).filterNot { it.id == id })

    fun newId(): String = "folder_${System.currentTimeMillis().toString(36)}"

    private fun read(context: Context): List<ChatFolder> = try {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            .orEmpty()
        if (raw.isBlank()) {
            emptyList()
        } else {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val keys = item.optJSONArray("keys")
                    add(
                        ChatFolder(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            rule = runCatching {
                                ChatFolder.Rule.valueOf(item.optString("rule"))
                            }.getOrDefault(ChatFolder.Rule.CUSTOM),
                            chatKeys = buildSet {
                                if (keys != null) {
                                    for (j in 0 until keys.length()) add(keys.optString(j))
                                }
                            },
                        ),
                    )
                }
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
