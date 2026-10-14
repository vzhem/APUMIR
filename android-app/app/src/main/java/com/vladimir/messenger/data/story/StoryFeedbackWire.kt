package com.vladimir.messenger.data.story

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * Конверт отклика на историю: реакция, комментарий и сводка.
 *
 * Зачем свой провод внутри префикса истории. Пакет истории (`APUSTORY1:`)
 * уже поглощается до записи в переписку - в `ChatRepository.saveIncomingMessage`
 * и, значит, на всех путях приёма (LAN, брокер, релей, зеркало не в счёт:
 * истории между устройствами одной личности не ходят). Отклик идёт тем же
 * префиксом, поэтому ему не нужны новые точки маршрутизации: служебная строка
 * не может лечь в чат сообщением даже на старом телефоне.
 *
 * Обратная совместимость. Старая версия APU разбирает пакет истории как JSON и
 * читает обязательное поле `text`. Конверт отклика - НЕ JSON и поля `text` не
 * содержит, поэтому старый телефон молча отбросит его (счётчик
 * `story_photo_pending`), а не покажет чужой комментарий как историю автора.
 *
 * Формат (разделитель `|`, все строковые поля в base64url без padding):
 *
 *   APUSTORY1:FB1|<вид>|<ид истории>|<автор истории>|<кто>|<имя кто>|<время>|<a>|<b>|<c>
 *
 *   react : a = значок, b = "1" поставить / "0" снять, c пусто
 *   cmt   : a = ид комментария, b = текст, c пусто
 *   ask   : a, b, c пусты (зритель просит сводку у автора)
 *   state : a = строки сводки (см. [encodeEntries]), b, c пусты
 *
 * Сводка - это `;`-строки `r:<кто>:<имя>:<значок>:<время>` и
 * `c:<ид>:<кто>:<имя>:<текст>:<время>`, где каждое поле тоже base64url,
 * поэтому `:` и `;` разделителями не конфликтуют.
 *
 * Приватность имени. Имя в конверте - то, которым человек назвался сам
 * (его профильное имя), а не подпись контакта в азбуке автора. Иначе автор
 * разослал бы всем зрителям свои личные подписи («Мама», «Начальник»).
 */
object StoryFeedbackWire {

    /** Префикс истории плюс метка отклика. Держать равным `StoryStore.PREFIX + "FB1|"`. */
    const val MARKER = "APUSTORY1:FB1|"

    const val KIND_REACTION = "react"
    const val KIND_COMMENT = "cmt"
    const val KIND_STATE_REQUEST = "ask"
    const val KIND_STATE = "state"

    /** Ровно столько полей даёт разбор по `|`; другое число - не наш конверт. */
    private const val FIELD_COUNT = 10

    /** Пределы одного отклика. Текст истории - 200 знаков, комментарий не длиннее. */
    const val MAX_COMMENT_CHARS = 200
    const val MAX_NAME_CHARS = 40
    const val MAX_EMOJI_CHARS = 32
    const val MAX_STORY_ID_CHARS = 100
    const val MAX_NODE_CHARS = 256

    /**
     * Предел конверта в байтах. У истории он 16 КиБ (`StoryStore.receive`),
     * сводка обязана проходить с запасом на base64 - поэтому режем по 15 000.
     */
    const val MAX_PACKET_BYTES = 15_000

    /** Сколько откликов помещается в одну сводку: сначала режем их, не имя. */
    const val MAX_STATE_REACTIONS = 40
    const val MAX_STATE_COMMENTS = 20

    /** Реакция одного человека. [emoji] пустой - реакция снята (надгробие). */
    data class Reaction(
        val actorId: String,
        val actorName: String,
        val emoji: String,
        val atMs: Long,
    ) {
        val removed: Boolean get() = emoji.isEmpty()
    }

    /** Комментарий одного человека. Текст неизменяем: правок у комментария нет. */
    data class Comment(
        val id: String,
        val actorId: String,
        val actorName: String,
        val body: String,
        val atMs: Long,
    )

    /** Разобранный конверт. */
    sealed interface Packet {
        val storyId: String
        val authorId: String
        /** Кто поставил отклик (в сводке - не обязательно отправитель пакета). */
        val actorId: String
        val actorName: String
        val atMs: Long

        data class React(
            override val storyId: String,
            override val authorId: String,
            override val actorId: String,
            override val actorName: String,
            override val atMs: Long,
            val emoji: String,
            /** true - значок поставлен, false - снят. */
            val added: Boolean,
        ) : Packet

        data class CommentPacket(
            override val storyId: String,
            override val authorId: String,
            override val actorId: String,
            override val actorName: String,
            override val atMs: Long,
            val commentId: String,
            val body: String,
        ) : Packet

        data class Ask(
            override val storyId: String,
            override val authorId: String,
            override val actorId: String,
            override val actorName: String,
            override val atMs: Long,
        ) : Packet

        data class State(
            override val storyId: String,
            override val authorId: String,
            override val actorId: String,
            override val actorName: String,
            override val atMs: Long,
            val reactions: List<Reaction>,
            val comments: List<Comment>,
        ) : Packet
    }

    fun isFeedback(raw: String?): Boolean =
        raw != null && raw.length <= MAX_PACKET_BYTES && raw.startsWith(MARKER)

    /** Значок поставить/снять. */
    fun buildReaction(
        storyId: String,
        authorId: String,
        actorId: String,
        actorName: String,
        emoji: String,
        added: Boolean,
        atMs: Long,
    ): String? {
        // Снятие значка (надгробие) значка не несёт, и требовать его здесь
        // нельзя: в книге откликов прежний значок уже заменён надгробием с
        // пустым значком, поэтому `outgoing` строит снятие именно так. Отказ
        // значило бы, что «снял» не доезжает до автора и значок остаётся у
        // соседей навсегда. Ставить значок пустым по-прежнему нельзя.
        val clean = if (added) sanitizeEmoji(emoji) ?: return null else sanitizeEmoji(emoji).orEmpty()
        if (!identifiersOk(storyId, authorId, actorId)) return null
        return envelope(
            KIND_REACTION, storyId, authorId, actorId, sanitizeName(actorName), atMs,
            encode(clean), if (added) "1" else "0", "",
        )
    }

    /** Новый комментарий. [commentId] - UUID, по нему идёт дедупликация. */
    fun buildComment(
        storyId: String,
        authorId: String,
        actorId: String,
        actorName: String,
        commentId: String,
        body: String,
        atMs: Long,
    ): String? {
        val clean = sanitizeComment(body) ?: return null
        if (!identifiersOk(storyId, authorId, actorId)) return null
        if (commentId.length !in 1..MAX_STORY_ID_CHARS) return null
        return envelope(
            KIND_COMMENT, storyId, authorId, actorId, sanitizeName(actorName), atMs,
            encode(commentId), encode(clean), "",
        )
    }

    /** Просьба зрителя отдать сводку (он открыл историю, а откликов у него нет). */
    fun buildStateRequest(
        storyId: String,
        authorId: String,
        actorId: String,
        actorName: String,
        atMs: Long,
    ): String? {
        if (!identifiersOk(storyId, authorId, actorId)) return null
        return envelope(
            KIND_STATE_REQUEST, storyId, authorId, actorId, sanitizeName(actorName), atMs,
            "", "", "",
        )
    }

    /**
     * Сводка автора: реакции и комментарии одной истории.
     *
     * Возвращает null, если даже после обрезки пакет не влезает в
     * [MAX_PACKET_BYTES] - например, когда значков и комментариев слишком
     * много. Тогда вызывающий обязан уменьшить список и попробовать снова
     * (или не слать ничего): резаный на transport пакет не собрать.
     */
    fun buildState(
        storyId: String,
        authorId: String,
        reactions: List<Reaction>,
        comments: List<Comment>,
        atMs: Long,
    ): String? {
        if (!identifiersOk(storyId, authorId, authorId)) return null
        // Списки приходят от книги откликов отсортированными по времени
        // (старые первыми), поэтому берём ХВОСТ: в пакет попадают самые свежие
        // отклики, а при нехватке места режутся самые старые.
        var keptComments = comments.takeLast(MAX_STATE_COMMENTS)
        var keptReactions = reactions.takeLast(MAX_STATE_REACTIONS)
        while (true) {
            val packet = envelope(
                KIND_STATE, storyId, authorId, authorId, "", atMs,
                encode(encodeEntries(keptReactions, keptComments)), "", "",
            )
            if (packet.toByteArray(StandardCharsets.UTF_8).size <= MAX_PACKET_BYTES) return packet
            // Режем сначала старые комментарии (они самые тяжёлые), потом значки.
            when {
                keptComments.size > 1 -> keptComments = keptComments.drop(1)
                keptReactions.size > 1 -> keptReactions = keptReactions.drop(1)
                else -> return null
            }
        }
    }

    fun parse(raw: String?): Packet? {
        if (!isFeedback(raw)) return null
        val parts = raw!!.split('|')
        if (parts.size != FIELD_COUNT) return null
        val kind = parts[1]
        val storyId = decode(parts[2]) ?: return null
        val authorId = decode(parts[3]) ?: return null
        val actorId = decode(parts[4]) ?: return null
        val actorName = decode(parts[5]) ?: return null
        val atMs = parts[6].toLongOrNull() ?: return null
        if (!identifiersOk(storyId, authorId, actorId)) return null
        if (atMs <= 0L) return null
        val name = sanitizeName(actorName)
        return when (kind) {
            KIND_REACTION -> {
                val emoji = decode(parts[7]) ?: return null
                val added = when (parts[8]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                // Снятая реакция приходит пустым значком: это надгробие, а не мусор.
                val clean = if (added) sanitizeEmoji(emoji) ?: return null else ""
                Packet.React(storyId, authorId, actorId, name, atMs, clean, added)
            }
            KIND_COMMENT -> {
                val commentId = decode(parts[7]) ?: return null
                val body = decode(parts[8]) ?: return null
                val clean = sanitizeComment(body) ?: return null
                if (commentId.length !in 1..MAX_STORY_ID_CHARS) return null
                Packet.CommentPacket(storyId, authorId, actorId, name, atMs, commentId, clean)
            }
            KIND_STATE_REQUEST -> Packet.Ask(storyId, authorId, actorId, name, atMs)
            KIND_STATE -> {
                val entries = decode(parts[7]) ?: return null
                val parsed = decodeEntries(entries)
                Packet.State(storyId, authorId, actorId, name, atMs, parsed.first, parsed.second)
            }
            else -> null
        }
    }

    /**
     * Идентификатор транспорта: устойчивый UUID на одно действие.
     *
     * Повторная волна обязана приехать тем же сообщением (как у частей
     * фотоистории в `StoryOutbox.transportId`), иначе ядро и дедупликатор
     * получателя увидят два разных отклика.
     */
    fun transportId(storyId: String, kind: String, actorId: String, atMs: Long): String =
        UUID.nameUUIDFromBytes(
            "APU-STORYFB1:$storyId:$kind:$actorId:$atMs".toByteArray(StandardCharsets.UTF_8)
        ).toString()

    /** Строки сводки: `r:` - значки, `c:` - комментарии. */
    fun encodeEntries(reactions: List<Reaction>, comments: List<Comment>): String = buildString {
        reactions.forEach { item ->
            if (item.actorId.isEmpty()) return@forEach
            if (isNotEmpty()) append(';')
            append("r:").append(encode(item.actorId)).append(':')
                .append(encode(item.actorName)).append(':')
                .append(encode(item.emoji)).append(':')
                .append(item.atMs)
        }
        comments.forEach { item ->
            if (item.id.isEmpty() || item.actorId.isEmpty()) return@forEach
            if (isNotEmpty()) append(';')
            append("c:").append(encode(item.id)).append(':')
                .append(encode(item.actorId)).append(':')
                .append(encode(item.actorName)).append(':')
                .append(encode(item.body)).append(':')
                .append(item.atMs)
        }
    }

    fun decodeEntries(raw: String): Pair<List<Reaction>, List<Comment>> {
        if (raw.isEmpty()) return emptyList<Reaction>() to emptyList()
        val reactions = ArrayList<Reaction>()
        val comments = ArrayList<Comment>()
        raw.split(';').forEach { line ->
            val fields = line.split(':')
            when {
                fields.size == 5 && fields[0] == "r" -> {
                    val actorId = decode(fields[1]) ?: return@forEach
                    val name = decode(fields[2]) ?: return@forEach
                    val emoji = decode(fields[3]) ?: return@forEach
                    val atMs = fields[4].toLongOrNull() ?: return@forEach
                    if (actorId.length !in 1..MAX_NODE_CHARS || atMs <= 0L) return@forEach
                    reactions.add(Reaction(actorId, sanitizeName(name), emoji.take(MAX_EMOJI_CHARS), atMs))
                }
                fields.size == 6 && fields[0] == "c" -> {
                    val id = decode(fields[1]) ?: return@forEach
                    val actorId = decode(fields[2]) ?: return@forEach
                    val name = decode(fields[3]) ?: return@forEach
                    val body = decode(fields[4]) ?: return@forEach
                    val atMs = fields[5].toLongOrNull() ?: return@forEach
                    if (id.isEmpty() || actorId.length !in 1..MAX_NODE_CHARS || atMs <= 0L) return@forEach
                    val clean = sanitizeComment(body) ?: return@forEach
                    comments.add(Comment(id, actorId, sanitizeName(name), clean, atMs))
                }
            }
        }
        return reactions.take(MAX_STATE_REACTIONS * 4) to comments.take(MAX_STATE_COMMENTS * 4)
    }

    /** Имя без переводов строк и управляющих символов, не длиннее [MAX_NAME_CHARS]. */
    fun sanitizeName(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val flat = raw.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim()
        return flat.take(MAX_NAME_CHARS)
    }

    /**
     * Текст комментария: одна строка, без управляющих символов, до
     * [MAX_COMMENT_CHARS] знаков. Пустой или пробельный текст не принимается -
     * пустой комментарий не отклик, а мусорный пакет.
     */
    fun sanitizeComment(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val flat = raw.replace(Regex("[\\p{Cntrl}]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        return flat.take(MAX_COMMENT_CHARS)
    }

    /** Значок реакции: непустой, короткий, без разделителей провода. */
    fun sanitizeEmoji(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val flat = raw.trim()
        if (flat.isEmpty() || flat.length > MAX_EMOJI_CHARS) return null
        if (flat.any { it == '|' || it == ':' || it == ';' || it.code < 32 }) return null
        return flat
    }

    private fun identifiersOk(storyId: String, authorId: String, actorId: String): Boolean =
        storyId.length in 1..MAX_STORY_ID_CHARS &&
            authorId.length in 1..MAX_NODE_CHARS &&
            actorId.length in 1..MAX_NODE_CHARS

    private fun envelope(
        kind: String,
        storyId: String,
        authorId: String,
        actorId: String,
        actorName: String,
        atMs: Long,
        a: String,
        b: String,
        c: String,
    ): String = listOf(
        MARKER.dropLast(1), kind, encode(storyId), encode(authorId), encode(actorId),
        encode(actorName), atMs.toString(), a, b, c,
    ).joinToString("|")

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? =
        if (value.isEmpty()) {
            ""
        } else {
            try {
                String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
