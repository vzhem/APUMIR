package com.vladimir.messenger.data.invite

import java.util.Base64

/**
 * Служебный пакет «ваше приглашение отклонено настройками получателя».
 *
 * Когда приглашение в группу/канал выбрасывается фильтром приватности
 * (InvitePrivacyStore), получатель молчит у себя, но отправителю уходит
 * этот пакет - чтобы человек понимал, почему приглашение не дошло:
 * NOT_CONTACT - «вы не в адресной книге, а приглашать могут только
 * контакты»; NOBODY - «получатель запретил приглашения всем».
 *
 * Формат: `APUINVREJ1|<c|n>|<название сообщества base64url>`; название
 * может быть пустым (мульти-приглашение). Пакет служебный: в переписку
 * строкой не ложится (см. ChatPreviews.SERVICE_PACKETS), на стороне
 * отправителя превращается в человеческую заметку в чате с получателем
 * и обычное уведомление.
 */
object InviteRejectWire {

    const val PREFIX = "APUINVREJ1|"

    enum class Reason { NOT_CONTACT, NOBODY }

    data class Notice(val reason: Reason, val title: String)

    fun build(reason: Reason, title: String): String {
        val code = if (reason == Reason.NOBODY) "n" else "c"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(title.trim().take(80).toByteArray(Charsets.UTF_8))
        return "$PREFIX$code|$encoded"
    }

    fun isPacket(raw: String?): Boolean = raw?.trim()?.startsWith(PREFIX) == true

    fun parse(raw: String?): Notice? {
        val packet = raw?.trim() ?: return null
        if (!packet.startsWith(PREFIX) || packet.length > 512) return null
        val parts = packet.split("|")
        if (parts.size != 3) return null
        val reason = when (parts[1]) {
            "c" -> Reason.NOT_CONTACT
            "n" -> Reason.NOBODY
            else -> return null
        }
        val title = runCatching {
            String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        }.getOrDefault("")
        return Notice(reason, title)
    }
}
