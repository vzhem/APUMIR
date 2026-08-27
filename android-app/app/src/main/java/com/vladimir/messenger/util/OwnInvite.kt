package com.vladimir.messenger.util

import android.content.Context
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
        val nodeId = prefs(context).getString(KEY_NODE_ID, "").orEmpty()
        if (nodeId.isBlank()) return null
        val name = URLEncoder.encode(displayName(context), "UTF-8")
        return "p2pmessenger://add?node_id=$nodeId&name=$name"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
