package com.vladimir.messenger.data.security

import java.util.UUID

/** Only replaceable control, never chat/ref/rank/delete/scheduled messages. */
object EphemeralControl {
    /** Префикс транспортного ID служебного кадра (см. [transportId]). */
    private const val TRANSPORT_PREFIX = "eph1-"

    fun isReplaceable(text: String): Boolean {
        // Periodic game snapshots are disposable; never grow the durable chat backlog.
        if (text.startsWith(com.vladimir.messenger.data.contactreset.ContactResetWire.PREFIX)) return true
        if (text.startsWith("APUTYP1|APUGAME1|")) return true
        if (text == "APUTYP1|0" || text == "APUTYP1|1") return true
        if (text.startsWith("APUGRP1|typ|")) {
            val parts = text.split('|', limit = 6)
            return parts.size == 5 && parts[4] in setOf("0", "1")
        }
        // Анонсы и просьбы видеоаватаров — служебный конверт (avv1), как контакт-
        // сброс: не растят долгий журнал и не теснят сообщения при повторах.
        if (text.startsWith("APUGRP1|avv1|")) return true
        // Привязка и замена профиля на том же устройстве (dva1) — заменяемый служебный пакет.
        if (text.startsWith(DeviceProfileAnchor.Wire.PREFIX)) return true
        val parts = text.split('|', limit = 3)
        return parts.size >= 2 && parts[0] in setOf("APUGIF1", "APUSTK1") &&
            parts[1] in setOf("ask", "have", "want", "thumb", "thmb")
    }

    /** Stable for retry of the same caller ID/recipient; private body is not hashed/exported. */
    fun transportId(id: String, peer: String, text: String): String = if (isReplaceable(text))
        TRANSPORT_PREFIX + UUID.nameUUIDFromBytes(("apu-control:$peer:$id").toByteArray(Charsets.UTF_8)) else id

    /**
     * Это транспортный ID, выданный [transportId], а не ID сообщения переписки.
     *
     * У служебного кадра строки в чате не бывает, поэтому квитанция о его
     * доставке — единственное доказательство, что он дошёл. Отчёт с телефона на
     * 517 показал, что без этого признака такая квитанция не снимала удержание
     * резервной копии: «ACK без локальной строки=1004» при живых
     * «delivery ACK received for eph1-[id]».
     *
     * Обычный ID переписки префикса не носит, а `UUID.fromString` отсекает всё,
     * что не канонический UUID: совпадение «на всякий случай» невозможно.
     */
    fun isTransportId(id: String): Boolean =
        id.startsWith(TRANSPORT_PREFIX) &&
            runCatching { UUID.fromString(id.substring(TRANSPORT_PREFIX.length)) }.isSuccess
}
