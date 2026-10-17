package com.vladimir.messenger.util

/** Текст для шторки Android. Исходное сообщение и ссылка в чате не меняются. */
object MessageNotificationText {
    fun format(text: String): String {
        GroupInviteCardSender.parseCard(text)?.let { card ->
            return buildList {
                add(if (card.isChannel) "Приглашение в канал «${card.title}»" else "Приглашение в группу «${card.title}»")
                add(when (card.isPublic) {
                    true -> if (card.isChannel) "Открытый канал" else "Открытая группа"
                    false -> if (card.isChannel) "Закрытый канал" else "Закрытая группа"
                    null -> "Тип доступа не указан в приглашении"
                })
                add(card.about.ifBlank { "Описание не указано" })
                add(when (card.needsApproval) {
                    true -> "Вступление по заявке — требуется одобрение"
                    false -> "Вступление без одобрения"
                    null -> "Условия вступления не указаны в приглашении"
                })
            }.joinToString("\n")
        }
        GroupInviteCardSender.parseMultiCard(text)?.let { card ->
            return buildList {
                add("Приглашение в сообщества APU")
                card.items.forEach { item ->
                    add(if (item.isChannel) "Канал «${item.title}»" else "Группа «${item.title}»")
                }
            }.joinToString("\n")
        }
        // Обычные сообщения, в том числе ссылки, оставляем как есть.
        return text
    }
}
