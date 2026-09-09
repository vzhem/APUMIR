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

    /** Ключей в одном `pkeys`: владелец + до 15 администраторов. */
    const val MAX_POST_KEYS = 16

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
    const val KIND_DIRECTORY = "dir"
    const val KIND_NICK = "nick"
    /**
     * «Представься»: адресный запрос имени и аватара.
     *
     * Нужен, когда собеседник показан набором букв и цифр. Ждать роевую
     * рассылку @имени можно часами - она уходит раз в несколько часов, а
     * запрос решает вопрос за один круг.
     */
    const val KIND_WHOIS = "who"
    /** Аватар участника: маленький JPEG в base64. */
    const val KIND_AVAT = "avat"
    /**
     * Правка сообщения (поста канала). Принимается от автора сообщения и от
     * владельца группы; остальные пакеты правки отбрасываются.
     */
    const val KIND_EDIT = "edit"
    /**
     * «Пришлите последние посты»: вступивший позже просит владельца канала
     * прислать тексты и фотографии последних постов, а не только список тем.
     */
    const val KIND_POSTS_REQUEST = "preq"
    /**
     * Манифест поста (рой, этап 1): текст и куски фото с хэшами, подпись
     * автора. Позволяет принимать пост от любого участника, а не только от
     * владельца. Старые телефоны вид не знают и молча отбрасывают.
     */
    const val KIND_POST_MANIFEST = "pman"
    /**
     * Ключи подписи постов: `pkeys|groupId|nodeId:b64(pubkey),…`. Узел шлёт
     * свой ключ сам, владелец канала - свой и ключи администраторов.
     */
    const val KIND_POST_KEYS = "pkeys"
    /** Просьба прислать ключи подписи: `pkreq|groupId`; отвечают владелец и администраторы. */
    const val KIND_POST_KEYS_REQUEST = "pkreq"

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
            /**
             * Настоящий автор, если сообщение пересылает не он.
             *
             * Владелец канала досылает опоздавшему подписчику старые посты от
             * своего имени, но написать их мог администратор. Пустое поле -
             * автор и есть отправитель (все конверты старого образца).
             */
            val authorId: String = "",
            /**
             * Исходное время сообщения при досылке старых постов; 0 у живых
             * сообщений. Без него пост недельной давности показывался бы
             * опоздавшему как написанный только что.
             */
            val sentAtMs: Long = 0L,
        ) : Packet()

        /** Правка текста сообщения: новый текст ложится под тот же id. */
        data class Edit(
            val groupId: String,
            val topicId: String,
            val messageId: String,
            val text: String,
        ) : Packet()

        /**
         * Просьба прислать последние [limit] постов канала целиком.
         *
         * [have] - темы, чьи посты у просящего уже есть: владелец их не шлёт,
         * иначе каждый запуск приложения гонял бы по сети одни и те же фото.
         */
        data class PostsRequest(
            val groupId: String,
            val limit: Int,
            val have: List<String> = emptyList(),
        ) : Packet()

        /**
         * Манифест поста: см. [com.vladimir.messenger.data.swarm.PostManifest].
         * Подпись здесь НЕ проверена - это делает приёмник.
         */
        data class PostManifest(
            val manifest: com.vladimir.messenger.data.swarm.PostManifest,
        ) : Packet()

        /** Ключи подписи постов: пары «узел → открытый ключ Ed25519 (32 байта)». */
        data class PostKeys(
            val groupId: String,
            val keys: List<Pair<String, ByteArray>>,
        ) : Packet()

        data class PostKeysRequest(val groupId: String) : Packet()

        data class TopicCreated(
            val groupId: String,
            val topicId: String,
            val name: String,
            /** Значок темы (эмодзи). Старые конверты приходили без него. */
            val iconEmoji: String = "",
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

        /** «Представься»: [requesterId] просит прислать ему имя и аватар. */
        data class WhoIs(val requesterId: String) : Packet()

        /**
         * Участника исключили или ограничили. Без этого пакета он так и видит
         * группу у себя: состав обновляется рассылкой, а самого исключённого
         * она не касается.
         */
        data class Kick(val groupId: String, val nodeId: String) : Packet()

        /**
         * Запись сетевого каталога: владелец публично делится группой/каналом,
         * контакты сохраняют и пересылают дальше (hops ограничивает дальность).
         * Старые сборки видят неизвестный вид «dir» и молча пропускают.
         */
        data class Directory(
            val groupId: String,
            val title: String,
            val about: String,
            val ownerId: String,
            val slug: String,
            val isChannel: Boolean,
            val needsApproval: Boolean,
            val hops: Int,
        ) : Packet()

        /**
         * Регистрация @имени: владелец объявляет имя и время регистрации,
         * контакты сохраняют и пересылают дальше. При споре прав тот, у кого
         * время раньше. Имя хранится без собаки.
         */
        data class Nick(
            val ownerId: String,
            val name: String,
            val registeredAtMs: Long,
            val hops: Int,
        ) : Packet()

        /**
         * Аватар: JPEG 96x96 в base64, присланный владельцем. Свежесть по
         * updatedAtMs: старые пакеты не затирают новые.
         */
        data class Avatar(
            val ownerId: String,
            val dataB64: String,
            val updatedAtMs: Long,
            val hops: Int,
        ) : Packet()

        data class GroupInfo(
            val groupId: String,
            val title: String,
            val about: String,
            val ownerId: String,
            val inviteSlug: String,
            val isPublic: Boolean,
            val topicsEnabled: Boolean,
            /** Канал, а не группа. У старых конвертов поля нет - это группа. */
            val isChannel: Boolean = false,
        ) : Packet()
    }

    data class RosterEntry(val nodeId: String, val displayName: String, val role: String)

    data class TopicEntry(val topicId: String, val name: String, val iconEmoji: String = "")

    fun isGroupPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_BYTES && text.startsWith("$PREFIX|")

    // ── Сборка ────────────────────────────────────────────────────────────────

    fun buildMessage(
        groupId: String,
        topicId: String,
        text: String,
        messageId: String = "",
        senderName: String = "",
        authorId: String = "",
        sentAtMs: Long = 0L,
    ): String {
        val base = "$PREFIX|$KIND_MESSAGE|$groupId|$topicId|${encode(text)}"
        // Поля добавляются по порядку и только если есть что добавить:
        // конверт из 5 частей понимают даже телефоны с прошлой версией.
        val relayed = authorId.isNotBlank() || sentAtMs > 0L
        if (messageId.isBlank() && senderName.isBlank() && !relayed) return base
        val withId = "$base|${encode(messageId)}"
        if (senderName.isBlank() && !relayed) return withId
        val withName = "$withId|${encode(senderName)}"
        // Восьмое и девятое поля (автор и исходное время) - только при
        // досылке старых постов: конверт из 7 частей по-прежнему понимают
        // телефоны с прошлой версией, а 9-полевой они молча отбрасывают.
        return if (!relayed) withName else "$withName|${encode(authorId)}|${sentAtMs.coerceAtLeast(0L)}"
    }

    /** Правка сообщения: тот же id, новый текст. */
    fun buildEdit(groupId: String, topicId: String, messageId: String, text: String): String =
        "$PREFIX|$KIND_EDIT|$groupId|$topicId|${encode(messageId)}|${encode(text)}"

    /** «Пришлите последние limit постов, кроме этих» - владельцу канала. */
    fun buildPostsRequest(groupId: String, limit: Int, have: List<String> = emptyList()): String {
        val base = "$PREFIX|$KIND_POSTS_REQUEST|$groupId|$limit"
        if (have.isEmpty()) return base
        return base + "|" + have.joinToString(",") { encode(it) }
    }

    /**
     * Манифест поста: `pman|groupId|topicId|b64(messageId)|b64(authorId)|sentAtMs|rev|
     * hex(sha256 текста)|части|b64(pubkey)|b64(sig)`; части - `id:sha16b64,…`
     * (id сообщений - UUID без запятых и двоеточий).
     */
    fun buildPostManifest(m: com.vladimir.messenger.data.swarm.PostManifest): String {
        require(m.isSigned) { "manifest is not signed" }
        val pm = com.vladimir.messenger.data.swarm.PostManifest
        return "$PREFIX|$KIND_POST_MANIFEST|${m.groupId}|${m.topicId}|${encode(m.messageId)}|" +
            "${encode(m.authorId)}|${m.sentAtMs}|${m.revision}|${pm.hex(m.textSha)}|${m.partsWire()}|" +
            "${pm.b64(m.signerPublicKey)}|${pm.b64(m.signature)}"
    }

    fun buildPostKeysRequest(groupId: String): String = "$PREFIX|$KIND_POST_KEYS_REQUEST|$groupId"

    /** Ключи подписи постов: не больше [MAX_POST_KEYS] пар, ключи ровно по 32 байта. */
    fun buildPostKeys(groupId: String, keys: List<Pair<String, ByteArray>>): String {
        val cells = keys.asSequence()
            .filter { (nodeId, key) ->
                nodeId.isNotBlank() && ':' !in nodeId && ',' !in nodeId && '|' !in nodeId &&
                    key.size == com.vladimir.messenger.data.swarm.Ed25519.PUBLIC_KEY_BYTES
            }
            .take(MAX_POST_KEYS)
            .joinToString(",") { (nodeId, key) ->
                nodeId + ":" + com.vladimir.messenger.data.swarm.PostManifest.b64(key)
            }
        return "$PREFIX|$KIND_POST_KEYS|$groupId|$cells"
    }

    /** «Представься» - адресный запрос имени и аватара. */
    fun buildWhoIs(requesterId: String): String = "$PREFIX|$KIND_WHOIS|$requesterId"

    /** «Пришлите состав группы» - лечит неизвестные имена у отправителя. */
    fun buildRosterRequest(groupId: String): String = "$PREFIX|$KIND_ROSTER_REQUEST|$groupId"

    /** Роевая публикация публичной группы/канала в сетевой каталог. */
    fun buildDirectory(
        groupId: String,
        title: String,
        about: String,
        ownerId: String,
        slug: String,
        isChannel: Boolean,
        needsApproval: Boolean,
        hops: Int,
    ): String =
        "$PREFIX|$KIND_DIRECTORY|$groupId|${encode(title)}|${encode(about)}|$ownerId|" +
            "${encode(slug)}|${if (isChannel) 1 else 0}|${if (needsApproval) 1 else 0}|$hops"

    /** Роевая публикация @имени. */
    fun buildNick(ownerId: String, name: String, registeredAtMs: Long, hops: Int): String =
        "$PREFIX|$KIND_NICK|$ownerId|${encode(name)}|$registeredAtMs|$hops"

    fun buildAvatar(ownerId: String, dataB64: String, updatedAtMs: Long, hops: Int): String =
        "$PREFIX|$KIND_AVAT|$ownerId|$dataB64|$updatedAtMs|$hops"

    fun buildTopicCreated(
        groupId: String,
        topicId: String,
        name: String,
        iconEmoji: String = "",
    ): String =
        "$PREFIX|$KIND_TOPIC|$groupId|$topicId|${encode(name)}" +
            if (iconEmoji.isNotEmpty()) "|${encode(iconEmoji)}" else ""

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

    /** Список тем: строки "topicId,name[,iconEmoji]" через ';'. */
    fun buildTopics(groupId: String, entries: List<TopicEntry>): String {
        val csv = entries.joinToString(";") {
            "${encode(it.topicId)},${encode(it.name)}" +
                if (it.iconEmoji.isNotEmpty()) ",${encode(it.iconEmoji)}" else ""
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
        isChannel: Boolean = false,
    ): String {
        val base = "$PREFIX|$KIND_GROUP_INFO|$groupId|" +
            encode(title) + "|" + encode(about) + "|" + encode(ownerId) + "|" +
            encode(inviteSlug) + "|" + (if (isPublic) 1 else 0) + "|" +
            (if (topicsEnabled) 1 else 0)
        // Десятое поле добавляем только для канала: обычный конверт группы
        // остаётся из 9 частей, и его понимают телефоны с прошлой версией.
        return if (isChannel) "$base|1" else base
    }

    // ── Разбор ────────────────────────────────────────────────────────────────

    fun parse(text: String?): Packet? {
        if (!isGroupPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val groupId = parts[2]
        if (groupId.isBlank()) return null

        return when (parts[1]) {
            KIND_MESSAGE -> if (parts.size in 5..9) {
                val body = decode(parts[4]) ?: return null
                // Конверты старого образца приходили без id и без имени.
                val senderMessageId = if (parts.size >= 6) decode(parts[5]).orEmpty() else ""
                val senderName = if (parts.size >= 7) decode(parts[6]).orEmpty() else ""
                val authorId = if (parts.size >= 8) decode(parts[7]).orEmpty() else ""
                val sentAtMs = if (parts.size >= 9) parts[8].toLongOrNull() ?: 0L else 0L
                Packet.Message(groupId, parts[3], body, senderMessageId, senderName, authorId, sentAtMs)
            } else {
                null
            }

            KIND_EDIT -> if (parts.size == 6) {
                val editedId = decode(parts[4]) ?: return null
                val body = decode(parts[5]) ?: return null
                if (editedId.isBlank()) null else Packet.Edit(groupId, parts[3], editedId, body)
            } else {
                null
            }

            KIND_POSTS_REQUEST -> if (parts.size == 4 || parts.size == 5) {
                val limit = parts[3].toIntOrNull() ?: return null
                if (limit <= 0) return null
                val have = if (parts.size == 5 && parts[4].isNotBlank()) {
                    parts[4].split(',').mapNotNull { cell -> decode(cell)?.takeIf { it.isNotBlank() } }
                } else {
                    emptyList()
                }
                Packet.PostsRequest(groupId, limit, have)
            } else {
                null
            }

            KIND_POST_MANIFEST -> if (parts.size == 12) {
                val pm = com.vladimir.messenger.data.swarm.PostManifest
                val topicId = parts[3]
                val messageId = decode(parts[4]) ?: return null
                val authorId = decode(parts[5]) ?: return null
                val sentAtMs = parts[6].toLongOrNull() ?: return null
                val revision = parts[7].toIntOrNull() ?: return null
                val textSha = pm.unhex(parts[8]) ?: return null
                val manifestParts = pm.parseParts(parts[9]) ?: return null
                val pubKey = pm.unb64(parts[10]) ?: return null
                val sig = pm.unb64(parts[11]) ?: return null
                if (topicId.isBlank() || messageId.isBlank() || authorId.isBlank() ||
                    sentAtMs <= 0L || revision < 0 || textSha.size != 32 ||
                    pubKey.size != com.vladimir.messenger.data.swarm.Ed25519.PUBLIC_KEY_BYTES ||
                    sig.size != com.vladimir.messenger.data.swarm.Ed25519.SIGNATURE_BYTES
                ) {
                    null
                } else {
                    Packet.PostManifest(
                        com.vladimir.messenger.data.swarm.PostManifest(
                            groupId = groupId,
                            topicId = topicId,
                            messageId = messageId,
                            authorId = authorId,
                            sentAtMs = sentAtMs,
                            textSha = textSha,
                            parts = manifestParts,
                            revision = revision,
                            signerPublicKey = pubKey,
                            signature = sig,
                        ),
                    )
                }
            } else {
                null
            }

            KIND_POST_KEYS_REQUEST -> if (parts.size == 3) {
                Packet.PostKeysRequest(groupId)
            } else {
                null
            }

            KIND_POST_KEYS -> if (parts.size == 4) {
                val cells = parts[3].split(',').filter { it.isNotBlank() }
                if (cells.size > MAX_POST_KEYS) return null
                val keys = ArrayList<Pair<String, ByteArray>>(cells.size)
                for (cell in cells) {
                    val colon = cell.indexOf(':')
                    if (colon <= 0) return null
                    val key = com.vladimir.messenger.data.swarm.PostManifest.unb64(cell.substring(colon + 1))
                        ?: return null
                    if (key.size != com.vladimir.messenger.data.swarm.Ed25519.PUBLIC_KEY_BYTES) return null
                    keys.add(cell.substring(0, colon) to key)
                }
                Packet.PostKeys(groupId, keys)
            } else {
                null
            }

            KIND_WHOIS -> if (parts.size == 3) {
                Packet.WhoIs(groupId)
            } else {
                null
            }

            KIND_ROSTER_REQUEST -> if (parts.size == 3) {
                Packet.RosterRequest(groupId)
            } else {
                null
            }

            KIND_TOPIC -> if (parts.size == 5 || parts.size == 6) {
                val name = decode(parts[4]) ?: return null
                // Конверты старого образца приходили без значка темы.
                val icon = if (parts.size == 6) decode(parts[5]).orEmpty() else ""
                if (name.isBlank()) {
                    null
                } else {
                    Packet.TopicCreated(groupId, parts[3], name, icon)
                }
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

            KIND_GROUP_INFO -> if (parts.size == 9 || parts.size == 10) {
                val title = decode(parts[3]) ?: return null
                val about = decode(parts[4]) ?: return null
                val ownerId = decode(parts[5]) ?: return null
                val slug = decode(parts[6]).orEmpty()
                val isPublic = parts[7]
                val topics = parts[8]
                val isChannel = parts.size == 10 && parts[9] == "1"
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
                        isChannel = isChannel,
                    )
                }
            } else {
                null
            }

            KIND_DIRECTORY -> if (parts.size == 10) {
                val title = decode(parts[3]) ?: return null
                val about = decode(parts[4]).orEmpty()
                val ownerId = parts[5]
                val slug = decode(parts[6]).orEmpty()
                if (ownerId.isBlank() || slug.isBlank()) {
                    null
                } else {
                    Packet.Directory(
                        groupId = groupId,
                        title = title,
                        about = about,
                        ownerId = ownerId,
                        slug = slug,
                        isChannel = parts[7] == "1",
                        needsApproval = parts[8] == "1",
                        hops = parts[9].toIntOrNull() ?: 0,
                    )
                }
            } else {
                null
            }

            KIND_NICK -> if (parts.size == 6) {
                val ownerId = parts[2]
                val name = decode(parts[3])?.trim()?.trimStart('@').orEmpty()
                val at = parts[4].toLongOrNull() ?: return null
                if (ownerId.isBlank() || name.isBlank()) {
                    null
                } else {
                    Packet.Nick(
                        ownerId = ownerId,
                        name = name,
                        registeredAtMs = at,
                        hops = parts[5].toIntOrNull() ?: 0,
                    )
                }
            } else {
                null
            }

            KIND_AVAT -> if (parts.size == 6) {
                val ownerId = parts[2]
                val dataB64 = parts[3].trim()
                val at = parts[4].toLongOrNull() ?: return null
                if (ownerId.isBlank() || dataB64.isBlank()) {
                    null
                } else {
                    Packet.Avatar(
                        ownerId = ownerId,
                        dataB64 = dataB64,
                        updatedAtMs = at,
                        hops = parts[5].toIntOrNull() ?: 0,
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
            // Строки старого образца - две ячейки, без значка темы.
            if (cells.size != 2 && cells.size != 3) return null
            val topicId = decode(cells[0]) ?: return null
            val name = decode(cells[1]) ?: return null
            if (topicId.isBlank()) return null
            val icon = if (cells.size == 3) decode(cells[2]).orEmpty() else ""
            out.add(TopicEntry(topicId, name, icon))
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
