package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.service.BotApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сохранение и восстановление личности.
 *
 * Связывает три вещи: личность в настройках телефона, сундук
 * ([IdentityVault]) и полку на сервере ([BotApi]).
 *
 * Что здесь важно понимать: пароль и ключ НЕ уходят на сервер. Сундук
 * запирается на телефоне, наружу летят непрозрачные байты, и открыть их можно
 * только зная никнейм и пароль.
 */
@Singleton
class IdentityBackup @Inject constructor(
    private val botApi: BotApi,
) {
    companion object {
        private const val TAG = "IdentityBackup"
        private const val PREFS = "p2p_prefs"

        /** Отметка, что человек уже завёл пароль: экран настроек показывает состояние. */
        private const val KEY_PROTECTED_NICK = "identity_backup_nickname"
        private const val KEY_PROTECTED_AT = "identity_backup_saved_at"
    }

    sealed interface SaveResult {
        data object Success : SaveResult
        data object NoIdentity : SaveResult
        data object BadInput : SaveResult
        data object NetworkFailed : SaveResult
    }

    sealed interface RestoreResult {
        data class Success(val nodeId: String, val displayName: String) : RestoreResult
        /** Полка пуста: под этим никнеймом ничего не сохраняли. */
        data object NotFound : RestoreResult
        /** Сундук есть, но не открылся: неверный пароль или никнейм. */
        data object WrongPassword : RestoreResult
        data object NetworkFailed : RestoreResult
    }

    /** Под каким никнеймом защищена личность, или null, если пароль не заводили. */
    fun protectedNickname(context: Context): String? =
        prefs(context).getString(KEY_PROTECTED_NICK, null)

    fun savedAtMs(context: Context): Long = prefs(context).getLong(KEY_PROTECTED_AT, 0L)

    /**
     * Запереть текущую личность и положить на полку.
     *
     * Вызывается и при первом задании пароля, и при каждой смене: содержимое
     * то же самое, меняется только замок, поэтому адрес человека не меняется
     * никогда.
     */
    suspend fun save(context: Context, nickname: String, password: String): SaveResult {
        val nick = IdentityVault.normalizeNickname(nickname) ?: return SaveResult.BadInput
        if (password.length < IdentityVault.MIN_PASSWORD_LENGTH) return SaveResult.BadInput

        val prefs = prefs(context)
        val nodeId = prefs.getString("node_id", null)
            ?: prefs.getString("existing_public_key", null)
            ?: return SaveResult.NoIdentity
        val privateKey = prefs.getString("existing_private_key", null) ?: nodeId
        val displayName = prefs.getString("display_name", "") ?: ""

        val identity = IdentityVault.Identity(
            nodeId = nodeId,
            privateKey = privateKey,
            displayName = displayName,
            nickname = nick,
        )
        val sealed = IdentityVault.seal(identity, nick, password) ?: return SaveResult.BadInput
        val shelf = IdentityVault.shelfFor(nick) ?: return SaveResult.BadInput

        val stored = botApi.storeIdentityVault(
            shelf = shelf,
            sealedBase64 = Base64.encodeToString(sealed, Base64.NO_WRAP),
        )
        if (!stored) return SaveResult.NetworkFailed

        prefs.edit()
            .putString(KEY_PROTECTED_NICK, nick)
            .putLong(KEY_PROTECTED_AT, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Личность защищена под @$nick")
        return SaveResult.Success
    }

    /**
     * Вернуть личность после переустановки.
     *
     * Записывает восстановленные ключи в настройки. Движок должен быть
     * перезапущен ПОСЛЕ этого, иначе он продолжит работать под свежесозданным
     * адресом.
     */
    suspend fun restore(context: Context, nickname: String, password: String): RestoreResult {
        val nick = IdentityVault.normalizeNickname(nickname) ?: return RestoreResult.WrongPassword
        val shelf = IdentityVault.shelfFor(nick) ?: return RestoreResult.WrongPassword

        val fetched = botApi.fetchIdentityVault(shelf) ?: return RestoreResult.NotFound
        val sealed = runCatching { Base64.decode(fetched, Base64.NO_WRAP) }.getOrNull()
            ?: return RestoreResult.NotFound
        // Неверный пароль и порченый сундук намеренно неотличимы: иначе сундук
        // подсказывал бы подбирающему, что никнейм угадан верно.
        val identity = IdentityVault.open(sealed, nick, password)
            ?: return RestoreResult.WrongPassword

        prefs(context).edit()
            .putBoolean("identity_created", true)
            .putString("node_id", identity.nodeId)
            .putString("public_key", identity.nodeId)
            .putString("existing_public_key", identity.nodeId)
            .putString("existing_private_key", identity.privateKey)
            .putString("display_name", identity.displayName)
            .putString("my_username", identity.nickname)
            .putString(KEY_PROTECTED_NICK, identity.nickname)
            .putLong(KEY_PROTECTED_AT, System.currentTimeMillis())
            .commit()

        // Метка устройства: без неё восстановленное состояние сочли бы
        // подсунутым из чужой резервной копии и стёрли на следующем запуске.
        runCatching { DeviceIdentityMarker.create(context) }

        Log.i(TAG, "Личность @${identity.nickname} восстановлена")
        return RestoreResult.Success(identity.nodeId, identity.displayName)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
