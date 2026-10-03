package com.vladimir.messenger.util

// =============================================================================
// GROUPINVITECARDSENDER.KT — приглашение в группу/канал КАРТОЧКОЙ
// =============================================================================
// Раунд 189. Владелец: «такую же карточку рассылать друзьям ... жирным
// название, ниже описание, ещё ниже пузырь: "Вступить" (группа) /
// "Подписаться" (канал)». Текст остаётся человекочитаемым: старые сборки
// увидят обычное сообщение со ссылкой, новые рисуют карточку.
// =============================================================================

/** Распознанная карточка приглашения в сообщении. */
data class GroupInviteCard(
    /** true - канал (кнопка «Подписаться»), false - группа («Вступить»). */
    val isChannel: Boolean,
    val title: String,
    val about: String,
    val link: String,
    /** Раунд 218: установочная ссылка (если в тексте была) для кнопки «Скачать APU». */
    val apkLink: String? = null,
)

object GroupInviteCardSender {

    private val LINK_PREFIXES = listOf(
        "https://", "http://", "apu://", "p2pmessenger://", "p2p://",
    )

    /** Собрать текст карточки: заголовок, описание (если есть), ссылка. */
    fun build(isChannel: Boolean, title: String, about: String, link: String): String {
        val header = if (isChannel) "Канал" else "Группа"
        val sb = StringBuilder("$header «${title.trim()}» в APU.")
        val trimmedAbout = about.trim()
        if (trimmedAbout.isNotEmpty()) {
            sb.append("\n").append(trimmedAbout.take(300))
        }
        sb.append("\n").append(link)
        return sb.toString()
    }

    /**
     * Разобрать сообщение-приглашение. Понимает и прежние форматы, так что
     * карточкой рисуются и приглашения, отправленные старыми сборками.
     */
    fun parseCard(content: String): GroupInviteCard? {
        val lines = content.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val first = lines.first()

        val isChannel: Boolean
        when {
            (first.startsWith("Группа «") || first.startsWith("Канал «")) &&
                first.contains("» в APU") -> isChannel = first.startsWith("Канал")
            first.startsWith("Присоединяйся к группе «") ||
                first.startsWith("Присоединяйся к каналу «") -> {
                if (!first.contains("в APU")) return null
                isChannel = first.contains("каналу")
            }
            first.startsWith("Приглашение в группу «") ||
                first.startsWith("Приглашение в канал «") -> isChannel = first.contains("канал")
            else -> return null
        }
        val title = first.substringAfter("«", "").substringBefore("»").trim()
        if (title.isEmpty()) return null

        // Раунд 218: ссылка приглашения идёт ПЕРВОЙ - после неё бывают
        // «Скачать APU:» и установочная ссылка. Раньше брали последнюю и
        // кнопкой «Вступить» становилась загрузка APK.
        val linkLine = lines.firstOrNull { line ->
            LINK_PREFIXES.any { line.startsWith(it) }
        } ?: return null

        // Раунд 218: установочная ссылка - следующая после ссылки приглашения;
        // служебную строку «Открой ссылку...» с карточки убираем.
        val apkLink = lines.drop(lines.indexOf(linkLine) + 1)
            .firstOrNull { line -> LINK_PREFIXES.any { line.startsWith(it) } }

        val about = lines.subList(1, lines.indexOf(linkLine).coerceAtLeast(1))
            .filterNot { it.startsWith("Открой ссылку") }
            .joinToString(" ") { it }
            .removePrefix("Присоединяйся к группе в APU.")
            .removePrefix("Присоединяйся к каналу в APU.")
            .trim()

        return GroupInviteCard(
            isChannel = isChannel,
            title = title,
            about = about.take(300),
            link = linkLine,
            apkLink = apkLink,
        )
    }

    /**
     * Раунд 218: разобрать МУЛЬТИ-приглашение - «Присоединяйся к моим
     * группам/сообществам в APU.» и дальше блоки «Канал «Название»:» /
     * «Группа «Название»:» (старые сборки пишут просто «Название:») со
     * ссылкой отдельной строкой в каждом, в конце «Скачать APU:» + ссылка.
     * Понимает и прежний формат без признака канала (кнопка будет
     * «Вступить», вход всё равно определит правильный тип).
     */
    fun parseMultiCard(content: String): MultiInviteCard? {
        val lines = content.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return null
        val first = lines.first()
        if (!first.startsWith("Присоединяйся к моим ")) return null
        if (!first.contains(" в APU")) return null

        fun linkAt(index: Int): String? {
            val line = lines.getOrNull(index) ?: return null
            return if (LINK_PREFIXES.any { line.startsWith(it) }) line else null
        }

        val items = mutableListOf<GroupInviteRef>()
        var apkLink: String? = null
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("Скачать APU")) {
                val link = linkAt(i + 1)
                if (link != null) {
                    apkLink = link
                    i += 2
                    continue
                }
            }
            if (line.endsWith(":")) {
                val link = linkAt(i + 1)
                if (link != null) {
                    var raw = line.removeSuffix(":").trim()
                    var isChannel = false
                    if (raw.startsWith("Канал «") && raw.endsWith("»")) {
                        isChannel = true
                        raw = raw.removePrefix("Канал «").removeSuffix("»")
                    } else if (raw.startsWith("Группа «") && raw.endsWith("»")) {
                        raw = raw.removePrefix("Группа «").removeSuffix("»")
                    }
                    if (raw.isNotEmpty()) items.add(GroupInviteRef(raw, link, isChannel))
                    i += 2
                    continue
                }
            }
            i += 1
        }
        if (items.isEmpty()) return null
        return MultiInviteCard(items, apkLink)
    }
}

/** Раунд 218: одно сообщество в мульти-приглашении (с признаком «канал»). */
data class GroupInviteRef(
    val title: String,
    val link: String,
    val isChannel: Boolean,
)

/** Раунд 218: разобранное мульти-приглашение: сообщества и ссылка на APK. */
data class MultiInviteCard(
    val items: List<GroupInviteRef>,
    val apkLink: String?,
)
