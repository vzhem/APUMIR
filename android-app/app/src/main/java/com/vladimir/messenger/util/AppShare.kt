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
     * Текст приглашения.
     *
     * Ссылки всегда стоят ОТДЕЛЬНОЙ строкой и ничего к себе не приклеивают:
     * мессенджеры распознают ссылку целиком только тогда, когда она начинается
     * с начала строки и заканчивается переводом строки. Внутри длинной фразы
     * ссылка переносится по словам и кликабельной становится только её часть.
     */
    fun inviteText(displayName: String, contactLink: String): String {
        val who = displayName.trim().ifBlank { "APU" }
        return "Привет! Это $who в APU — мессенджере без серверов: " +
            "сообщения и файлы идут напрямую между телефонами.\n\n" +
            "Добавь меня в контакты — открой ссылку или вставь её в APU:\n" +
            contactLink + "\n\n" +
            "Скачать APU:\n" +
            INSTALL_LINK
    }

    /** Текст приглашения в группу: коротко и сразу со ссылкой отдельной строкой. */
    fun groupInviteText(groupTitle: String, link: String): String {
        val title = groupTitle.trim()
        val head = if (title.isBlank()) {
            "Присоединяйся к моей группе в APU."
        } else {
            "Присоединяйся к группе «$title» в APU."
        }
        return head + "\n\n" +
            "Открой ссылку или вставь её в APU:\n" +
            link + "\n\n" +
            "Скачать APU:\n" +
            INSTALL_LINK
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
