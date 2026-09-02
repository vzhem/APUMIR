package com.vladimir.messenger.util

import android.content.Context
import com.vladimir.messenger.data.referral.ReferralWire
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import java.net.URLEncoder

/**
 * Ссылка-приглашение на СВОЙ профиль — единственный источник для всего
 * приложения: текст, «Поделиться», QR-код в настройках, экран профиля и шаг
 * «Покажите другу» в регистрации.
 *
 * Зачем отдельно: ссылку строили в нескольких местах по-своему
 * (ShareProfileViewModel, диалог «Поделиться приглашением» в списке чатов через
 * RustBridge.generateInvite, «Мой QR-код» в настройках через p2p://invite/...,
 * шаг ShowInvite в регистрации), и в разных экранах владелец видел разные
 * строки. Хуже того, только часть из них несла подписанный токен, поэтому один
 * и тот же QR в одном месте поднимал ранг, а в другом — нет.
 *
 * Правило: новый экран, показывающий ссылку или QR на себя, берёт строку
 * ТОЛЬКО отсюда. Собственноручно склеенная ссылка означает приглашение без
 * токена, то есть без начисления ранга.
 */
object OwnInvite {

    private const val PREFS = "p2p_prefs"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_USERNAME = "my_username"

    /** Имя владельца из настроек; пустая строка, если профиль ещё не заполнен. */
    fun displayName(context: Context): String = prefs(context)
        .getString(KEY_DISPLAY_NAME, "")
        .orEmpty()

    /** Своё @имя без собаки; пустая строка, если владелец его не указал. */
    fun username(context: Context): String = prefs(context)
        .getString(KEY_USERNAME, "")
        .orEmpty()
        .trim()
        .trimStart('@')
        .trim()

    /**
     * Ссылка на свой профиль или null, пока узел не создан.
     * Null бывает на первом запуске, до генерации ключей.
     */
    fun link(context: Context): String? {
        val app = context.applicationContext
        val nodeId = prefs(app).getString(KEY_NODE_ID, "").orEmpty()
        if (nodeId.isBlank()) return null
        // Подписанный токен превращает обычную ссылку в приглашение: по нему
        // пригласивший потом зачтёт друга (см. data/referral). Если подпись
        // недоступна, ссылка остаётся рабочей, просто без начисления ранга.
        // Короткая ссылка - основной вид с версии 11.38. Токен по ней не едет:
        // приглашённый спросит его по связи (ReferralWire.tokq/tokr), поэтому
        // ранг начисляется как раньше, а QR становится втрое реже.
        ApuLink.build(nodeId, username(app))?.let { return it }
        // Узел непривычного вида - откатываемся на прежнюю длинную ссылку.
        val token = signedToken(app, nodeId)?.let { ReferralWire.encode(it) }
        return buildLink(nodeId, displayName(app), username(app), token)
    }

    /**
     * Чистая сборка ссылки, без Android: формат проверяют host-тесты
     * (`OwnInviteTest`), поэтому ссылка из любого экрана разбирается
     * `InviteLinkParser` одинаково.
     *
     * @param tokenB64 base64url подписанного токена либо null, если подписи нет.
     */
    fun buildLink(
        nodeId: String,
        displayName: String,
        username: String,
        tokenB64: String?,
    ): String? {
        val node = nodeId.trim()
        if (node.isEmpty()) return null
        val name = URLEncoder.encode(displayName, "UTF-8")
        // Своё @имя едет в ссылке, чтобы новый контакт сохранил его сам.
        val user = username.trim().trimStart('@').trim()
        val usernamePart = if (user.isEmpty()) "" else "&u=" + URLEncoder.encode(user, "UTF-8")
        val base = "p2pmessenger://add?node_id=$node&name=$name$usernamePart"
        if (tokenB64.isNullOrBlank()) return base
        return base + "&" + ReferralInviteLink.TOKEN_PARAMETER + "=" + tokenB64
    }

    private fun signedToken(context: Context, nodeId: String): ByteArray? = try {
        IdentitySigningKeyStore.createSignedReferralToken(context)
            ?: run {
                // Sidecar могли ещё не установить в этом процессе: ставим и
                // пробуем ещё раз, иначе приглашения молча теряли бы токен.
                IdentitySigningKeyStore.installIntoCore(context, nodeId)
                IdentitySigningKeyStore.createSignedReferralToken(context)
            }
    } catch (error: Exception) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
