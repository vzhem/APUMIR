package com.vladimir.messenger.util

/**
 * Тексты приглашений — как у топовых мессенджеров.
 *
 * Владелец 2026-10-30 (скрин приглашения в Telegram): «Сделай проще приглашение,
 * как у топовых мессенджеров». Раньше текст был инструкцией:
 *
 *   «Привет! Это Server10 в APU — мессенджере без серверов… Добавь меня в
 *   контакты — открой ссылку или вставь её в APU (можно вставить всё сообщение
 *   целиком): <ссылка>. Скачать APU: <ссылка>»
 *
 * Три беды: человек читает инструкцию вместо приглашения; в тексте две ссылки и
 * обе технические; установочный файл (APK) и так приложен к сообщению ниже, так
 * что строка «Скачать APU» — лишний шум.
 *
 * Теперь так, как это делают Telegram и WhatsApp: одно предложение, одно имя,
 * одна ссылка отдельной строкой. Ссылка нужна — без неё приглашённого не с чем
 * добавить в контакты, — но она больше не несёт личных данных в адресе
 * (см. `data.link.ServiceHost`).
 *
 * Строка со ссылкой отдельная и последняя: мессенджеры делают кликабельной
 * ссылку, только когда она стоит с начала строки целиком. Внутри длинной фразы
 * ссылка переносится по словам и кликабельной становится лишь её часть.
 *
 * Android-зависимостей нет — текст проверяется обычными JVM-тестами
 * (`InviteTextTest`).
 */
object InviteText {

    /** Подпись, если имя не задано: приглашение всё равно должно читаться. */
    private const val FALLBACK_NAME = "APU"

    /** Куда приглашают: подпись сообщества, ссылка и признак «канал». */
    data class Community(val title: String, val link: String, val isChannel: Boolean)

    /**
     * Личное приглашение в APU: «Привет! Это Иван. Я в APU — давай общаться.»
     * и ссылка добавления отдельной строкой.
     *
     * Файл приложения (APK) прикладывается к сообщению ниже — в тексте о нём
     * ничего не говорим.
     */
    fun personal(displayName: String, contactLink: String): String {
        val who = displayName.trim().ifBlank { FALLBACK_NAME }
        return "Привет! Это $who. Я в APU — давай общаться.\n\n" + contactLink.trim()
    }

    /**
     * Приглашение в группу или канал: одно предложение и ссылка отдельной
     * строкой, без советов «вставь ссылку в APU».
     */
    fun community(title: String, link: String, isChannel: Boolean): String =
        communityHead(title, isChannel) + "\n\n" + link.trim()

    /**
     * Приглашение сразу в несколько групп или каналов: «Группа «Двор»:» и
     * ссылка с начала следующей строки — иначе мессенджеры делают кликабельной
     * только часть ссылки.
     */
    fun communities(items: List<Community>): String {
        if (items.isEmpty()) return ""
        if (items.size == 1) {
            val one = items[0]
            return community(one.title, one.link, one.isChannel)
        }
        val sb = StringBuilder("Присоединяйся к моим сообществам в APU.")
        for (item in items) {
            val name = item.title.trim().ifBlank { if (item.isChannel) "Канал" else "Группа" }
            sb.append("\n\n").append(if (item.isChannel) "Канал «" else "Группа «")
                .append(name).append("»:\n").append(item.link.trim())
        }
        return sb.toString()
    }

    /** Первое предложение приглашения в группу или канал. */
    fun communityHead(title: String, isChannel: Boolean): String {
        val name = title.trim()
        return when {
            name.isBlank() && isChannel -> "Присоединяйся к моему каналу в APU."
            name.isBlank() -> "Присоединяйся к моей группе в APU."
            isChannel -> "Присоединяйся к каналу «$name» в APU."
            else -> "Присоединяйся к группе «$name» в APU."
        }
    }
}
