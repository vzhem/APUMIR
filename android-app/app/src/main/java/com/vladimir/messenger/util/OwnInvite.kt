package com.vladimir.messenger.util

import android.content.Context
import com.vladimir.messenger.data.referral.ReferralWire
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import java.net.URLEncoder

/**
 * Ссылка-приглашение на СВОЙ профиль — один источник для всего приложения.
 *
 * Зачем отдельно: ссылку строили в трёх местах по-своему (ShareProfileViewModel,
 * диалог «Поделиться приглашением» в списке чатов через RustBridge.generateInvite,
 * экран настроек), и в разных экранах владелец видел разные строки. Здесь один
 * формат p2pmessenger://add?node_id=...&name=..., который уже понимают
 * InviteLinkParser и QR-сканер.
 */
object OwnInvite {

    private const val PREFS = "p2p_prefs"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    /** Имя владельца из настроек; пустая строка, если профиль ещё не заполнен. */
    fun displayName(context: Context): String = prefs(context)
        .getString(KEY_DISPLAY_NAME, "")
        .orEmpty()

    /**
     * Ссылка на свой профиль или null, пока узел не создан.
     * Null бывает на первом запуске, до генерации ключей.
     */
    fun link(context: Context): String? {
        val app = context.applicationContext
        val nodeId = prefs(app).getString(KEY_NODE_ID, "").orEmpty()
        if (nodeId.isBlank()) return null
        val name = URLEncoder.encode(displayName(app), "UTF-8")
        val base = "p2pmessenger://add?node_id=$nodeId&name=$name"
        // Подписанный токен превращает обычную ссылку в приглашение: по нему
        // пригласивший потом зачтёт друга (см. data/referral). Если подпись
        // недоступна, ссылка остаётся рабочей, просто без начисления ранга.
        val token = signedToken(app, nodeId) ?: return base
        return base + "&" + ReferralInviteLink.TOKEN_PARAMETER + "=" + ReferralWire.encode(token)
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
