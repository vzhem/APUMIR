package com.vladimir.messenger.data.featurepacks

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Исполнение докачанных пакетов (docs/FEATURE_PACKS.md): лёгкая база не
 * содержит тяжёлых нативных библиотек, поэтому функция перед первым
 * нативным вызовом обязана спросить [ensureLoaded]:
 *
 *  - пакета нет — функция честно недоступна (экран предлагает включить её
 *    в «Дополнительных функциях»), приложение не падает;
 *  - пакет скачан — библиотеки распаковываются в
 *    `filesDir/feature_packs/<id>/<abi>/` и подключаются [System.load] до
 *    того, как ML Kit / Vosk попытаются загрузить их сами.
 *
 * Порядок загрузки внутри пакета — как в каталоге: зависимости (например,
 * движок перевода) идут раньше обёрток.
 */
object FeaturePackRuntime {

    private const val TAG = "FeaturePackRuntime"

    private val loadedPacks = ConcurrentHashMap.newKeySet<String>()

    fun packDir(context: Context, pack: FeaturePack, abi: String): File =
        File(File(context.filesDir, "feature_packs"), "${pack.id}/$abi")

    /** Распаковать zip пакета в его каталог; true — все библиотеки на месте. */
    fun extract(context: Context, pack: FeaturePack, abi: String, zipFile: File): Boolean {
        val dir = packDir(context, pack, abi)
        return runCatching {
            dir.mkdirs()
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (name in pack.nativeLibs && !entry.isDirectory) {
                        val target = File(dir, name)
                        val temporary = File(dir, "$name.tmp")
                        temporary.outputStream().buffered().use { zip.copyTo(it) }
                        if (!temporary.renameTo(target)) {
                            temporary.copyTo(target, overwrite = true)
                            temporary.delete()
                        }
                    }
                    zip.closeEntry()
                }
            }
            pack.nativeLibs.all { File(dir, it).isFile }
        }.onFailure { Log.w(TAG, "pack ${pack.id} extract failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** Пакет скачан и распакован (библиотеки лежат на месте). */
    fun isReady(context: Context, packId: String): Boolean {
        val pack = FeaturePackCatalog.pack(packId) ?: return false
        val installed = FeaturePackStore.installed(context, packId) ?: return false
        val dir = packDir(context, pack, installed.abi)
        return pack.nativeLibs.all { File(dir, it).isFile }
    }

    /**
     * Подключить нативные библиотеки функции. Нет пакета — false, и
     * вызывающий обязан обойтись без функции, а не падать.
     *
     * Полная версия приложения (app-full.apk, выбор при скачивании):
     * библиотеки уже внутри APK — подключаем их напрямую, пакеты не нужны.
     */
    fun ensureLoaded(context: Context, feature: FeaturePackFeature): Boolean {
        val pack = FeaturePackCatalog.packFor(feature)
        if (pack.id in loadedPacks) return true
        val installed = FeaturePackStore.installed(context, pack.id)
        if (installed == null) {
            return tryLoadBundled(pack)
        }
        val dir = packDir(context, pack, installed.abi)
        if (!pack.nativeLibs.all { File(dir, it).isFile }) {
            // Zip скачан, но ещё не распакован (например, приложение
            // перезапустили между загрузкой и первым вызовом).
            val zip = File(File(context.filesDir, "feature_packs"), pack.downloadFileName(installed.abi))
            if (!zip.isFile || !extract(context, pack, installed.abi, zip)) return false
        }
        return runCatching {
            for (lib in pack.nativeLibs) {
                System.load(File(dir, lib).absolutePath)
            }
            loadedPacks += pack.id
            Log.i(TAG, "pack ${pack.id} native libraries loaded")
            true
        }.onFailure { Log.w(TAG, "pack ${pack.id} load failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Библиотеки, встроенные в сам APK (полная версия при скачивании):
     * пробуем загрузить по именам из APK. В лёгкой базе их нет — честное
     * false, функция останется недоступной до скачивания пакета.
     */
    private fun tryLoadBundled(pack: FeaturePack): Boolean = runCatching {
        for (lib in pack.nativeLibs) {
            System.loadLibrary(lib.removePrefix("lib").removeSuffix(".so"))
        }
        loadedPacks += pack.id
        Log.i(TAG, "pack ${pack.id} native libraries bundled in APK")
        true
    }.onFailure { Log.w(TAG, "pack ${pack.id} is not bundled: ${it.message}") }
        .getOrDefault(false)
}
