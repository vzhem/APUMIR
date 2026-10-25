package com.vladimir.messenger.data.mirror

// =============================================================================
// MIRRORROOMTOKEN.KT — маркер комнаты зеркала (раунд 249)
// =============================================================================
// До этой правки полка комнаты выводилась из ОТКРЫТОГО nodeId личности:
//   shelf = sha256("apu-mirror-v1|" + nodeId)
// Адрес личности знает каждый, с кем человек переписывался, - значит, любой
// мог вычислить полку, зайти в комнату и занять одно из четырёх мест в ней.
// Хуже того, вскрыв кадр нельзя было понять, кто его прислал: кадры
// запечатаны на НАШ открытый ключ, а запечатать на него может кто угодно.
//
// Теперь адрес комнаты выводится из маркера - случайного значения, которого
// нет ни в одном открытом поле личности:
//   shelf = sha256("apu-mirror-v2|" + маркер)
// Маркер лежит в `p2p_prefs`, а этот файл настроек входит в копию аккаунта
// (BackupLayout.PREFS_NAMES), поэтому второе устройство получает его вместе с
// личностью при восстановлении - переписывать ничего не нужно.
//
// Откуда маркер берётся у ПАР, которые уже работают: устройства обмениваются
// им по самому зеркальному каналу (см. MirrorChannel), но только после
// проверки: партнёр должен доказать, что умеет читать кадры, запечатанные на
// эту личность. Доказательство - ответ на случайное число внутри
// запечатанного кадра: вскрыть его может только тот, у кого есть наш закрытый
// ключ, то есть другое устройство той же личности (и оно же может прочитать
// сам маркер). Посторонний в общей комнате числа не увидит и ответа не даст.
// =============================================================================

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object MirrorRoomToken {

    private const val MAIN_PREFS = "p2p_prefs"
    private const val KEY_TOKEN = "mirror_room_v1"
    private const val KEY_AT = "mirror_room_at_v1"
    /** Shared only by devices paired for the mirror room, never sent in hello/hb. */
    private const val KEY_SYNC = "mirror_sync_key_v1"
    private const val TOKEN_BYTES = 16
    private const val SYNC_KEY_BYTES = 32
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Маркер комнаты и время его рождения (по нему два маркера сравнивают). */
    data class RoomToken(
        val value: String,
        val createdAtMs: Long,
    )

    private val random = SecureRandom()

    /** Текущий маркер или null, если его ещё нет (дообновление или новая личность). */
    fun load(context: Context): RoomToken? {
        val prefs = prefs(context)
        val value = prefs.getString(KEY_TOKEN, null) ?: return null
        if (!value.matches(Regex("^[0-9a-f]{32}$"))) return null
        val at = prefs.getLong(KEY_AT, 0L)
        return RoomToken(value, if (at > 0L) at else System.currentTimeMillis())
    }

    /**
     * Создать новый маркер и сразу записать его.
     *
     * `commit`, а не `apply`: маркер успевают спросить партнёры в первые же
     * секунды, поэтому он должен лежать в настройках до того, как мы начнём
     * его раздавать.
     */
    fun create(context: Context): RoomToken? {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val token = RoomToken(bytes.toHex(), System.currentTimeMillis())
        return if (save(context, token, sync = true)) token else null
    }

    /** Записать маркер (принять от партнёра или создать свой). */
    fun save(context: Context, token: RoomToken, sync: Boolean = false): Boolean =
        try {
            val editor = prefs(context).edit()
                .putString(KEY_TOKEN, token.value)
                .putLong(KEY_AT, token.createdAtMs)
            if (sync) editor.commit() else editor.apply()
            true
        } catch (error: Exception) {
            false
        }

    /**
     * Ключ кадров живого зеркала. Он не выводится из nodeId и не попадает в
     * открытые hello/hb: новый телефон получает его только внутри
     * аутентифицированного pairing bootstrap.
     */
    fun loadSyncKey(context: Context): ByteArray? = runCatching {
        val encoded = prefs(context).getString(KEY_SYNC, null) ?: return null
        Base64.decode(encoded, B64_FLAGS).takeIf { it.size == SYNC_KEY_BYTES }
    }.getOrNull()

    /** Создать ключ кадров на показывающем телефоне, если его ещё нет. */
    fun createSyncKey(context: Context): ByteArray? {
        loadSyncKey(context)?.let { return it }
        val key = ByteArray(SYNC_KEY_BYTES).also { random.nextBytes(it) }
        return if (saveSyncKey(context, key, sync = true)) key else null
    }

    /** Записать ключ кадров, полученный из pairing bootstrap. */
    fun saveSyncKey(context: Context, key: ByteArray, sync: Boolean = false): Boolean {
        if (key.size != SYNC_KEY_BYTES) return false
        return runCatching {
            val encoded = Base64.encodeToString(key, B64_FLAGS)
            val editor = prefs(context).edit().putString(KEY_SYNC, encoded)
            if (sync) editor.commit() else editor.apply()
            true
        }.getOrDefault(false)
    }

    /** Забыть маркер и ключ зеркала (личность сброшена - комната больше не наша). */
    fun clear(context: Context) {
        runCatching {
            prefs(context).edit()
                .remove(KEY_TOKEN)
                .remove(KEY_AT)
                .remove(KEY_SYNC)
                .apply()
        }
    }

    /** Полка защищённой комнаты: из маркера, а не из открытого адреса личности. */
    fun shelf(token: String): String =
        sha256("apu-mirror-v2|" + token).take(32)

    /**
     * Печать полки для hello/hb: по ней партнёр видит, что мы разъехались
     * по разным комнатам. Сам маркер по печати не восстановить.
     */
    fun stamp(token: String?): String =
        if (token.isNullOrBlank()) "" else sha256("apu-mirror-stamp-v1|" + token).take(16)

    /**
     * Метка внутри запечатанного кадра: отличает своё устройство от чужого,
     * даже если адрес комнаты всё-таки утёк. Пустая - метку не проверяем
     * (старая общая комната, где маркера нет ни у кого).
     */
    fun frameMark(token: String?): String =
        if (token.isNullOrBlank()) "" else sha256("apu-mirror-frame-v1|" + token).take(32)

    /**
     * Чей маркер главнее: берём более старый, а при равенстве времени -
     * меньший строкой. Правило обязано быть одинаковым на обоих устройствах,
     * иначе они разойдутся по разным комнатам и не сойдутся никогда.
     */
    fun wins(candidate: RoomToken, current: RoomToken?): Boolean {
        if (current == null) return true
        if (candidate.createdAtMs != current.createdAtMs) {
            return candidate.createdAtMs < current.createdAtMs
        }
        return candidate.value < current.value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
