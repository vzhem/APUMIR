package com.vladimir.messenger.util

// =============================================================================
// APULINK.KT — короткая ссылка-приглашение
// =============================================================================
// Прежняя ссылка занимала 589 символов и требовала QR из 85 модулей: код
// выходил густой сеткой, которую камера ловила подолгу. Из этих 589 символов
// 427 приходилось на подписанный токен приглашения, ещё 67 - на узел,
// записанный шестнадцатеричными цифрами.
//
// Короткая ссылка убирает и то, и другое:
//
//   apu://a/<узел>/<никнейм>
//
//  - узел записан плотнее (base64url вместо hex): 43 символа вместо 67;
//  - @никнейм заменяет имя человека - оно короткое, не требует перекодировки
//    и остаётся у человека при переустановке;
//  - токен в ссылке НЕ едет. Приглашённый спрашивает его у пригласившего по
//    связи сразу после знакомства (см. ReferralWire, пакеты tokq/tokr).
//    Подпись остаётся прежней, ранг начисляется как раньше.
//
// Итог: 60 символов и 33 модуля вместо 85. Код становится крупноклеточным и
// читается с большего расстояния и под углом.
//
// Где это помогает ещё:
//  - ссылку можно продиктовать голосом или переписать от руки;
//  - она влезает в подпись, СМС и заголовок сообщения;
//  - её не рвут на части мессенджеры, обрезающие длинные ссылки;
//  - тот же короткий вид годится для приглашений в группы.
//
// Старые ссылки продолжают работать: InviteLinkParser разбирает и их.
// =============================================================================

// java.util.Base64 доступен с API 26 (минимум приложения), поэтому разбор
// ссылки проверяется обычными JVM-тестами, без Android.
import java.util.Base64

object ApuLink {

    const val SCHEME = "apu"

    /** Знакомство: узел плюс @никнейм. */
    const val HOST_ADD = "a"

    /** Ссылка только по @никнейму, для тех, кто уже есть в реестре имён. */
    const val HOST_USER = "u"

    private const val MAX_NICK_CHARS = 32
    private val nickPattern = Regex("^[A-Za-z0-9_]{1,32}$")
    private val hexNode = Regex("^pk_([0-9a-f]{32}|[0-9a-f]{64})$")

    data class Parsed(
        /** Узел в привычном виде `pk_<hex>` либо null, если ссылка только с именем. */
        val nodeId: String?,
        /** @никнейм без собаки либо null. */
        val nickname: String?,
    )

    /**
     * Собрать короткую ссылку.
     *
     * @param nodeId узел в виде `pk_<hex>`.
     * @param nickname @никнейм без собаки; пустой допустим - тогда ссылка несёт только узел.
     */
    fun build(nodeId: String, nickname: String?): String? {
        val packed = packNode(nodeId) ?: return null
        val nick = normalizeNick(nickname)
        return if (nick == null) "$SCHEME://$HOST_ADD/$packed"
        else "$SCHEME://$HOST_ADD/$packed/$nick"
    }

    /** Ссылка только по имени: «найдите меня по @никнейму». */
    fun buildByNickname(nickname: String?): String? {
        val nick = normalizeNick(nickname) ?: return null
        return "$SCHEME://$HOST_USER/$nick"
    }

    fun parse(raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Регистр схемы не важен: некоторые сканеры отдают текст заглавными.
        val lower = text.lowercase()
        val body = when {
            lower.startsWith("$SCHEME://$HOST_ADD/") -> text.substring(SCHEME.length + 3 + HOST_ADD.length + 1)
            lower.startsWith("$SCHEME://$HOST_USER/") -> {
                val nick = normalizeNick(text.substring(SCHEME.length + 3 + HOST_USER.length + 1))
                    ?: return null
                return Parsed(nodeId = null, nickname = nick)
            }
            else -> return null
        }
        val parts = body.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nodeId = unpackNode(parts[0]) ?: return null
        val nick = parts.getOrNull(1)?.let { normalizeNick(it) }
        return Parsed(nodeId = nodeId, nickname = nick)
    }

    /** `pk_<hex>` → плотная запись base64url. */
    fun packNode(nodeId: String?): String? {
        val text = nodeId?.trim()?.lowercase() ?: return null
        if (!hexNode.matches(text)) return null
        val hex = text.removePrefix("pk_")
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Плотная запись base64url → `pk_<hex>`. */
    fun unpackNode(packed: String?): String? {
        val text = packed?.trim() ?: return null
        if (text.isEmpty() || text.length > 64) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(text)
            // Узел бывает двух длин: короткий 16 байт и полный 32 байта.
            if (bytes.size != 16 && bytes.size != 32) return null
            val hex = StringBuilder(bytes.size * 2)
            for (b in bytes) hex.append(String.format("%02x", b))
            "pk_$hex"
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** @никнейм без собаки, только буквы, цифры и подчёркивание. */
    fun normalizeNick(raw: String?): String? {
        val clean = raw?.trim()?.trimStart('@')?.trim()?.take(MAX_NICK_CHARS) ?: return null
        return clean.takeIf { it.isNotEmpty() && nickPattern.matches(it) }
    }
}
