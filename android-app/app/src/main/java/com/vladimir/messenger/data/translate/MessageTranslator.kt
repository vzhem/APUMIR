package com.vladimir.messenger.data.translate

// =============================================================================
// MESSAGETRANSLATOR.kt — перевод входящих сообщений на устройстве
// =============================================================================
// Решение владельца (2026-10-08, вариант 1): перевод на устройстве через ML Kit.
// Текст переписки никуда не уходит: язык определяется и переводится локально.
// Языковые пакеты скачиваются один раз (около 30 МБ на язык) с серверов Google
// через Google Play services. Без сети перевод просто не выполняется, и
// пользователь видит оригинал.
//
// Перевод включается переключателем в настройках и касается только входящих
// текстовых сообщений. Карточки, картинки и служебные сообщения не переводятся.
// =============================================================================

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.vladimir.messenger.util.GroupInviteCardSender
import com.vladimir.messenger.util.ContactCardSender
import com.vladimir.messenger.util.ImageLinkDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Результат перевода: сам текст и код языка оригинала (BCP-47). */
data class MessageTranslation(val text: String, val sourceCode: String)

/** Переключатель «Переводить входящие сообщения». Хранится в p2p_prefs. */
object TranslationSettings {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "translate_incoming"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Вызывается из MainActivity.onCreate: читает сохранённое значение. */
    fun init(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
        _enabled.value = value
    }
}

object MessageTranslator {
    /** Кэш результатов в памяти: при прокрутке сообщение не переводится заново. */
    private val cache = object : LinkedHashMap<String, MessageTranslation?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageTranslation?>?): Boolean =
            size > 300
    }

    /** Переводчики по паре языков: создаются один раз и переиспользуются. */
    private val translators = HashMap<String, Translator>()

    private val languageId by lazy { LanguageIdentification.getClient() }

    /** Служебные сообщения (карточки, картинки) не переводим: там не текст для чтения. */
    fun isTranslatableText(text: String): Boolean {
        if (text.trim().length < 2) return false
        if (ImageLinkDetector.directImageUrl(text) != null) return false
        if (ContactCardSender.parseCard(text) != null) return false
        if (GroupInviteCardSender.parseCard(text) != null) return false
        if (GroupInviteCardSender.parseMultiCard(text) != null) return false
        return true
    }

    /**
     * Переводит текст на [targetCode], если он написан на другом языке.
     * Возвращает null, если перевод не нужен, язык не определён или перевод не удался.
     */
    suspend fun translateForeign(text: String, targetCode: String): MessageTranslation? {
        val key = targetCode + "|" + text
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val result = try {
            doTranslate(text, targetCode)
        } catch (e: Exception) {
            android.util.Log.w("MessageTranslator", "Перевод не выполнен: " + e.javaClass.simpleName)
            null
        }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private suspend fun doTranslate(text: String, targetCode: String): MessageTranslation? {
        val target = TranslateLanguage.fromLanguageTag(targetCode) ?: return null
        val detected = languageId.identifyLanguage(text).awaitResult()
        if (detected.isNullOrBlank() || detected == "und") return null
        val source = TranslateLanguage.fromLanguageTag(detected) ?: return null
        if (source == target) return null

        val translator = translatorFor(source, target)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
        val translated = translator.translate(text).awaitResult() ?: return null
        return MessageTranslation(text = translated, sourceCode = source)
    }

    private fun translatorFor(source: String, target: String): Translator {
        val pair = source + ">" + target
        synchronized(translators) {
            return translators.getOrPut(pair) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build(),
                )
            }
        }
    }

    /** Ожидание Task из ML Kit без дополнительной библиотеки корутин. */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    }
}
