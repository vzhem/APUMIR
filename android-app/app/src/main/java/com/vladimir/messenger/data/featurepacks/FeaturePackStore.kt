package com.vladimir.messenger.data.featurepacks

import android.content.Context
import android.os.Build

/**
 * Выбор владельца в разделе «Дополнительные функции»: какие пакеты
 * докачать. Ранги здесь не участвуют вообще — переключатель доступен
 * каждому, кто поставил приложение (решение владельца).
 *
 * Пока пакеты не опубликованы на релизе, здесь живёт только выбор:
 * он переживёт обновление приложения, и загрузчик (следующая стадия,
 * docs/FEATURE_PACKS.md) подхватит его без повторных вопросов.
 * Состояние загрузки появится рядом, когда пакеты начнут выходить.
 */
object FeaturePackStore {

    private const val PREFS = "feature_packs"
    private const val KEY_SELECTED = "selected_pack_ids"

    /** Архитектура этого телефона: пакеты качаются только под неё. */
    fun deviceAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it in FeaturePackCatalog.releaseAbis }
            ?: FeaturePackCatalog.ABI_ARM64

    fun selectedIds(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED, emptySet())
            .orEmpty()
        // Каталог мог измениться между версиями: несуществующих id не держим.
        return raw.filter { FeaturePackCatalog.pack(it) != null }.toSet()
    }

    fun setSelected(context: Context, packId: String, selected: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = selectedIds(context).toMutableSet()
        if (selected) current += packId else current -= packId
        prefs.edit().putStringSet(KEY_SELECTED, current).apply()
    }

    fun selectAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED, FeaturePackCatalog.packs.map { it.id }.toSet())
            .apply()
    }

    /** Суммарный объём выбранной догрузки именно для этого телефона. */
    fun selectedBytes(context: Context): Long =
        FeaturePackCatalog.totalBytes(selectedIds(context), deviceAbi())
}
