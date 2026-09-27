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

        val linkLine = lines.lastOrNull { line ->
            LINK_PREFIXES.any { line.startsWith(it) }
        } ?: return null

        val about = lines.subList(1, lines.indexOf(linkLine).coerceAtLeast(1))
            .joinToString(" ") { it }
            .removePrefix("Присоединяйся к группе в APU.")
            .removePrefix("Присоединяйся к каналу в APU.")
            .trim()

        return GroupInviteCard(
            isChannel = isChannel,
            title = title,
            about = about.take(300),
            link = linkLine,
        )
    }
}
