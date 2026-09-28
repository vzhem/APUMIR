package com.vladimir.messenger.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

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

    /** Текст приглашения в группу/канал: коротко и сразу со ссылкой
     *  отдельной строкой. Раунд 162: для канала пишем «канал» (владелец:
     *  «приглашение в канал пишет что в группу»). */
    fun groupInviteText(groupTitle: String, link: String, isChannel: Boolean = false): String {
        val title = groupTitle.trim()
        val head = when {
            title.isBlank() && isChannel -> "Присоединяйся к моему каналу в APU."
            title.isBlank() -> "Присоединяйся к моей группе в APU."
            isChannel -> "Присоединяйся к каналу «$title» в APU."
            else -> "Присоединяйся к группе «$title» в APU."
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

    /**
     * Поделиться приглашениями сразу в несколько групп или каналов.
     * Раунд 198: [attachApk] - приложить установочный APK (получателю извне
     * APU может не быть; по умолчанию выключено - зовущий решает галочкой).
     */
    fun shareGroupInvites(
        context: Context,
        invites: List<Pair<String, String>>,
        attachApk: Boolean = false,
    ) {
        if (invites.isEmpty()) return
        val text = groupsInviteText(invites)
        if (attachApk) shareTextWithApk(context, text, "Пригласить в группу")
        else shareText(context, text, "Пригласить в группу")
    }

    /**
     * Поделиться приглашением в группу/канал (раунд 162: честное слово).
     * Раунд 198: [attachApk] - приложить установочный APK для получателя
     * вне APU.
     */
    fun shareGroupInvite(
        context: Context,
        groupTitle: String,
        link: String,
        isChannel: Boolean = false,
        attachApk: Boolean = false,
    ) {
        val text = groupInviteText(groupTitle, link, isChannel)
        val title = if (isChannel) "Пригласить в канал" else "Пригласить в группу"
        if (attachApk) shareTextWithApk(context, text, title)
        else shareText(context, text, title)
    }

    /**
     * Поделиться приглашением в APUMIR.
     *
     * Раунд 197 (владелец): друг приглашается ИЗ ДРУГОЙ СЕТИ - к сообщению
     * прикладываем свежий APK (наш установленный файл, он на каждом телефоне
     * и всегда ровно той версии, что у нас). Друг ставит приложение прямо
     * из входящего сообщения - сайт и GitHub не нужны вовсе. Копия в кэш
     * с понятным именем APU-v<версия>.apk (у base.apk некрасивое имя);
     * копируем в фоне - 40 МБ не должны морозить интерфейс; любая неудача
     * (нет файла, нет места, приложение не открылось) - обычный текст.
     */
    /** Раунд 200: [attachApk] - по галочке (по умолчанию ВКЛ, решение владельца). */
    fun shareInvite(
        context: Context,
        displayName: String,
        contactLink: String,
        attachApk: Boolean = true,
    ) {
        val text = inviteText(displayName, contactLink)
        if (attachApk) shareTextWithApk(context, text, "Пригласить в APUMIR")
        else shareText(context, text, "Пригласить в APUMIR")
    }

    /** Текст + установочный APK; не вышло - обычный текст, как раньше. */
    private fun shareTextWithApk(context: Context, text: String, title: String) {
        Thread {
            val shared = runCatching { shareApkWithText(context, text, title) }
                .onFailure { android.util.Log.w("AppShare", "apk share failed: ${it.message}") }
                .getOrDefault(false)
            if (!shared) {
                runCatching { shareText(context, text, title) }
                // Раунд 199 (владелец: «файл не прикрепился»): молчаливый
                // fallback выглядит как «галочка не работает». Говорим честно.
                runCatching {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context, "Файл приложить не вышло - отправлен текст со ссылкой",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }.start()
    }

    /** Приложить установленный APK к тексту приглашения. false - не вышло. */
    private fun shareApkWithText(context: Context, text: String, title: String): Boolean {
        val src = File(context.applicationInfo.sourceDir)
        if (!src.isFile) return false
        // Раунд 200: versionName уже несёт «v» (v11.74.94) - не удваиваем.
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { !it.isNullOrBlank() }?.removePrefix("v") ?: "latest"
        val dir = File(context.cacheDir, "invite").apply { mkdirs() }
        val named = File(dir, "APU-v$version.apk")
        if (!named.isFile || named.length() != src.length()) {
            // Свежая копия с понятным именем; старые версии заодно подчищаем.
            val tmp = File(dir, named.name + ".tmp")
            if (tmp.exists()) tmp.delete()
            src.copyTo(tmp, overwrite = false)
            if (named.exists()) named.delete()
            if (!tmp.renameTo(named)) {
                tmp.delete()
                return false
            }
            dir.listFiles { file -> file.name.endsWith(".apk") && file != named }
                ?.forEach { it.delete() }
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", named,
        )
        // Раунд 199: БЕЗ clipData меню «Поделиться» на части Android не
        // пробрасывает разрешение на чтение вложения целевому приложению -
        // то молча показывало один текст, файл «не прикреплялся».
        // clipData + флаг на сам chooser - канонический рецепт из документации.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        return true
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
