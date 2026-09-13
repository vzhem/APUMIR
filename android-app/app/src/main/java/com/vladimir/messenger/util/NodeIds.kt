package com.vladimir.messenger.util

/**
 * Что считается идентификатором узла (node id) и что - мусором, попавшим в поле
 * отправителя.
 *
 * Идентификатор узла всегда выглядит как `pk_` + шестнадцатеричная строка
 * (32 или 64 знака: `crypto_ffi.rs`, `referral.rs`). Ни один настоящий
 * собеседник не присылает сообщение с другим отправителем.
 *
 * Зачем отдельная проверка. Транспорт ядра разбирает входящую строку на
 * четыре поля `sender|messageId|chatId|text`, не проверяя первое поле. Стоило
 * одному пути отправить строку без конверта (голосовой фолбэк звонка слал
 * `APUCALL1|ab|…` как есть, v11.70.14-v11.70.17), и отправителем становилось
 * слово `APUCALL1`, а приёмник заводил контакт-призрак «Contact APUCALL1» с
 * кусками голосового пакета вместо переписки. Здесь такой отправитель
 * опознаётся и отбрасывается до любого разбора, а уже заведённые призраки
 * находятся по паре «id не узла + имя-заглушка от этого id».
 */
object NodeIds {

    const val PREFIX = "pk_"

    /**
     * Самый короткий допустимый хвост после `pk_`. Настоящие id - 32 или 64
     * знака; порог взят из уже работающего правила LAN-канала
     * (`LanDirectChannel`: длина id не меньше 10), чтобы не быть строже него.
     */
    private const val MIN_BODY = 7

    /** Верх - как у custody-пакетов (`FileCustodyPdu.MAX_NODE_ID_BYTES`). */
    private const val MAX_BODY = 128

    /**
     * Похоже ли значение на идентификатор узла: `pk_` и от 7 до 128 букв или
     * цифр без разделителей. Строже (только hex) нарочно не делаем: цена
     * ложного отказа - молча потерянное сообщение живого человека.
     */
    fun isNodeId(value: String?): Boolean {
        if (value == null || !value.startsWith(PREFIX)) return false
        val body = value.length - PREFIX.length
        if (body < MIN_BODY || body > MAX_BODY) return false
        for (i in PREFIX.length until value.length) {
            val c = value[i]
            val ok = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
            if (!ok) return false
        }
        return true
    }

    /** Имя-заглушка, которое приёмник даёт контакту, заведённому по первому сообщению. */
    fun autoName(nodeId: String): String = "Contact " + nodeId.takeLast(8)

    /**
     * Контакт-призрак: заведён автоматически (имя - заглушка от его же id),
     * а id - не узел. Записи со ссылкой вместо id (старые контакты вида
     * `p2pmessenger://add?node=pk_…`) не трогаем: в них есть `pk_`, и за ними
     * стоит настоящий человек. Переименованный владельцем контакт тоже не
     * трогаем - имя уже не заглушка.
     */
    fun isStrayAutoContact(id: String?, displayName: String?): Boolean {
        if (id == null || displayName == null || id.isBlank()) return false
        if (isNodeId(id) || id.contains(PREFIX)) return false
        return displayName.trim() == autoName(id)
    }
}
