package com.vladimir.messenger.data.group

/** Роль участника. Владелец группы — всегда OWNER, его права не ограничиваются маской. */
object GroupRole {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"

    fun isOwner(role: String): Boolean = role == OWNER
    fun isAdminOrOwner(role: String): Boolean = role == OWNER || role == ADMIN

    fun normalize(role: String?): String = when (role) {
        OWNER, ADMIN, MEMBER -> role
        else -> MEMBER
    }
}

/**
 * Права в группе. Две независимые битовые маски:
 *  - [Admin] — что разрешено администратору (хранится в group_members.permissions у ADMIN);
 *  - [Member] — что разрешено обычным участникам (общая политика группы).
 *
 * Владелец группы имеет все права администратора безусловно: маска у OWNER
 * хранится для отображения, но при проверках не ограничивает.
 */
object GroupPermissions {

    data class Entry(val flag: Long, val title: String, val hint: String)

    object Admin {
        const val CHANGE_INFO = 1L shl 0
        const val DELETE_MESSAGES = 1L shl 1
        const val BAN_USERS = 1L shl 2
        const val INVITE_USERS = 1L shl 3
        const val PIN_MESSAGES = 1L shl 4
        const val MANAGE_TOPICS = 1L shl 5
        const val ADD_ADMINS = 1L shl 6
        const val REMAIN_ANONYMOUS = 1L shl 7

        val ALL: Long = CHANGE_INFO or DELETE_MESSAGES or BAN_USERS or INVITE_USERS or
            PIN_MESSAGES or MANAGE_TOPICS or ADD_ADMINS or REMAIN_ANONYMOUS

        /**
         * Набор по умолчанию при назначении администратора: как в Telegram —
         * всё кроме удаления чужих сообщений, банов и назначения админов.
         */
        val DEFAULT: Long = CHANGE_INFO or INVITE_USERS or PIN_MESSAGES or MANAGE_TOPICS

        val entries: List<Entry> = listOf(
            Entry(CHANGE_INFO, "Изменять информацию", "Название, описание и ссылку группы"),
            Entry(DELETE_MESSAGES, "Удалять сообщения", "Удаление любых сообщений участников"),
            Entry(BAN_USERS, "Ограничивать участников", "Бан и исключение из группы"),
            Entry(INVITE_USERS, "Добавлять участников", "Приглашения и одобрение заявок"),
            Entry(PIN_MESSAGES, "Закреплять сообщения", "Без этого права закреп недоступен"),
            Entry(MANAGE_TOPICS, "Управлять темами", "Создание, переименование и закрытие тем"),
            Entry(ADD_ADMINS, "Добавлять администраторов", "Назначать и снимать администраторов"),
            Entry(REMAIN_ANONYMOUS, "Оставаться анонимным", "Писать от имени группы"),
        )
    }

    object Member {
        const val SEND_MESSAGES = 1L shl 0
        const val SEND_MEDIA = 1L shl 1
        const val SEND_STICKERS = 1L shl 2
        const val SEND_POLLS = 1L shl 3
        const val ADD_MEMBERS = 1L shl 4
        const val CHANGE_INFO = 1L shl 5

        val ALL: Long = SEND_MESSAGES or SEND_MEDIA or SEND_STICKERS or SEND_POLLS or
            ADD_MEMBERS or CHANGE_INFO

        /**
         * По умолчанию участники пишут, шлют медиа и стикеры, но НЕ приглашают
         * и НЕ меняют информацию о группе.
         *
         * Раньше в набор входил ADD_MEMBERS, и любой участник мог создавать и
         * отзывать ссылки-приглашения - так Владимир, не будучи
         * администратором, создавал и удалял ссылки. Право по-прежнему можно
         * выдать всем участникам вкладкой «Разрешения».
         */
        val DEFAULT: Long = SEND_MESSAGES or SEND_MEDIA or SEND_STICKERS or SEND_POLLS

        val entries: List<Entry> = listOf(
            Entry(SEND_MESSAGES, "Отправка сообщений", "Текст в любые открытые темы"),
            Entry(SEND_MEDIA, "Отправка медиа", "Фото, видео и файлы"),
            Entry(SEND_STICKERS, "Стикеры и GIF", "Анимированные вложения"),
            Entry(SEND_POLLS, "Опросы", "Создание опросов"),
            Entry(ADD_MEMBERS, "Добавлять участников", "Приглашать знакомых в группу"),
            Entry(CHANGE_INFO, "Изменять информацию", "Название и описание группы"),
        )
    }

    fun has(mask: Long, flag: Long): Boolean = mask and flag == flag

    fun withFlag(mask: Long, flag: Long, enabled: Boolean): Long =
        if (enabled) mask or flag else mask and flag.inv()

    /**
     * Закрепить сообщение может только владелец или администратор, которому
     * явно выдано право PIN_MESSAGES. Обычный участник — никогда.
     */
    fun canPinMessages(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.PIN_MESSAGES)
        else -> false
    }

    fun canDeleteAnyMessage(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.DELETE_MESSAGES)
        else -> false
    }

    fun canBan(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.BAN_USERS)
        else -> false
    }

    fun canInvite(role: String, adminMask: Long, memberMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.INVITE_USERS)
        else -> has(memberMask, Member.ADD_MEMBERS)
    }

    /**
     * Создавать, отзывать и удалять ссылки-приглашения может только владелец
     * или администратор с правом INVITE_USERS. Право участников ADD_MEMBERS
     * позволяет поделиться ссылкой, но не управлять чужими.
     */
    fun canManageInvites(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.INVITE_USERS)
        else -> false
    }

    fun canManageTopics(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.MANAGE_TOPICS)
        else -> false
    }

    fun canAddAdmins(role: String, adminMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.ADD_ADMINS)
        else -> false
    }

    fun canChangeInfo(role: String, adminMask: Long, memberMask: Long): Boolean = when (role) {
        GroupRole.OWNER -> true
        GroupRole.ADMIN -> has(adminMask, Admin.CHANGE_INFO)
        else -> has(memberMask, Member.CHANGE_INFO)
    }

    fun canSendMessages(role: String, memberMask: Long, isBanned: Boolean): Boolean =
        !isBanned && has(memberMask, Member.SEND_MESSAGES)

    fun titles(mask: Long, entries: List<Entry>): List<String> =
        entries.filter { has(mask, it.flag) }.map { it.title }
}
