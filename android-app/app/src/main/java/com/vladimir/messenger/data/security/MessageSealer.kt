package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import uniffi.p2p_core.createFileKeyEnvelope
import uniffi.p2p_core.openFileKeyEnvelope

/**
 * Сквозное шифрование переписки.
 *
 * Зачем: прямой QUIC защищён TLS 1.3, но путь через ретранслятор идёт по
 * чужому публичному брокеру. До этой правки текст личных сообщений лежал там
 * открытыми байтами и читался кем угодно. Теперь наружу уходит конверт,
 * который может вскрыть только получатель.
 *
 * Схема (гибридная, ровно как у файлов):
 * 1. На каждое сообщение — свежий случайный ключ 32 байта.
 * 2. Тело шифруется AES-GCM этим ключом.
 * 3. Ключ запечатывается в X25519-конверт ядра для одного получателя
 *    ([createFileKeyEnvelope]): аутентифицированный обмен плюс подпись Ed25519
 *    отправителя.
 *
 * Почему переиспользуется файловый конверт, а не заведён новый примитив:
 * это уже проверенный и, главное, уже собранный в ядре код. Новая функция в
 * ядре потребовала бы перегенерации FFI-привязок, а они лежат в репозитории
 * готовыми и сборкой не обновляются.
 *
 * Свой ключ на сообщение означает, что вскрытие одного сообщения не раскрывает
 * остальные.
 *
 * Ключи собеседников берём из уже работающего обмена файловыми привязками:
 * они расходятся пакетом HELLO по всем контактам и закрепляются по принципу
 * «доверяем первому увиденному».
 */
object MessageSealer {
    private const val TAG = "MessageSealer"
    private const val PREFS = "apu_message_sealer"
    private const val KEY_PREFIX = "binding_"
    private const val MAX_CACHED = 2000

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Потолок открытого текста: групповой конверт 16 КБ, личный — 4096 символов. */
    const val MAX_PLAINTEXT_BYTES = 64 * 1024

    private val random = SecureRandom()

    /** peerId -> подписанная привязка получателя. */
    private val bindings = ConcurrentHashMap<String, ByteArray>()

    /**
     * Запомнить привязку собеседника. Вызывается там же, где ключ
     * закрепляется для файлов, поэтому отдельного согласования не нужно.
     */
    fun remember(context: Context, peerId: String, binding: ByteArray) {
        if (!peerId.startsWith("pk_") || binding.isEmpty()) return
        if (bindings.size >= MAX_CACHED && !bindings.containsKey(peerId)) return
        bindings[peerId] = binding.copyOf()
        runCatching {
            prefs(context).edit()
                .putString(KEY_PREFIX + peerId, Base64.encodeToString(binding, Base64.NO_WRAP))
                .apply()
        }
    }

    /** Есть ли ключ, которым можно запечатать для этого узла. */
    fun canSeal(context: Context, peerId: String): Boolean = binding(context, peerId) != null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun binding(context: Context, peerId: String): ByteArray? {
        bindings[peerId]?.let { return it }
        val stored = runCatching { prefs(context).getString(KEY_PREFIX + peerId, null) }
            .getOrNull() ?: return null
        val decoded = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        bindings[peerId] = decoded
        return decoded
    }

    /**
     * Запечатать текст для получателя.
     *
     * @return строка для транспорта или null, если ключ собеседника ещё не
     * известен. Null — не ошибка, а сигнал: сначала обменяться ключами.
     */
    fun seal(context: Context, peerId: String, plaintext: String): String? {
        val app = context.applicationContext
        val body = plaintext.toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > MAX_PLAINTEXT_BYTES) return null
        val recipientBinding = binding(app, peerId) ?: return null
        val myBinding = FileExchangeKeyStore.publicBinding(app) ?: return null

        return runCatching {
            val messageKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val ciphertext = try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(messageKey, "AES"),
                    GCMParameterSpec(TAG_BITS, iv),
                )
                cipher.doFinal(body)
            } catch (error: Exception) {
                messageKey.fill(0)
                throw error
            }

            // Манифест привязывает ключ к этому конкретному шифротексту:
            // подменить тело не выйдет, ядро сверит манифест при вскрытии.
            val manifest = manifest(ciphertext)
            val keyEnvelope = FileExchangeKeyStore.withExistingSecret(app) { secret ->
                createFileKeyEnvelope(myBinding, recipientBinding, secret, manifest, messageKey)
            }
            messageKey.fill(0)
            SealedWire.encode(keyEnvelope, iv, ciphertext)
        }.onFailure { Log.w(TAG, "seal failed for ${peerId.takeLast(8)}: ${it.message}") }
            .getOrNull()
    }

    /**
     * Вскрыть входящий конверт.
     *
     * @return открытый текст или null, если конверт не для нас либо повреждён.
     */
    fun open(context: Context, wire: String): String? {
        val app = context.applicationContext
        val parts = SealedWire.decode(wire) ?: return null
        val myBinding = FileExchangeKeyStore.publicBinding(app) ?: return null

        return runCatching {
            val manifest = manifest(parts.ciphertext)
            val messageKey = FileExchangeKeyStore.withExistingSecret(app) { secret ->
                openFileKeyEnvelope(parts.keyEnvelope, myBinding, secret, manifest)
            }
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(messageKey, "AES"),
                    GCMParameterSpec(TAG_BITS, parts.iv),
                )
                cipher.doFinal(parts.ciphertext).toString(Charsets.UTF_8)
            } finally {
                messageKey.fill(0)
            }
        }.onFailure { Log.i(TAG, "open failed (not ours or tampered): ${it.message}") }
            .getOrNull()
    }

    /**
     * Манифест обеих сторон обязан совпадать байт в байт, иначе ядро не отдаст
     * ключ и сообщение молча не откроется.
     *
     * Поэтому в манифесте ТОЛЬКО отпечаток шифротекста и никаких имён узлов.
     * Раунд 80: узел может называться и 32-, и 64-символьным видом одного и
     * того же ключа (`is_legacy_routing_node_id` допускает оба), а на разных
     * путях доставки приходит разный вид. Из-за этого сообщения через
     * ретранслятор не открывались, хотя по Wi-Fi всё работало.
     *
     * Безопасность от этого не страдает: подлинность обеих сторон уже
     * проверяется подписанными привязками внутри самого конверта, а привязка
     * ключа к телу сообщения сохраняется отпечатком.
     */
    private fun manifest(ciphertext: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        return ("apu-msg-v1|" + digest.toHex()).toByteArray(Charsets.UTF_8)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun forget(context: Context, peerId: String) {
        bindings.remove(peerId)
        runCatching { prefs(context).edit().remove(KEY_PREFIX + peerId).apply() }
    }
}
