package com.vladimir.messenger.data.featurepacks

/**
 * Каталог докачиваемых пакетов функций: что можно вынести из базового APK,
 * чтобы первая установка была маленькой, а тяжёлый функционал догружался
 * по желанию из настроек — без привязки к рангам (решение владельца:
 * полноценное приложение, в настройках раздел с переключателями
 * «что докачать»).
 *
 * Цифры — не оценка на глаз, а замер CI-прогона 41695927881
 * (`scripts/ci/apk-composition.py`, аннотации «APK composition»):
 * файл APK 222,7 МБ, из них нативные библиотеки всех ABI — 178,8 МБ,
 * dex — 20,2 МБ, ресурсы — 16,4 МБ, assets — 6,6 МБ. Самые тяжёлые
 * библиотеки: перевод ML Kit (xeno + translate_jni + language_id) —
 * 100,8 МБ на три ABI, Vosk — 25,6 МБ, детектор лиц — 22,5 МБ.
 * Ядро сети (libp2p_core.so, 29,4 МБ) остаётся в базе: без него нет
 * ни сообщений, ни роя, ни самой догрузки.
 *
 * Пакет — это нативные библиотеки одной функции под одну архитектуру.
 * Код функции (dex) остаётся в базе и обязан проверять наличие пакета
 * перед вызовом нативной части. Телефон качает только свою ABI, поэтому
 * размеры ниже — по архитектурам отдельно.
 */
enum class FeaturePackFeature {
    /** Перевод входящих сообщений на устройстве (ML Kit Translate). */
    MESSAGE_TRANSLATION,

    /** Распознавание голосовых сообщений в текст (Vosk, офлайн). */
    VOICE_TRANSCRIPTION,

    /** Лицо и эффекты в видеоисториях и видеозвонках (ML Kit Face). */
    VIDEO_FACE_EFFECTS,
}

data class FeaturePack(
    val id: String,
    val feature: FeaturePackFeature,
    /** Нативные библиотеки пакета: именно они уезжают из базового APK. */
    val nativeLibs: List<String>,
    /** Размер пакета (сжатый, как в APK) по архитектурам, байты. */
    val sizeBytesByAbi: Map<String, Long>,
) {
    /** Имя файла пакета на релизе: один файл на пакет и архитектуру. */
    fun downloadFileName(abi: String): String = "feature-pack-$id-$abi.zip"

    /**
     * Размер для показа и планирования загрузки. Неизвестной архитектуре
     * честно показываем наибольший вариант, а не наименьший.
     */
    fun sizeBytesFor(abi: String): Long =
        sizeBytesByAbi[abi] ?: sizeBytesByAbi.values.maxOrNull() ?: 0L
}

object FeaturePackCatalog {

    const val ABI_ARM64 = "arm64-v8a"
    const val ABI_ARM32 = "armeabi-v7a"
    const val ABI_X86_64 = "x86_64"

    /** Архитектуры релиза: тот же набор, что у универсального APK. */
    val releaseAbis: List<String> = listOf(ABI_ARM64, ABI_ARM32, ABI_X86_64)

    private fun mb(value: Double): Long = (value * 1024.0 * 1024.0).toLong()

    val packs: List<FeaturePack> = listOf(
        FeaturePack(
            id = "translate",
            feature = FeaturePackFeature.MESSAGE_TRANSLATION,
            nativeLibs = listOf("libxeno_native.so", "libtranslate_jni.so", "liblanguage_id_l2c_jni.so"),
            // Замер: xeno 20.6/15.3/18.9 + translate_jni 15.6/11.1/16.6 +
            // language_id ~0.9 на ABI (всего 2.8 на три архитектуры).
            sizeBytesByAbi = mapOf(
                ABI_ARM64 to mb(37.1),
                ABI_ARM32 to mb(27.3),
                ABI_X86_64 to mb(36.5),
            ),
        ),
        FeaturePack(
            id = "voice-text",
            feature = FeaturePackFeature.VOICE_TRANSCRIPTION,
            nativeLibs = listOf("libvosk.so"),
            // Замер: arm64 8.5, x86_64 9.2, armeabi-v7a — остаток 25.6-8.5-9.2.
            // Языковая модель Vosk и так скачивается отдельно при первом
            // использовании, в эти цифры она не входит.
            sizeBytesByAbi = mapOf(
                ABI_ARM64 to mb(8.5),
                ABI_ARM32 to mb(7.9),
                ABI_X86_64 to mb(9.2),
            ),
        ),
        FeaturePack(
            id = "face-effects",
            feature = FeaturePackFeature.VIDEO_FACE_EFFECTS,
            nativeLibs = listOf("libface_detector_v2_jni.so"),
            // Замер: arm64 8.1, x86_64 9.2, armeabi-v7a — остаток 22.5-8.1-9.2.
            sizeBytesByAbi = mapOf(
                ABI_ARM64 to mb(8.1),
                ABI_ARM32 to mb(5.2),
                ABI_X86_64 to mb(9.2),
            ),
        ),
    )

    fun pack(id: String): FeaturePack? = packs.firstOrNull { it.id == id }

    fun packFor(feature: FeaturePackFeature): FeaturePack =
        packs.first { it.feature == feature }

    /** Сколько скачает телефон с этой архитектурой при выборе этих пакетов. */
    fun totalBytes(packIds: Collection<String>, abi: String): Long =
        packs.filter { it.id in packIds }.sumOf { it.sizeBytesFor(abi) }

    /** Все пакеты сразу: размер полной догрузки для этой архитектуры. */
    fun totalBytesAll(abi: String): Long = packs.sumOf { it.sizeBytesFor(abi) }

    /**
     * Сколько мегабайт базовый APK теряет, если все пакеты уедут из него
     * (все архитектуры сразу, как сейчас лежит в файле). Для отчётов
     * владельцу, не для показа на телефоне.
     */
    fun totalBytesAllAbis(): Long =
        packs.sumOf { pack -> pack.sizeBytesByAbi.values.sum() }

    /** Имена всех файлов догрузки на релизе: пакеты × архитектуры. */
    fun releaseFileNames(): List<String> =
        packs.flatMap { pack -> releaseAbis.map { abi -> pack.downloadFileName(abi) } }
}
