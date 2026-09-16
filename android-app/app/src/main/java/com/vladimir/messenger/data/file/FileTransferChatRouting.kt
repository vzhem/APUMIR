package com.vladimir.messenger.data.file

/**
 * Resolves the transport chat scope to the recipient phone's local chat ID.
 *
 * Chat IDs are device-local UUIDs, so a sender's chat ID must never be persisted as-is when the
 * recipient already has its own chat for that contact. Direct file QUIC frames deliberately carry
 * [DIRECT_TRANSPORT_SCOPE] instead of pretending that a remote chat UUID is portable.
 */
internal object FileTransferChatRouting {
    const val DIRECT_TRANSPORT_SCOPE = "direct"

    /**
     * Чужой файл на хранении (этап 7 роя) чата на этом телефоне не имеет:
     * строка помечается этой меткой, пузырь в переписке не рисуется, а
     * подтверждения хранения уходят с ней вместо id чата (транспорту чат
     * нужен только как метка).
     */
    const val CUSTODY_SCOPE = "custody"

    /**
     * Файл группы полосами (K2, v11.70.25): в манифесте вместо получателя
     * стоит метка группы `grp_<id>` (ядро, `file_transfer::is_group_scope`;
     * Kotlin строит её в `GroupFileMarker.scope`). Такой манифест один на
     * всех участников, и предложение с ним принимается от любого сида, у
     * которого рой просил файл, а не только от того, кто в нём записан
     * отправителем (это автор).
     */
    const val GROUP_SCOPE_PREFIX = "grp_"

    fun isGroupScope(recipient: String): Boolean = recipient.startsWith(GROUP_SCOPE_PREFIX)

    /** Метка группы для её идентификатора - как её строит автор копии. */
    fun groupScope(groupId: String): String = com.vladimir.messenger.util.GroupFileMarker.scope(groupId)

    /**
     * Returns the recipient's local chat when known. A non-direct transport scope is retained only
     * for the legacy path; the direct sentinel is never allowed to become a Room chat ID.
     */
    fun resolve(transportChatId: String, localChatId: String?): String? {
        localChatId?.takeIf { it.isNotBlank() }?.let { return it }
        return transportChatId.takeIf {
            it.isNotBlank() && it != DIRECT_TRANSPORT_SCOPE
        }
    }
}
