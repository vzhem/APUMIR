package com.vladimir.messenger.util

import android.content.Context
import android.content.Intent

/**
 * Единственная точка «поделиться» в приложении.
 *
 * Отдаём текст системному меню Android (Intent.ACTION_SEND): в нём уже есть
 * мессенджеры, почта, SMS и контакты владельца, поэтому «переслать другу»
 * работает везде одинаково и не требует своего списка контактов.
 */
object AppShare {

    /** Куда ставить приложение. Тот же адрес, что читает UpdateChecker. */
    const val INSTALL_LINK = "https://github.com/vzhem/APUMIR/releases/latest"

    /**
     * Текст приглашения. Всегда три строки: кто зовёт, ссылка на контакт,
     * где взять приложение — чтобы получатель мог открыть и то и другое.
     */
    fun inviteText(displayName: String, contactLink: String): String {
        val who = displayName.trim().ifBlank { "APUMIR" }
        return "Привет! Это $who в APUMIR — мессенджере без серверов: " +
            "сообщения и файлы идут напрямую между телефонами.\n" +
            "Добавь меня в контакты: $contactLink\n" +
            "Скачать приложение: $INSTALL_LINK"
    }

    /** Текст приглашения в группу: коротко и сразу со ссылкой. */
    fun groupInviteText(groupTitle: String, link: String): String {
        val title = groupTitle.trim()
        return if (title.isBlank()) {
            "Присоединяйся к группе в APUMIR: $link\nСкачать приложение: $INSTALL_LINK"
        } else {
            "Присоединяйся к группе «$title» в APUMIR: $link\nСкачать приложение: $INSTALL_LINK"
        }
    }

    /** Поделиться приглашением в группу. */
    fun shareGroupInvite(context: Context, groupTitle: String, link: String) {
        shareText(context, groupInviteText(groupTitle, link), "Пригласить в группу")
    }

    /** Поделиться приглашением в APUMIR. */
    fun shareInvite(context: Context, displayName: String, contactLink: String) {
        shareText(context, inviteText(displayName, contactLink), "Пригласить в APUMIR")
    }

    /** Поделиться произвольным текстом (например, ссылкой-приглашением в группу). */
    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }
}
