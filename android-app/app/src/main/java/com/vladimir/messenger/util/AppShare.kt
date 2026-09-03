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

    /**
     * Куда ставить приложение.
     *
     * Ссылка ведёт НА ФАЙЛ, а не на страницу релиза: latest/download/<имя
     * ассета> — постоянный адрес GitHub для последней публикации, а ассет
     * называется app-release.apk (его собирает рабочий процесс релиза, см.
     * scripts/make-release.ps1). Получателю не надо искать файл в списке.
     */
    const val INSTALL_LINK =
        "https://github.com/vzhem/APUMIR/releases/latest/download/app-release.apk"

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
            "Добавь меня в контакты — открой ссылку или вставь её в APU " +
            "(можно вставить всё сообщение целиком):\n" +
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
            "Открой ссылку или вставь её в APU (можно вставить всё сообщение целиком):\n" +
            link + "\n\n" +
            "Скачать APU:\n" +
            INSTALL_LINK
    }

    /**
     * Текст приглашения сразу в несколько групп или каналов: по строке на
     * каждую ссылку, ссылка всегда с начала строки - иначе мессенджеры делают
     * кликабельной только её часть.
     */
    fun groupsInviteText(invites: List<Pair<String, String>>): String {
        if (invites.size == 1) {
            return groupInviteText(invites[0].first, invites[0].second)
        }
        val sb = StringBuilder("Присоединяйся к моим группам в APU.\n")
        for (item in invites) {
            val title = item.first.trim().ifBlank { "Группа" }
            sb.append("\n").append(title).append(":\n").append(item.second).append("\n")
        }
        sb.append("\nСкачать APU:\n").append(INSTALL_LINK)
        return sb.toString()
    }

    /** Поделиться приглашениями сразу в несколько групп или каналов. */
    fun shareGroupInvites(context: Context, invites: List<Pair<String, String>>) {
        if (invites.isEmpty()) return
        shareText(context, groupsInviteText(invites), "Пригласить в группу")
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
