package com.vladimir.messenger.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Код восстановления личности.
 *
 * Зачем: до этого личность жила только в памяти телефона, и переустановка
 * приложения делала человека новым - другой адрес, другое имя, потерянный
 * ранг и оборванная переписка. Собеседники видели незнакомца.
 *
 * Теперь адрес узла ВЫВОДИТСЯ из пары «никнейм + код восстановления»
 * математически. Тот же никнейм и тот же код на любом телефоне дают тот же
 * самый адрес, поэтому для собеседников человек вообще не менялся: ни склеивать
 * контакты, ни переносить переписку не требуется.
 *
 * Почему код, а не один никнейм: никнеймы публичны - они видны в переписке,
 * в группах и в ссылках. Если бы доказательством служило само знание никнейма,
 * любой мог бы переустановить приложение, ввести чужое имя и получить чужой
 * ранг и доверие. Код знает только владелец, поэтому чужое имя без кода даёт
 * другой адрес, и подмена сразу видна.
 *
 * Против накрутки рангов: переустановка перестаёт что-либо обнулять, значит
 * переустанавливаться ради «чистого старта» бессмысленно.
 *
 * Ключ нигде не хранится и никуда не отправляется - он каждый раз выводится
 * заново из того, что человек ввёл. Поэтому восстановление работает и без
 * интернета.
 */
object RecoveryCode {

    /**
     * Алфавит кода без похожих знаков: убраны 0/O, 1/I/L, чтобы код нельзя
     * было переписать с ошибкой. Только заглавные - так его проще диктовать.
     */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Длина кода без разделителей. 20 знаков алфавита из 31 - около 99 бит. */
    private const val CODE_LENGTH = 20

    /** По сколько знаков в группе при показе человеку. */
    private const val GROUP_SIZE = 5

    /**
     * Число повторов при выводе ключа. Подбор кода становится дорогим даже
     * если злоумышленник знает никнейм. 200 000 - компромисс между стойкостью
     * и задержкой на слабом телефоне (примерно полсекунды).
     */
    private const val ITERATIONS = 200_000

    private const val KEY_BITS = 256

    /** Разделитель домена: тот же код в другом месте даст другой результат. */
    private const val DOMAIN = "apu-identity-v1"

    private val random = SecureRandom()

    /** Сгенерировать новый код восстановления. Показывается человеку один раз. */
    fun generate(): String {
        val builder = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return format(builder.toString())
    }

    /** Разбить на группы через дефис: ABCDE-FGHJK-... - так его проще прочесть. */
    fun format(rawCode: String): String {
        val clean = normalize(rawCode)
        return clean.chunked(GROUP_SIZE).joinToString("-")
    }

    /**
     * Привести введённое к каноническому виду: убрать дефисы, пробелы и
     * регистр. Человек может ввести код как угодно - это не должно мешать.
     */
    fun normalize(rawCode: String): String =
        rawCode.uppercase()
            .filter { it in ALPHABET }

    /** Годится ли введённое как код: правильная длина и только наши знаки. */
    fun isValid(rawCode: String): Boolean = normalize(rawCode).length == CODE_LENGTH

    /**
     * Вывести адрес узла из никнейма и кода.
     *
     * Никнейм играет роль соли, поэтому один и тот же код у разных людей даёт
     * разные адреса. Регистр никнейма не важен: «Anna» и «anna» - один человек.
     *
     * @return `pk_` + 32 шестнадцатеричных знака - ровно тот вид, который ждёт
     *         ядро, либо null, если код не годится.
     */
    fun deriveNodeId(nickname: String, rawCode: String): String? {
        val secret = deriveSecret(nickname, rawCode) ?: return null
        // Адрес - первые 16 байт вывода: ядро использует node_id длиной
        // pk_ + 32 hex-знака (см. is_legacy_routing_node_id).
        val hex = secret.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        secret.fill(0)
        return "pk_$hex"
    }

    /**
     * Приватная часть пары для ядра. Выводится из того же секрета, но по
     * другому назначению, поэтому по адресу её вычислить нельзя.
     */
    fun derivePrivateKey(nickname: String, rawCode: String): String? {
        val secret = deriveSecret(nickname, rawCode) ?: return null
        val material = MessageDigest.getInstance("SHA-256").digest(
            "$DOMAIN:private".toByteArray(Charsets.US_ASCII) + secret,
        )
        secret.fill(0)
        val hex = material.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        material.fill(0)
        return "sk_$hex"
    }

    /**
     * Общий секрет, из которого выводится всё остальное.
     *
     * PBKDF2 намеренно медленный: подобрать код перебором дорого даже зная
     * никнейм.
     */
    private fun deriveSecret(nickname: String, rawCode: String): ByteArray? {
        val code = normalize(rawCode)
        if (code.length != CODE_LENGTH) return null
        val nick = nickname.trim().trimStart('@').lowercase()
        if (nick.isEmpty()) return null

        val salt = MessageDigest.getInstance("SHA-256")
            .digest("$DOMAIN:$nick".toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: Exception) {
            null
        } finally {
            spec.clearPassword()
        }
    }
}
