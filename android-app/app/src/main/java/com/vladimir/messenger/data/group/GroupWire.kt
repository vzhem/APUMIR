package com.vladimir.messenger.data.group

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Проводной конверт групповых событий.
 *
 * Группы живут поверх существующего транспорта 1:1, поэтому каждое групповое
 * событие — это обычный текст с префиксом APUGRP1. Префикс не пересекается с
 * APULAN1 / APULANHS1 (файлы и LAN-сигналинг) и разбирается тем же приёмником,
 * что и остальной трафик.
 *
 * Все поля, где возможен разделитель `|` или перевод строки, кодируются
 * base64url без дополнения, поэтому формат однозначен.
 *
 * Разбор строгий: любое отклонение даёт null, пакет молча отбрасывается.
 */
object GroupWire {

    const val PREFIX = "APUGRP1"

    /** Ограничение на длину конверта, чтобы один пакет не занимал всю очередь. */
    const val MAX_ENVELOPE_BYTES = 16 * 1024

    const val KIND_MESSAGE = "msg"
    const val KIND_TOPIC = "topic"
    const val KIND_JOIN_REQUEST = "req"
    const val KIND_JOIN_DECISION = "reqd"
    const val KIND_PIN = "pin"
    const val KIND_ROSTER = "roster"
    const val KIND_GROUP_DELETED = "grpdel"
    const val KIND_GROUP_INFO = "info"
    const val KIND_TOPICS = "topics"
    const val KIND_TOPICS_REQUEST = "treq"
    /**
     * Запрос списка участников.
     *
     * Телефон, который получил сообщение от незнакомого участника, просит
     * группу прислать состав: иначе вместо имени он показывает обрывок
     * идентификатора («буквы и цифры»).
     */
    const val KIND_ROSTER_REQUEST = "rreq"
    const val KIND_KICK = "kick"

    const val DECISION_APPROVED = "APPROVED"
    const val DECISION_REJECTED = "REJECTED"

    sealed class Packet {
        data class Message(
            val groupId: String,
            val topicId: String,
            val text: String,
            /**
             * Идентификатор сообщения у ОТПРАВИТЕЛЯ.
             *
             * Без него у каждого получателя сообщение ложилось под своим
             * транспортным id, и пакет Pin (в нём id отправителя) не находил
             * строку: закреп видела только тот, кто закреплял. Теперь id один
             * на всю группу. У старых конвертов поля нет - оно пустое.
             */
            val messageId: String = "",
            /**
             * Имя отправителя на его собственном телефоне.
             *
             * Список участников рассылается только при изменении состава, и
             * телефон, который в тот момент был не в сети, имени так и не
             * узнаёт. Поэтому имя ездит вместе с сообщением: получил
             * сообщение - сразу знаешь, кто написал.
             */
            val senderName: String = "",
        ) : Packet()

        data class TopicCreated(
            val groupId: String,
            val topicId: String,
            val name: String,
        ) : Packet()

        data class JoinRequest(
            val groupId: String,
            val displayName: String,
            val note: String,
            /**
             * Slug ссылки, по которой пришёл человек. По нему владелец находит
             * пригласительную запись и решает, пускать сразу или ждать одобрения.
             * У заявок старого образца slug пустой.
             */
            val slug: String = "",
        ) : Packet()

        data class JoinDecision(
            val groupId: String,
            val nodeId: String,
            val approved: Boolean,
        ) : Packet()

        data class Pin(
            val groupId: String,
            val topicId: String,
            val messageId: String,
            val pinned: Boolean,
        ) : Packet()

        /**
         * Владелец удалил группу: получатели стирают свою копию.
         * Принимаем только от ownerId группы (проверка в приёмнике).
         */
        data class GroupDeleted(val groupId: String) : Packet()

        /** Список участников: строки "nodeId,displayName,role" через ';'. */
        data class Roster(
            val groupId: String,
            val entries: List<RosterEntry>,
        ) : Packet()

        /**
         * Карточка группы: её шлёт владелец человеку, которого только что
         * принял. Без неё у нового участника нет ни названия, ни владельца —
         * создать локальную строку группы не из чего, и вступление «проходит»,
         * но группа не появляется в списке.
         *
         * Принимаем только от `ownerId` (проверка в приёмнике).
         */
        /**
         * Список тем группы. Шлётся новому участнику вместе с карточкой группы:
         * темы создаются пакетом TopicCreated в момент создания, и тот, кто
         * вступил позже, без такого списка видит пустой чат без тем.
         */
        data class Topics(
            val groupId: String,
            val entries: List<TopicEntry>,
        ) : Packet()

        /**
         * Участник просит прислать список тем. Нужно вступившим позже: темы
         * создаются пакетом TopicCreated, и опоздавший их не застал.
         */
        data class TopicsRequest(val groupId: String) : Packet()

        data class RosterRequest(val groupId: String) : Packet()

        /**
         * Участника исключили или ограничили. Без этого пакета он так и видит
         * группу у себя: состав обновляется рассылкой, а самого исключённого
         * она не касается.
         */
        data class Kick(val groupId: String, val nodeId: String) : Packet()

        data class GroupInfo(
            val groupId: String,
            val title: String,
            val about: String,
            val ownerId: String,
            val inviteSlug: String,
            val isPublic: Boolean,
            val topicsEnabled: Boolean,
        ) : Packet()
    }

    data class RosterEntry(val nodeId: String, val displayName: String, val role: String)

    data class TopicEntry(val topicId: String, val name: String)

    fun isGroupPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_BYTES && text.startsWith("$PREFIX|")

    // ── Сборка ────────────────────────────────────────────────────────────────

    fun buildMessage(
        groupId: String,
        topicId: String,
        text: String,
        messageId: String = "",
        senderName: String = "",
    ): String {
        val base = "$PREFIX|$KIND_MESSAGE|$groupId|$topicId|${encode(text)}"
        // Поля добавляются по порядку и только если есть что добавить:
        // конверт из 5 частей понимают даже телефоны с прошлой версией.
        if (messageId.isBlank() && senderName.isBlank()) return base
        val withId = "$base|${encode(messageId)}"
        return if (senderName.isBlank()) withId else "$withId|${encode(senderName)}"
    }

    /** «Пришлите состав группы» - лечит неизвестные имена у отправителя. */
    fun buildRosterRequest(groupId: String): String = "$PREFIX|$KIND_ROSTER_REQUEST|$groupId"

    fun buildTopicCreated(groupId: String, topicId: String, name: String): String =
        "$PREFIX|$KIND_TOPIC|$groupId|$topicId|${encode(name)}"

    fun buildJoinRequest(
        groupId: String,
        displayName: String,
        note: String,
        slug: String = "",
    ): String =
        "$PREFIX|$KIND_JOIN_REQUEST|$groupId|${encode(displayName)}|${encode(note)}|" +
            encode(slug)

    fun buildJoinDecision(groupId: String, nodeId: String, approved: Boolean): String =
        "$PREFIX|$KIND_JOIN_DECISION|$groupId|$nodeId|" +
            if (approved) DECISION_APPROVED else DECISION_REJECTED

    fun buildPin(groupId: String, topicId: String, messageId: String, pinned: Boolean): String =
        "$PREFIX|$KIND_PIN|$groupId|$topicId|$messageId|${if (pinned) 1 else 0}"

    fun buildGroupDeleted(groupId: String): String =
        "$PREFIX|$KIND_GROUP_DELETED|$groupId"

    fun buildRoster(groupId: String, entries: List<RosterEntry>): String {
        val csv = entries.joinToString(";") {
            "${encode(it.nodeId)},${encode(it.displayName)},${it.role}"
        }
        return "$PREFIX|$KIND_ROSTER|$groupId|$csv"
    }

    /** Список тем: строки "topicId,name" через ';'. */
    fun buildTopics(groupId: String, entries: List<TopicEntry>): String {
        val csv = entries.joinToString(";") {
            "${encode(it.topicId)},${encode(it.name)}"
        }
        return "$PREFIX|$KIND_TOPICS|$groupId|$csv"
    }

    fun buildTopicsRequest(groupId: String): String = "$PREFIX|$KIND_TOPICS_REQUEST|$groupId"

    fun buildKick(groupId: String, nodeId: String): String = "$PREFIX|$KIND_KICK|$groupId|$nodeId"

    /** Карточка группы для нового участника. Все текстовые поля — base64url. */
    fun buildGroupInfo(
        groupId: String,
        title: String,
        about: String,
        ownerId: String,
        inviteSlug: String,
        isPublic: Boolean,
        topicsEnabled: Boolean,
    ): String =
        "$PREFIX|$KIND_GROUP_INFO|$groupId|" +
            encode(title) + "|" + encode(about) + "|" + encode(ownerId) + "|" +
            encode(inviteSlug) + "|" + (if (isPublic) 1 else 0) + "|" +
            if (topicsEnabled) 1 else 0

    // ── Разбор ────────────────────────────────────────────────────────────────

    fun parse(text: String?): Packet? {
        if (!isGroupPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val groupId = parts[2]
        if (groupId.isBlank()) return null

        return when (parts[1]) {
            KIND_MESSAGE -> if (parts.size in 5..7) {
                val body = decode(parts[4]) ?: return null
                // Конверты старого образца приходили без id и без имени.
                val senderMessageId = if (parts.size >= 6) decode(parts[5]).orEmpty() else ""
                val senderName = if (parts.size >= 7) decode(parts[6]).orEmpty() else ""
                Packet.Message(groupId, parts[3], body, senderMessageId, senderName)
            } else {
                null
            }

            KIND_ROSTER_REQUEST -> if (parts.size == 3) {
                Packet.RosterRequest(groupId)
            } else {
                null
            }

            KIND_TOPIC -> if (parts.size == 5) {
                val name = decode(parts[4]) ?: return null
                if (name.isBlank()) null else Packet.TopicCreated(groupId, parts[3], name)
            } else {
                null
            }

            KIND_JOIN_REQUEST -> if (parts.size == 5 || parts.size == 6) {
                val name = decode(parts[3]) ?: return null
                val note = decode(parts[4]) ?: return null
                // Заявки старого образца приходили без slug — считаем его пустым.
                val slug = if (parts.size == 6) decode(parts[5]).orEmpty() else ""
                if (name.isBlank()) null else Packet.JoinRequest(groupId, name, note, slug)
            } else {
                null
            }

            KIND_JOIN_DECISION -> if (parts.size == 5) {
                val nodeId = parts[3]
                when (parts[4]) {
                    DECISION_APPROVED -> Packet.JoinDecision(groupId, nodeId, true)
                    DECISION_REJECTED -> Packet.JoinDecision(groupId, nodeId, false)
                    else -> null
                }
            } else {
                null
            }

            KIND_PIN -> if (parts.size == 6) {
                val flag = parts[5]
                if (flag == "1") {
                    Packet.Pin(groupId, parts[3], parts[4], true)
                } else if (flag == "0") {
                    Packet.Pin(groupId, parts[3], parts[4], false)
                } else {
                    null
                }
            } else {
                null
            }

            KIND_GROUP_DELETED -> if (parts.size == 3) {
                Packet.GroupDeleted(groupId)
            } else {
                null
            }

            KIND_ROSTER -> if (parts.size == 4) {
                val entries = parseRoster(parts[3]) ?: return null
                Packet.Roster(groupId, entries)
            } else {
                null
            }

            KIND_TOPICS_REQUEST -> if (parts.size == 3) {
                Packet.TopicsRequest(groupId)
            } else {
                null
            }

            KIND_KICK -> if (parts.size == 4) {
                val nodeId = parts[3]
                if (nodeId.isBlank()) null else Packet.Kick(groupId, nodeId)
            } else {
                null
            }

            KIND_TOPICS -> if (parts.size == 4) {
                val entries = parseTopics(parts[3]) ?: return null
                Packet.Topics(groupId, entries)
            } else {
                null
            }

            KIND_GROUP_INFO -> if (parts.size == 9) {
                val title = decode(parts[3]) ?: return null
                val about = decode(parts[4]) ?: return null
                val ownerId = decode(parts[5]) ?: return null
                val slug = decode(parts[6]).orEmpty()
                val isPublic = parts[7]
                val topics = parts[8]
                if (ownerId.isBlank() || (isPublic != "0" && isPublic != "1") ||
                    (topics != "0" && topics != "1")
                ) {
                    null
                } else {
                    Packet.GroupInfo(
                        groupId = groupId,
                        title = title,
                        about = about,
                        ownerId = ownerId,
                        inviteSlug = slug,
                        isPublic = isPublic == "1",
                        topicsEnabled = topics == "1",
                    )
                }
            } else {
                null
            }

            else -> null
        }
    }

    private fun parseTopics(csv: String): List<TopicEntry>? {
        if (csv.isBlank()) return emptyList()
        val out = ArrayList<TopicEntry>()
        for (row in csv.split(';')) {
            if (row.isBlank()) continue
            val cells = row.split(',')
            if (cells.size != 2) return null
            val topicId = decode(cells[0]) ?: return null
            val name = decode(cells[1]) ?: return null
            if (topicId.isBlank()) return null
            out.add(TopicEntry(topicId, name))
        }
        return out
    }

    private fun parseRoster(csv: String): List<RosterEntry>? {
        if (csv.isBlank()) return emptyList()
        val out = ArrayList<RosterEntry>()
        for (row in csv.split(';')) {
            if (row.isBlank()) continue
            val cells = row.split(',')
            if (cells.size != 3) return null
            val nodeId = decode(cells[0]) ?: return null
            val displayName = decode(cells[1]) ?: return null
            if (nodeId.isBlank()) return null
            out.add(RosterEntry(nodeId, displayName, GroupRole.normalize(cells[2])))
        }
        return out
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
