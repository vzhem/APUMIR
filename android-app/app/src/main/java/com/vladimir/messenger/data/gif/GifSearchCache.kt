package com.vladimir.messenger.data.gif

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Раунд 192: локальный кэш ответов внешнего каталога (/gif/search).
 *
 * Зачем: relay-воркер упёрся в дневной лимит облака (р191) и вечером
 * каталог гиф отвечал «сервер перегружен». Поиск гиф - единственное,
 * что ходит на наш сервер; превью и скачивание байт идут с телефона
 * напрямую на CDN Giphy. Поэтому телефон, хоть раз открывший каталог,
 * при недоступном сервере показывает сохранённые результаты вместо
 * ошибки - поиск, скачивание и отправка продолжают работать.
 *
 * Формат файла (noBackupFilesDir/gif/search_cache.v1): первая строка
 * APUGIFSRCH1, дальше по одной записи-строке JSON на запрос:
 * {"q":…,"at":…,"next":…,"items":[{"id","preview","gif"}]}.
 * Храним до 24 последних запросов (включая тренд - пустой q),
 * по 24 гифки в каждом. Запись атомарная (tmp+rename), как в GifLibrary.
 */
object GifSearchCache {

    private const val TAG = "GifSearchCache"
    private const val MAGIC = "APUGIFSRCH1"
    private const val MAX_ENTRIES = 24
    private const val MAX_ITEMS = 24

    private fun file(context: Context): File {
        val dir = File(context.noBackupFilesDir, "gif")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "search_cache.v1")
    }

    /** Сохранить страницу результатов запроса (вызывать при успехе). */
    suspend fun save(context: Context, query: String, items: List<GifItem>, next: String) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val f = file(context)
                val key = query.trim().lowercase().take(64)
                val entry = JSONObject()
                    .put("q", key)
                    .put("at", System.currentTimeMillis())
                    .put("next", next.take(64))
                val arr = JSONArray()
                for (g in items.take(MAX_ITEMS)) {
                    if (g.id.isBlank() || g.preview.isBlank() || g.gif.isBlank()) continue
                    arr.put(JSONObject().put("id", g.id).put("preview", g.preview).put("gif", g.gif))
                }
                entry.put("items", arr)

                // Перезаписываем запись этого же запроса, остальные храним.
                val kept = ArrayList<String>()
                if (f.isFile) {
                    for (line in f.readLines()) {
                        if (line == MAGIC || line.isBlank()) continue
                        val q = runCatching { JSONObject(line).optString("q") }.getOrNull()
                        if (q != key) kept.add(line)
                    }
                }
                kept.add(entry.toString())
                while (kept.size > MAX_ENTRIES) kept.removeAt(0)

                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(MAGIC + "\n" + kept.joinToString("\n"))
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                Log.i(TAG, "save failed: ${e.message}")
            }
        }
    }

    /**
     * Сохранённые результаты запроса ("" - тренд). null - не сохранилось ничего.
     * Второй элемент - сохранённый курсор «ещё»; при недоступном сервере
     * его не используем (страница одна).
     */
    suspend fun load(context: Context, query: String): Pair<List<GifItem>, String>? =
        withContext(Dispatchers.IO) {
            try {
                val f = file(context)
                if (!f.isFile) return@withContext null
                val key = query.trim().lowercase().take(64)
                var best: JSONObject? = null
                for (line in f.readLines()) {
                    if (line == MAGIC || line.isBlank()) continue
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (o.optString("q") == key) {
                        val cur = best
                        if (cur == null || o.optLong("at") > cur.optLong("at")) best = o
                    }
                }
                val o = best ?: return@withContext null
                val arr = o.optJSONArray("items") ?: return@withContext null
                val items = ArrayList<GifItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id")
                    val preview = e.optString("preview")
                    val gif = e.optString("gif")
                    if (id.isNotBlank() && preview.isNotBlank() && gif.isNotBlank()) {
                        items.add(GifItem(id, preview, gif))
                    }
                }
                if (items.isEmpty()) null else items to o.optString("next")
            } catch (e: Exception) {
                Log.i(TAG, "load failed: ${e.message}")
                null
            }
        }
}
