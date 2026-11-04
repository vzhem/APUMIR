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

    // ── Установленные пакеты и текущая просьба (раздача роем) ─────────────

    private const val KEY_INSTALLED_PREFIX = "installed_"
    private const val KEY_PENDING = "pending_request"

    /** Пакет, уже лежащий на телефоне: файл проверен по контрольной сумме. */
    data class Installed(val packId: String, val abi: String, val sha256: String, val sizeBytes: Long)

    fun installed(context: Context, packId: String): Installed? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED_PREFIX + packId, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val size = parts[3].toLongOrNull() ?: return null
        if (FeaturePackCatalog.pack(parts[0]) == null) return null
        return Installed(parts[0], parts[1], parts[2], size)
    }

    fun saveInstalled(context: Context, record: Installed) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                KEY_INSTALLED_PREFIX + record.packId,
                "${record.packId}|${record.abi}|${record.sha256}|${record.sizeBytes}",
            )
            .apply()
    }

    fun clearInstalled(context: Context, packId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_INSTALLED_PREFIX + packId)
            .apply()
    }

    /** Текущая просьба о пакете: sha256 пуст, пока ни один сосед не объявил пакет. */
    data class Pending(val packId: String, val abi: String, val sha256: String, val startedAtMs: Long)

    fun pending(context: Context): Pending? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val started = parts[3].toLongOrNull() ?: return null
        if (FeaturePackCatalog.pack(parts[0]) == null) return null
        return Pending(parts[0], parts[1], parts[2], started)
    }

    fun savePending(context: Context, pending: Pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, "${pending.packId}|${pending.abi}|${pending.sha256}|${pending.startedAtMs}")
            .apply()
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING)
            .apply()
    }
}
