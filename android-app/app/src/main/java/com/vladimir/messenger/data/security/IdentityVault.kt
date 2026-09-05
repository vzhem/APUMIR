package com.vladimir.messenger.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Хранилище личности: ключ узла, запертый никнеймом и паролем.
 *
 * Зачем: переустановка приложения стирает всё, и человек становился новым -
 * другой адрес, потерянный ранг, оборванная переписка, для собеседников
 * незнакомец. Теперь ключ можно сложить в сундук, который переживёт
 * переустановку, и вернуть его, введя никнейм и пароль.
 *
 * Почему сундук, а не вывод адреса из пароля напрямую: владелец должен иметь
 * возможность СМЕНИТЬ пароль, не меняя личность. Если адрес вычисляется из
 * пароля, смена пароля означает смену человека - ровно та беда, от которой
 * уходим. Здесь адрес живёт внутри сундука, а пароль лишь запирает его:
 * меняем пароль - перекладываем то же содержимое в новый замок.
 *
 * Что видит сервер: только непрозрачные байты. Шифрование и расшифровка
 * происходят на телефоне, пароль наружу не уходит никогда. Даже владелец
 * сервера не может достать чужой ключ.
 *
 * Против накрутки рангов: восстановление возвращает ТУ ЖЕ личность вместе с
 * её рангом и историей, поэтому переустанавливаться ради чистого старта
 * бессмысленно.
 */
object IdentityVault {

    /** Столько повторов при выводе ключа - подбор пароля становится дорогим. */
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERSION = 1

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Разделитель домена: те же пароль и ник в другом месте дадут другое. */
    private const val DOMAIN = "apu-identity-vault-v1"

    private val random = SecureRandom()

    /** Минимальная длина пароля. Короче - подбирается слишком быстро. */
    const val MIN_PASSWORD_LENGTH = 8

    /** Что лежит внутри сундука: всё, что делает человека собой. */
    data class Identity(
        val nodeId: String,
        val privateKey: String,
        val displayName: String,
        val nickname: String,
    )

    /**
     * Адрес полки на сервере.
     *
     * Выводится из никнейма, поэтому по нему нельзя понять, чей это сундук, и
     * нельзя перебрать все полки подряд. Регистр никнейма не важен.
     */
    fun shelfFor(nickname: String): String? {
        val nick = normalizeNickname(nickname) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$DOMAIN:shelf:$nick".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Запереть личность никнеймом и паролем. Возвращает содержимое сундука. */
    fun seal(identity: Identity, nickname: String, password: String): ByteArray? {
        val nick = normalizeNickname(nickname) ?: return null
        if (password.length < MIN_PASSWORD_LENGTH) return null

        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(nick, password, salt) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val body = cipher.doFinal(encodeBody(identity))
            // version | salt | iv | тело
            byteArrayOf(VERSION.toByte()) + salt + iv + body
        } catch (_: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    /**
     * Открыть сундук.
     *
     * @return личность или null, если пароль неверный либо содержимое
     *         испорчено. Различать эти случаи намеренно нельзя: иначе сундук
     *         подсказывал бы подбирающему, что он на верном пути.
     */
    fun open(sealed: ByteArray, nickname: String, password: String): Identity? {
        val nick = normalizeNickname(nickname) ?: return null
        val header = 1 + SALT_BYTES + IV_BYTES
        if (sealed.size <= header || sealed[0].toInt() != VERSION) return null

        val salt = sealed.copyOfRange(1, 1 + SALT_BYTES)
        val iv = sealed.copyOfRange(1 + SALT_BYTES, header)
        val body = sealed.copyOfRange(header, sealed.size)
        val key = deriveKey(nick, password, salt) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            decodeBody(cipher.doFinal(body))
        } catch (_: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    /** Никнейм без собаки, в нижнем регистре. Пустой считается отсутствующим. */
    fun normalizeNickname(nickname: String): String? =
        nickname.trim().trimStart('@').trim().lowercase().takeIf { it.isNotEmpty() }

    private fun deriveKey(nickname: String, password: String, salt: ByteArray): ByteArray? {
        // Никнейм подмешан в соль: одинаковый пароль у разных людей даёт
        // разные ключи, поэтому один подбор не вскрывает сразу многих.
        val fullSalt = MessageDigest.getInstance("SHA-256")
            .digest(salt + "$DOMAIN:$nickname".toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), fullSalt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: Exception) {
            null
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Поля разделяются переводом строки, а сами значения его не содержат:
     * адрес и ключ - шестнадцатеричные, имя и никнейм чистятся при вводе.
     * Перевод строки в имени всё же срезаем, иначе он разъехал бы разбор.
     */
    private fun encodeBody(identity: Identity): ByteArray = listOf(
        identity.nodeId,
        identity.privateKey,
        identity.displayName.replace("\n", " "),
        identity.nickname.replace("\n", " "),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun decodeBody(bytes: ByteArray): Identity? {
        val fields = bytes.toString(Charsets.UTF_8).split("\n")
        if (fields.size != 4) return null
        val nodeId = fields[0]
        if (!nodeId.matches(Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$"))) return null
        if (fields[1].isEmpty()) return null
        return Identity(
            nodeId = nodeId,
            privateKey = fields[1],
            displayName = fields[2],
            nickname = fields[3],
        )
    }
}
