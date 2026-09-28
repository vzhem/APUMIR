package com.vladimir.messenger.util

// Раунд 203: кликабельный источник пересылки «Поделиться в APU».
// Первой строкой пересланного едет служебный маркер APUFWD1, второй -
// человекочитаемая шапка «↩ Переслано из «…»» (старые версии покажут её
// обычным текстом, как в v11.74.97). Пузыри лички и групп шапку рисуют
// сами (кликабельно) и прячут служебные строки; предпросмотры списков
// чистит ChatPreviews.

object ForwardMarker {
    const val MARKER = "APUFWD1|"
    const val HEADER = "↩ Переслано из «"

    /** Откуда переслано: p - друг (id = узел контакта), g - группа/канал. */
    data class Ref(
        val isGroup: Boolean,
        val id: String,
        val topicId: String,
        val label: String,
        /** Ссылка-приглашение: слаг, владелец, «это канал» - для «Вступить». */
        val slug: String = "",
        val ownerId: String = "",
        val isChannel: Boolean = false,
    )

    /** Куда открывать источник на телефоне читателя. */
    sealed class Open {
        data class Chat(val chatId: String, val contactName: String, val contactId: String) : Open()
        data class Group(val groupId: String, val topicId: String?) : Open()
        /** Группы/канала нет на телефоне - предложить вступить по ссылке. */
        data class Join(val link: String) : Open()
        data class Missing(val message: String) : Open()
    }

    /**
     * Тело пересланного: маркер, шапка для старых версий, пустая строка,
     * сам текст. Маркер и шапку пузыри прячут - рисуют свою шапку.
     */
    fun buildBody(
        isGroup: Boolean,
        id: String,
        topicId: String,
        label: String,
        text: String,
        slug: String = "",
        ownerId: String = "",
        isChannel: Boolean = false,
    ): String {
        val safeLabel = label.replace("|", "/").trim()
        val marker = if (isGroup) {
            "APUFWD1|g|" + id + "|" + topicId + "|" + slug + "|" + ownerId + "|" +
                (if (isChannel) "1" else "0") + "|" + safeLabel
        } else {
            "APUFWD1|p|" + id + "|" + safeLabel
        }
        return marker + "\n" + HEADER + safeLabel + "»" + "\n\n" + text
    }

    fun parseRef(content: String): Ref? {
        val first = content.lineSequence().firstOrNull() ?: return null
        if (!first.startsWith(MARKER)) return null
        val parts = first.split("|")
        if (parts.size < 4) return null
        return when (parts[1]) {
            "p" -> Ref(false, parts[2], "", parts.drop(3).joinToString("|"))
            "g" -> when {
                // Полный формат: слаг, владелец, признак канала, название.
                parts.size >= 8 -> Ref(
                    true,
                    parts[2],
                    parts[3],
                    parts.drop(7).joinToString("|"),
                    parts[4],
                    parts[5],
                    parts[6] == "1",
                )
                // Короткий (первые сборки): только тема и название.
                parts.size >= 5 -> Ref(true, parts[2], parts[3], parts.drop(4).joinToString("|"))
                else -> null
            }
            else -> null
        }
    }

    /** Есть ли пересылочная шапка (с маркером или старого формата v97). */
    fun hasHeader(content: String): Boolean {
        val first = content.lineSequence().firstOrNull() ?: return false
        return first.startsWith(HEADER) || first.startsWith(MARKER)
    }

    /** Имя источника из старой шапки без маркера: текст между «…». */
    fun plainHeaderLabel(content: String): String {
        val first = content.lineSequence().firstOrNull() ?: return ""
        return Regex("«(.*)»").find(first)?.groupValues?.get(1) ?: ""
    }

    /** Текст без служебного маркера и шапки - для отрисовки в пузыре. */
    fun stripHeader(content: String): String {
        val lines = content.split("\n")
        var i = 0
        if (lines.getOrNull(0)?.startsWith(MARKER) == true) i = 1
        if (lines.getOrNull(i)?.startsWith(HEADER) == true) i += 1
        if (i > 0 && lines.getOrNull(i)?.isBlank() == true) i += 1
        return if (i == 0) content else lines.drop(i).joinToString("\n")
    }
}
