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

    /** Больше стольких сидов один пост полосами не тянут. */
    const val MAX_STRIPES = 4

    /** Адресов соседей в одном `peers`: 30 × ~64 байта укладываются в ~4 КБ открытого текста. */
    const val MAX_PEERS = 30

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
    /**
     * Просьба прислать ключи подписи: `pkreq|groupId[|b64(nodeId)]`. В канале
     * отвечают владелец и администраторы. В группе (рой, этап 5) четвёртое
     * поле называет, чей ключ нужен: сам этот узел отвечает своим ключом,
     * владелец и администраторы - своими и его, если он им предъявлялся.
     * Прошлые версии четырёхполевую просьбу отбрасывают (они и не отвечают
     * за группы).
     */
    const val KIND_POST_KEYS_REQUEST = "pkreq"
    /**
     * «Хочу куски» (рой, этап 2): `pwant|groupId|b64(topicId)|k|m|b64(id),…`.
     * «Из m сидов тебе досталась полоса k: пришли куски поста с номером
     * ≡ k (mod m), кроме перечисленных». Номер 0 - текст поста, дальше -
     * куски фото в порядке манифеста.
     */
    const val KIND_PIECE_WANT = "pwant"
    /**
     * «Хочу куски» сообщения (рой, этап 5):
     * `mwant|groupId|b64(topicId)|b64(messageId)|k|m|b64(id),…` - то же, что
     * `pwant`, но пост назван своим id, а не темой: в теме обычной группы
     * сообщений много, и у каждого свой манифест. Прошлые версии вид не
     * знают и молча отбрасывают - они и манифестов групп не хранят.
     */
    const val KIND_MESSAGE_WANT = "mwant"
    /**
     * Просьба дослать сообщения темы группы (рой, этап 5):
     * `mreq|groupId|b64(topicId)|afterMs|limit|k|m` - «из m соседей тебе
     * досталась полоса k: пришли не больше `limit` сообщений темы новее
     * `afterMs`, чьё время ≡ k (mod m)». Полоса считается от времени
     * сообщения, поэтому соседи делят работу, не сговариваясь. Отвечает
     * любой участник, у которого сообщение собрано: манифест `pman`, затем
     * текст и куски конвертами `msg` с автором и временем (как досылка постов
     * канала). Так вернувшийся из офлайна и новичок добирают пропущенное.
     */
    const val KIND_MANIFESTS_REQUEST = "mreq"

    /** Сообщений в одном ответе на `mreq` - столько же, сколько постов досылается новичку канала. */
    const val MAX_MANIFESTS_REQUEST = 20
    /**
     * Выборка соседей (рой, этап 2): `peers|groupId|count|nodeId,…`.
     * На большом канале полный список участников в пакет не помещается;
     * владелец даёт новичку `count` (число подписчиков для карточки) и до
     * [MAX_PEERS] адресов, у кого спрашивать посты. Имена узлы узнают по
     * `who` и из конвертов сообщений.
     */
    const val KIND_PEERS = "peers"
    /**
     * Просьба прислать счётчики постов (рой, этап 3): `pcreq|groupId|b64(topicId),…`.
     * На большом канале просмотры и реакции стекаются к владельцу и
     * администраторам, а не ко всем подписчикам; открывая канал, читатель
     * спрашивает сводные числа у одного из них.
     */
    const val KIND_COUNTERS_REQUEST = "pcreq"
    /**
     * Сводные счётчики постов от владельца или администратора:
     * `pcnt|groupId|cells`, где cell = `b64(topicId),b64(messageId),views,
     * b64(emoji)=n+b64(emoji)=n`, а cells разделены `;`.
     */
    const val KIND_COUNTERS = "pcnt"
    /**
     * Просьба прислать комментарии поста (рой, этап 4):
     * `creq|groupId|b64(topicId)|limit|beforeMs|afterMs|have|want`. На большом
     * канале комментарий уходит не всем подписчикам, а владельцу и
     * администраторам («сборщикам») и небольшой выборке соседей; читатель,
     * открыв комментарии, просит у сборщика: ровно `want` (после сверки
     * описи), иначе последние `limit` старше `beforeMs` («показать ещё»),
     * иначе новее `afterMs` (всё, что появилось с прошлого раза), - кроме
     * `have`. `have` и `want` - начала идентификаторов сообщений
     * ([COMMENT_ID_CHARS] знаков) через запятую. Ответ - опись `cids` и
     * обычные конверты `msg` с автором и временем, как при досылке постов.
     */
    const val KIND_COMMENTS_REQUEST = "creq"
    /**
     * Опись комментариев темы у сборщика: `cids|groupId|b64(topicId)|count|p,p,…`
     * - сколько всего и начала идентификаторов самых свежих (до
     * [MAX_COMMENT_IDS], от новых к старым). По ней читатель точно знает,
     * чего у него нет, и просит недостающее по `want`.
     */
    const val KIND_COMMENT_IDS = "cids"
    /**
     * Число комментариев по темам: `cinf|groupId|b64(topicId)=n,…`. Идёт
     * вдогонку сводке счётчиков (`pcnt`): по нему лента показывает настоящее
     * «Комментарии (N)», а не число дошедших до этого телефона.
     */
    const val KIND_COMMENT_COUNTS = "cinf"

    /** Комментариев в одном ответе сборщика (и в первом окне «последние»). */
    const val MAX_COMMENT_BACKFILL = 20

    /** Идентификаторов в `have` и `want` просьбы о комментариях: 20 × 9 байт. */
    const val MAX_COMMENT_HAVE = 20

    /** Идентификаторов в описи `cids`: 100 × 9 байт - меньше килобайта; глубже - по «показать ещё». */
    const val MAX_COMMENT_IDS = 100

    /** Столько первых знаков идентификатора (UUID) хватает, чтобы различать комментарии одной темы. */
    const val COMMENT_ID_CHARS = 8

    /** Тем в одной просьбе о счётчиках - столько же, сколько постов досылается новичку. */
    const val MAX_COUNTER_TOPICS = 20

    /** Постов в одном пакете счётчиков: с реакциями это около 3 КБ - проходит через брокер. */
    const val MAX_COUNTER_CELLS = 10

    /** Значков реакций на пост в сводке: самые частые. */
    const val MAX_COUNTER_EMOJI = 8

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

        /** Просьба о ключах подписи; [nodeId] - чей ключ нужен (пусто - владельца и администраторов). */
        data class PostKeysRequest(val groupId: String, val nodeId: String = "") : Packet()

        /**
         * Просьба дослать сообщения темы группы новее [afterMs], не больше
         * [limit], полоса [stripe] из [stripes] по времени сообщения.
         */
        data class ManifestsRequest(
            val groupId: String,
            val topicId: String,
            val afterMs: Long,
            val limit: Int,
            val stripe: Int = 0,
            val stripes: Int = 1,
        ) : Packet()

        /** Выборка соседей от владельца: [memberCount] подписчиков всего, [nodeIds] - у кого спрашивать. */
        data class Peers(
            val groupId: String,
            val memberCount: Int,
            val nodeIds: List<String>,
        ) : Packet()

        /** Просьба прислать сводные счётчики перечисленных постов (тем). */
        data class CountersRequest(
            val groupId: String,
            val topicIds: List<String>,
        ) : Packet()

        /** Сводные счётчики постов от владельца или администратора. */
        data class Counters(
            val groupId: String,
            val cells: List<PostCounters>,
        ) : Packet()

        /**
         * Просьба прислать комментарии поста (темы [topicId]): ровно [want]
         * (начала идентификаторов); иначе последние [limit] штук старше
         * [beforeMs] (если он задан); иначе новее [afterMs] - кроме [have].
         */
        data class CommentsRequest(
            val groupId: String,
            val topicId: String,
            val limit: Int,
            val beforeMs: Long = 0L,
            val afterMs: Long = 0L,
            val have: List<String> = emptyList(),
            val want: List<String> = emptyList(),
        ) : Packet()

        /** Опись комментариев темы у сборщика: всего [count], начала идентификаторов свежих - [ids]. */
        data class CommentIds(
            val groupId: String,
            val topicId: String,
            val count: Int,
            val ids: List<String>,
        ) : Packet()

        /** Сколько комментариев у сборщика в каждой из тем: «тема → число». */
        data class CommentCounts(
            val groupId: String,
            val counts: List<Pair<String, Int>>,
        ) : Packet()

        /**
         * Просьба прислать полосу кусков поста темы [topicId]; [have] - что
         * уже есть. [messageId] задан у сообщения группы (`mwant`): там пост
         * ищут по нему, а не по теме; у поста канала (`pwant`) он пустой.
         */
        data class PieceWant(
            val groupId: String,
            val topicId: String,
            val stripe: Int,
            val stripes: Int,
            val have: List<String> = emptyList(),
            val messageId: String = "",
        ) : Packet()

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

    /**
     * Сводка одного поста: сколько разных читателей его открыли и сколько
     * реакций каждого значка стоит на сообщении-посте [messageId].
     */
    data class PostCounters(
        val topicId: String,
        val messageId: String,
        val views: Int,
        val reactions: List<Pair<String, Int>>,
    )

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

    /** «Пришлите ключи подписи»; с [nodeId] - ключ именно этого узла (группа, этап 5). */
    fun buildPostKeysRequest(groupId: String, nodeId: String = ""): String =
        if (nodeId.isBlank()) {
            "$PREFIX|$KIND_POST_KEYS_REQUEST|$groupId"
        } else {
            "$PREFIX|$KIND_POST_KEYS_REQUEST|$groupId|${encode(nodeId)}"
        }

    /** «Дошлите сообщения темы новее [afterMs]», полоса [stripe] из [stripes], - соседям по группе (этап 5). */
    fun buildManifestsRequest(
        groupId: String,
        topicId: String,
        afterMs: Long,
        limit: Int,
        stripe: Int = 0,
        stripes: Int = 1,
    ): String {
        require(stripes in 1..MAX_STRIPES && stripe in 0 until stripes) { "bad stripe $stripe/$stripes" }
        return "$PREFIX|$KIND_MANIFESTS_REQUEST|$groupId|${encode(topicId)}|${afterMs.coerceAtLeast(0L)}|" +
            "${limit.coerceIn(1, MAX_MANIFESTS_REQUEST)}|$stripe|$stripes"
    }

    /** Выборка соседей: не больше [MAX_PEERS] адресов, без разделителей внутри. */
    fun buildPeers(groupId: String, memberCount: Int, nodeIds: List<String>): String {
        val cells = nodeIds.asSequence()
            .filter { it.isNotBlank() && ',' !in it && '|' !in it }
            .distinct()
            .take(MAX_PEERS)
            .joinToString(",")
        return "$PREFIX|$KIND_PEERS|$groupId|${memberCount.coerceAtLeast(0)}|$cells"
    }

    /**
     * Полоса [stripe] из [stripes] над упорядоченным списком кусков поста
     * ([ordered]: номер 0 - текст, дальше куски фото в порядке манифеста):
     * куски с номером ≡ stripe (mod stripes), которых нет в [have]. Одна
     * формула у просящего и у сида - полосы не пересекаются и вместе дают
     * весь пост.
     */
    fun stripe(ordered: List<String>, stripe: Int, stripes: Int, have: Set<String>): List<String> =
        ordered.filterIndexed { index, id -> index % stripes == stripe && id !in have }

    /**
     * «Хочу куски»: полоса [stripe] из [stripes], без перечисленных в [have].
     * С [messageId] - просьба о сообщении группы (`mwant`), без него - о
     * посте темы канала (`pwant`, его понимают и прошлые версии).
     */
    fun buildPieceWant(
        groupId: String,
        topicId: String,
        stripe: Int,
        stripes: Int,
        have: List<String>,
        messageId: String = "",
    ): String {
        require(stripes in 1..MAX_STRIPES && stripe in 0 until stripes) { "bad stripe $stripe/$stripes" }
        val haveCells = have.joinToString(",") { encode(it) }
        if (messageId.isBlank()) {
            return "$PREFIX|$KIND_PIECE_WANT|$groupId|${encode(topicId)}|$stripe|$stripes|$haveCells"
        }
        return "$PREFIX|$KIND_MESSAGE_WANT|$groupId|${encode(topicId)}|${encode(messageId)}|$stripe|$stripes|$haveCells"
    }

    /** «Пришлите счётчики этих постов» - владельцу или администратору канала. */
    fun buildCountersRequest(groupId: String, topicIds: List<String>): String =
        "$PREFIX|$KIND_COUNTERS_REQUEST|$groupId|" +
            topicIds.filter { it.isNotBlank() }.distinct().take(MAX_COUNTER_TOPICS).joinToString(",") { encode(it) }

    /** Сводные счётчики: не больше [MAX_COUNTER_CELLS] постов и [MAX_COUNTER_EMOJI] значков на пост. */
    fun buildCounters(groupId: String, cells: List<PostCounters>): String {
        val body = cells.take(MAX_COUNTER_CELLS).joinToString(";") { cell ->
            val reactions = cell.reactions.asSequence()
                .filter { (emoji, count) -> emoji.isNotBlank() && count > 0 }
                .sortedByDescending { it.second }
                .take(MAX_COUNTER_EMOJI)
                .joinToString("+") { (emoji, count) -> encode(emoji) + "=" + count }
            encode(cell.topicId) + "," + encode(cell.messageId) + "," + cell.views.coerceAtLeast(0) + "," + reactions
        }
        return "$PREFIX|$KIND_COUNTERS|$groupId|$body"
    }

    /** Начало идентификатора для `have`/`want`/`cids`: без разделителей конверта. */
    fun commentIdKey(messageId: String): String = messageId.take(COMMENT_ID_CHARS)

    private fun idKeys(ids: List<String>, limit: Int): String =
        ids.asSequence().map { commentIdKey(it) }
            .filter { it.isNotBlank() && ',' !in it && '|' !in it }
            .distinct().take(limit).joinToString(",")

    private fun parseIdKeys(cell: String, limit: Int): List<String>? {
        if (cell.isBlank()) return emptyList()
        val keys = cell.split(',').filter { it.isNotBlank() }
        if (keys.size > limit || keys.any { it.length > COMMENT_ID_CHARS || '|' in it }) return null
        return keys
    }

    /**
     * «Пришлите комментарии поста» - владельцу или администратору канала:
     * ровно [want] (если задан), иначе последние [limit] старше [beforeMs]
     * (если задан), иначе новее [afterMs] - кроме [have]. Списки режутся до
     * [MAX_COMMENT_HAVE] начал идентификаторов.
     */
    fun buildCommentsRequest(
        groupId: String,
        topicId: String,
        limit: Int,
        beforeMs: Long = 0L,
        afterMs: Long = 0L,
        have: List<String> = emptyList(),
        want: List<String> = emptyList(),
    ): String =
        "$PREFIX|$KIND_COMMENTS_REQUEST|$groupId|${encode(topicId)}|" +
            "${limit.coerceIn(1, MAX_COMMENT_BACKFILL)}|${beforeMs.coerceAtLeast(0L)}|${afterMs.coerceAtLeast(0L)}|" +
            idKeys(have, MAX_COMMENT_HAVE) + "|" + idKeys(want, MAX_COMMENT_HAVE)

    /** Опись комментариев темы: всего [count], начала идентификаторов [ids] (от новых к старым, до [MAX_COMMENT_IDS]). */
    fun buildCommentIds(groupId: String, topicId: String, count: Int, ids: List<String>): String =
        "$PREFIX|$KIND_COMMENT_IDS|$groupId|${encode(topicId)}|${count.coerceAtLeast(0)}|" + idKeys(ids, MAX_COMMENT_IDS)

    /** Число комментариев по темам: не больше [MAX_COUNTER_TOPICS] тем, отрицательные не шлём. */
    fun buildCommentCounts(groupId: String, counts: List<Pair<String, Int>>): String {
        val cells = counts.asSequence()
            .filter { (topicId, n) -> topicId.isNotBlank() && n >= 0 }
            .distinctBy { it.first }
            .take(MAX_COUNTER_TOPICS)
            .joinToString(",") { (topicId, n) -> encode(topicId) + "=" + n }
        return "$PREFIX|$KIND_COMMENT_COUNTS|$groupId|$cells"
    }

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

            KIND_POST_KEYS_REQUEST -> when (parts.size) {
                3 -> Packet.PostKeysRequest(groupId)
                4 -> {
                    val nodeId = decode(parts[3])?.takeIf { it.isNotBlank() } ?: return null
                    Packet.PostKeysRequest(groupId, nodeId)
                }
                else -> null
            }

            KIND_MANIFESTS_REQUEST -> if (parts.size == 8) {
                val topicId = decode(parts[3])?.takeIf { it.isNotBlank() } ?: return null
                val afterMs = parts[4].toLongOrNull() ?: return null
                val limit = parts[5].toIntOrNull() ?: return null
                val stripe = parts[6].toIntOrNull() ?: return null
                val stripes = parts[7].toIntOrNull() ?: return null
                if (afterMs < 0L || limit !in 1..MAX_MANIFESTS_REQUEST ||
                    stripes !in 1..MAX_STRIPES || stripe !in 0 until stripes
                ) {
                    return null
                }
                Packet.ManifestsRequest(groupId, topicId, afterMs, limit, stripe, stripes)
            } else {
                null
            }

            KIND_PEERS -> if (parts.size == 5) {
                val count = parts[3].toIntOrNull() ?: return null
                if (count < 0) return null
                val ids = if (parts[4].isBlank()) {
                    emptyList()
                } else {
                    parts[4].split(',').filter { it.isNotBlank() }
                }
                if (ids.size > MAX_PEERS) return null
                Packet.Peers(groupId, count, ids)
            } else {
                null
            }

            KIND_COUNTERS_REQUEST -> if (parts.size == 4) {
                val ids = if (parts[3].isBlank()) {
                    emptyList()
                } else {
                    parts[3].split(',').mapNotNull { cell -> decode(cell)?.takeIf { it.isNotBlank() } }
                }
                if (ids.isEmpty() || ids.size > MAX_COUNTER_TOPICS) null else Packet.CountersRequest(groupId, ids)
            } else {
                null
            }

            KIND_COUNTERS -> if (parts.size == 4) {
                val cells = parseCounters(parts[3]) ?: return null
                Packet.Counters(groupId, cells)
            } else {
                null
            }

            KIND_COMMENTS_REQUEST -> if (parts.size == 9) {
                val topicId = decode(parts[3])?.takeIf { it.isNotBlank() } ?: return null
                val limit = parts[4].toIntOrNull() ?: return null
                val beforeMs = parts[5].toLongOrNull() ?: return null
                val afterMs = parts[6].toLongOrNull() ?: return null
                if (limit !in 1..MAX_COMMENT_BACKFILL || beforeMs < 0L || afterMs < 0L) return null
                val have = parseIdKeys(parts[7], MAX_COMMENT_HAVE) ?: return null
                val want = parseIdKeys(parts[8], MAX_COMMENT_HAVE) ?: return null
                Packet.CommentsRequest(groupId, topicId, limit, beforeMs, afterMs, have, want)
            } else {
                null
            }

            KIND_COMMENT_IDS -> if (parts.size == 6) {
                val topicId = decode(parts[3])?.takeIf { it.isNotBlank() } ?: return null
                val count = parts[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val ids = parseIdKeys(parts[5], MAX_COMMENT_IDS) ?: return null
                Packet.CommentIds(groupId, topicId, count, ids)
            } else {
                null
            }

            KIND_COMMENT_COUNTS -> if (parts.size == 4) {
                val counts = parseCommentCounts(parts[3]) ?: return null
                Packet.CommentCounts(groupId, counts)
            } else {
                null
            }

            KIND_PIECE_WANT -> if (parts.size == 7) {
                val topicId = decode(parts[3]) ?: return null
                val stripe = parts[4].toIntOrNull() ?: return null
                val stripes = parts[5].toIntOrNull() ?: return null
                val have = parseHave(parts[6])
                if (topicId.isBlank() || stripes !in 1..MAX_STRIPES || stripe !in 0 until stripes) {
                    null
                } else {
                    Packet.PieceWant(groupId, topicId, stripe, stripes, have)
                }
            } else {
                null
            }

            KIND_MESSAGE_WANT -> if (parts.size == 8) {
                val topicId = decode(parts[3]) ?: return null
                val messageId = decode(parts[4]) ?: return null
                val stripe = parts[5].toIntOrNull() ?: return null
                val stripes = parts[6].toIntOrNull() ?: return null
                val have = parseHave(parts[7])
                if (topicId.isBlank() || messageId.isBlank() || stripes !in 1..MAX_STRIPES || stripe !in 0 until stripes) {
                    null
                } else {
                    Packet.PieceWant(groupId, topicId, stripe, stripes, have, messageId)
                }
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

    private fun parseCounters(body: String): List<PostCounters>? {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<PostCounters>()
        for (cell in body.split(';')) {
            if (cell.isBlank()) continue
            val fields = cell.split(',')
            if (fields.size != 4) return null
            val topicId = decode(fields[0])?.takeIf { it.isNotBlank() } ?: return null
            val messageId = decode(fields[1]) ?: return null
            val views = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val reactions = ArrayList<Pair<String, Int>>()
            if (fields[3].isNotBlank()) {
                for (entry in fields[3].split('+')) {
                    val eq = entry.indexOf('=')
                    if (eq <= 0) return null
                    val emoji = decode(entry.substring(0, eq))?.takeIf { it.isNotBlank() } ?: return null
                    val count = entry.substring(eq + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    reactions.add(emoji to count)
                }
            }
            if (reactions.size > MAX_COUNTER_EMOJI) return null
            out.add(PostCounters(topicId, messageId, views, reactions))
        }
        if (out.size > MAX_COUNTER_CELLS) return null
        return out
    }

    /** `b64(topicId)=n,…` → пары; мусор - null. Пустое тело - пустой список. */
    private fun parseCommentCounts(body: String): List<Pair<String, Int>>? {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        for (cell in body.split(',')) {
            if (cell.isBlank()) continue
            val eq = cell.indexOf('=')
            if (eq <= 0) return null
            val topicId = decode(cell.substring(0, eq))?.takeIf { it.isNotBlank() } ?: return null
            val count = cell.substring(eq + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
            out.add(topicId to count)
        }
        if (out.size > MAX_COUNTER_TOPICS) return null
        return out
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

    /** Список «уже есть» из просьбы о кусках: b64(id) через запятую, пустые и битые ячейки пропускаются. */
    private fun parseHave(cell: String): List<String> =
        if (cell.isBlank()) {
            emptyList()
        } else {
            cell.split(',').mapNotNull { c -> decode(c)?.takeIf { it.isNotBlank() } }
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
