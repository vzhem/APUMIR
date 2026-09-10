package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupWireTest {

    @Test
    fun messageRoundTripKeepsTrickyText() {
        val text = "привет | труба\nвторая строка | 100% — ok"
        val envelope = GroupWire.buildMessage("grp1", "topic1", text)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Message)
        val msg = parsed as GroupWire.Packet.Message
        assertEquals("grp1", msg.groupId)
        assertEquals("topic1", msg.topicId)
        assertEquals(text, msg.text)
    }

    /**
     * Идентификатор сообщения обязан доходить до получателя.
     *
     * Без него закреп работал только у отправителя: получатель клал строку под
     * транспортным id, а конверт Pin ссылался на id отправителя.
     */
    @Test
    fun messageCarriesSenderId() {
        val envelope = GroupWire.buildMessage("grp1", "topic1", "текст", messageId = "msg-42")
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("msg-42", msg.messageId)
    }

    /** Старые конверты без id (5 частей) принимаются: id пустой, сообщение не теряется. */
    @Test
    fun legacyMessageWithoutIdStillParses() {
        val text = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("старое сообщение".toByteArray(Charsets.UTF_8))
        val parsed = GroupWire.parse("APUGRP1|msg|grp1|topic1|$text")
        assertTrue(parsed is GroupWire.Packet.Message)
        val msg = parsed as GroupWire.Packet.Message
        assertEquals("старое сообщение", msg.text)
        assertEquals("", msg.messageId)
    }

    /** Имя отправителя ездит вместе с сообщением: без него получатель показывает обрывок id. */
    @Test
    fun messageCarriesSenderName() {
        val envelope = GroupWire.buildMessage("grp1", "t", "текст", "msg-7", "Владимир")
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("msg-7", msg.messageId)
        assertEquals("Владимир", msg.senderName)
    }

    /** Конверт из 6 частей (id есть, имени ещё нет) принимается: имя пустое. */
    @Test
    fun messageWithIdButWithoutNameStillParses() {
        val envelope = GroupWire.buildMessage("grp1", "t", "текст", messageId = "msg-8")
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("msg-8", msg.messageId)
        assertEquals("", msg.senderName)
    }

    /** Канал отличается от группы одним полем в карточке. */
    @Test
    fun channelInfoRoundTrip() {
        val envelope = GroupWire.buildGroupInfo(
            groupId = "ch-1",
            title = "Новости",
            about = "",
            ownerId = "pk_owner",
            inviteSlug = "AbCdEf2345678901",
            isPublic = true,
            topicsEnabled = true,
            isChannel = true,
        )
        val parsed = GroupWire.parse(envelope) as GroupWire.Packet.GroupInfo
        assertTrue("канал должен остаться каналом", parsed.isChannel)
        assertEquals("ch-1", parsed.groupId)
    }

    /** Обычная группа: конверт из 9 частей, каналом не считается. */
    @Test
    fun groupInfoWithoutChannelFieldIsNotChannel() {
        val envelope = GroupWire.buildGroupInfo(
            groupId = "grp-2",
            title = "Работа",
            about = "",
            ownerId = "pk_owner",
            inviteSlug = "AbCdEf2345678901",
            isPublic = false,
            topicsEnabled = true,
        )
        assertEquals(9, envelope.split('|').size)
        val parsed = GroupWire.parse(envelope) as GroupWire.Packet.GroupInfo
        assertFalse(parsed.isChannel)
    }

    @Test
    fun rosterRequestRoundTrip() {
        val parsed = GroupWire.parse(GroupWire.buildRosterRequest("grp1"))
        assertTrue(parsed is GroupWire.Packet.RosterRequest)
        assertEquals("grp1", (parsed as GroupWire.Packet.RosterRequest).groupId)
    }

    @Test
    fun rosterRequestWithExtraPartsIsRejected() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_ROSTER_REQUEST}|g|x"))
    }

    @Test
    fun topicRoundTrip() {
        val parsed = GroupWire.parse(GroupWire.buildTopicCreated("g", "t", "Флуд | оффтоп"))
        assertTrue(parsed is GroupWire.Packet.TopicCreated)
        assertEquals("Флуд | оффтоп", (parsed as GroupWire.Packet.TopicCreated).name)
    }

    @Test
    fun joinRequestRoundTrip() {
        val parsed = GroupWire.parse(GroupWire.buildJoinRequest("g", "Аня", "пустите | пожалуйста"))
        assertTrue(parsed is GroupWire.Packet.JoinRequest)
        val req = parsed as GroupWire.Packet.JoinRequest
        assertEquals("Аня", req.displayName)
        assertEquals("пустите | пожалуйста", req.note)
    }

    @Test
    fun joinRequestCarriesInviteSlug() {
        val parsed = GroupWire.parse(
            GroupWire.buildJoinRequest("g", "Стас", "пустите", "AbCdEf2345678901")
        )
        assertTrue(parsed is GroupWire.Packet.JoinRequest)
        assertEquals("AbCdEf2345678901", (parsed as GroupWire.Packet.JoinRequest).slug)
    }

    @Test
    fun joinRequestFromOlderBuildStillParses() {
        // Заявка старого образца приходит без slug — её нельзя отбрасывать,
        // иначе заявка просто потеряется.
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val name = enc.encodeToString("Аня".toByteArray(Charsets.UTF_8))
        val note = enc.encodeToString("привет".toByteArray(Charsets.UTF_8))
        val parsed = GroupWire.parse("APUGRP1|req|g|$name|$note")
        assertTrue(parsed is GroupWire.Packet.JoinRequest)
        assertEquals("", (parsed as GroupWire.Packet.JoinRequest).slug)
    }

    @Test
    fun kickAndTopicsRequestRoundTrip() {
        val kick = GroupWire.parse(GroupWire.buildKick("grp-1", "pk_abc"))
        assertTrue(kick is GroupWire.Packet.Kick)
        assertEquals("pk_abc", (kick as GroupWire.Packet.Kick).nodeId)
        assertEquals("grp-1", kick.groupId)

        val request = GroupWire.parse(GroupWire.buildTopicsRequest("grp-1"))
        assertTrue(request is GroupWire.Packet.TopicsRequest)
        assertEquals("grp-1", (request as GroupWire.Packet.TopicsRequest).groupId)
    }

    @Test
    fun topicsRoundTrip() {
        val envelope = GroupWire.buildTopics(
            "grp-1",
            listOf(
                GroupWire.TopicEntry("t-1", "Общий | чат"),
                GroupWire.TopicEntry("t-2", "Флуд"),
            ),
        )
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Topics)
        val topics = parsed as GroupWire.Packet.Topics
        assertEquals("grp-1", topics.groupId)
        assertEquals(2, topics.entries.size)
        assertEquals("t-1", topics.entries[0].topicId)
        assertEquals("Общий | чат", topics.entries[0].name)
        assertEquals("Флуд", topics.entries[1].name)
    }

    @Test
    fun groupInfoRoundTrip() {
        val envelope = GroupWire.buildGroupInfo(
            groupId = "grp-1",
            title = "Работа | важная",
            about = "описание\nна две строки",
            ownerId = "pk_owner",
            inviteSlug = "AbCdEf2345678901",
            isPublic = true,
            topicsEnabled = false,
        )
        assertTrue(envelope.length <= GroupWire.MAX_ENVELOPE_BYTES)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.GroupInfo)
        val info = parsed as GroupWire.Packet.GroupInfo
        assertEquals("grp-1", info.groupId)
        assertEquals("Работа | важная", info.title)
        assertEquals("описание\nна две строки", info.about)
        assertEquals("pk_owner", info.ownerId)
        assertEquals("AbCdEf2345678901", info.inviteSlug)
        assertTrue(info.isPublic)
        assertFalse(info.topicsEnabled)
    }

    @Test
    fun groupInfoWithoutOwnerIsRejected() {
        // Карточка без владельца бесполезна: по ней нельзя проверить отправителя.
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val t = enc.encodeToString("Т".toByteArray(Charsets.UTF_8))
        assertNull(GroupWire.parse("APUGRP1|info|grp-1|$t|$t||$t|1|1"))
    }

    @Test
    fun joinDecisionCarriesVerdict() {
        val approved = GroupWire.parse(GroupWire.buildJoinDecision("g", "pk_abc", true))
        assertTrue(approved is GroupWire.Packet.JoinDecision)
        assertTrue((approved as GroupWire.Packet.JoinDecision).approved)

        val rejected = GroupWire.parse(GroupWire.buildJoinDecision("g", "pk_abc", false))
        assertFalse((rejected as GroupWire.Packet.JoinDecision).approved)
    }

    @Test
    fun pinRoundTrip() {
        val pinned = GroupWire.parse(GroupWire.buildPin("g", "t", "m1", true)) as GroupWire.Packet.Pin
        assertTrue(pinned.pinned)
        assertEquals("m1", pinned.messageId)

        val unpinned = GroupWire.parse(GroupWire.buildPin("g", "t", "m1", false)) as GroupWire.Packet.Pin
        assertFalse(unpinned.pinned)
    }

    @Test
    fun rosterRoundTrip() {
        val entries = listOf(
            GroupWire.RosterEntry("pk_owner", "Стас", GroupRole.OWNER),
            GroupWire.RosterEntry("pk_admin", "Аня | админ", GroupRole.ADMIN),
            GroupWire.RosterEntry("pk_user", "Гость", GroupRole.MEMBER),
        )
        val parsed = GroupWire.parse(GroupWire.buildRoster("g", entries)) as GroupWire.Packet.Roster
        assertEquals(3, parsed.entries.size)
        assertEquals("pk_admin", parsed.entries[1].nodeId)
        assertEquals("Аня | админ", parsed.entries[1].displayName)
        assertEquals(GroupRole.ADMIN, parsed.entries[1].role)
    }

    @Test
    fun rosterNormalizesUnknownRole() {
        val parsed = GroupWire.parse(
            GroupWire.buildRoster("g", listOf(GroupWire.RosterEntry("pk_x", "X", "EMPEROR")))
        ) as GroupWire.Packet.Roster
        assertEquals(GroupRole.MEMBER, parsed.entries[0].role)
    }

    @Test
    fun malformedEnvelopesAreRejected() {
        assertNull(GroupWire.parse(null))
        assertNull(GroupWire.parse(""))
        assertNull(GroupWire.parse("обычное сообщение без префикса"))
        assertNull(GroupWire.parse("APULAN1|iam|pk_abc"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|unknown|g|x"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_MESSAGE}|g"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_MESSAGE}||t|dGV4dA"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_PIN}|g|t|m|7"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_JOIN_DECISION}|g|pk|MAYBE"))
    }

    @Test
    fun invalidBase64IsRejectedNotThrown() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_MESSAGE}|g|t|!!!не-base64!!!"))
    }

    @Test
    fun oversizedEnvelopeIsRejected() {
        val huge = GroupWire.buildMessage("g", "t", "а".repeat(GroupWire.MAX_ENVELOPE_BYTES))
        assertTrue(huge.length > GroupWire.MAX_ENVELOPE_BYTES)
        assertFalse(GroupWire.isGroupPacket(huge))
        assertNull(GroupWire.parse(huge))
    }

    @Test
    fun lanSignalsAreNotMistakenForGroupPackets() {
        assertFalse(GroupWire.isGroupPacket("APULAN1|req|192.168.0.117|42108"))
        assertFalse(GroupWire.isGroupPacket("APULANHS1|pk_abc"))
        assertTrue(GroupWire.isGroupPacket(GroupWire.buildMessage("g", "t", "текст")))
    }

    @Test
    fun groupDeletedRoundTrip() {
        val envelope = GroupWire.buildGroupDeleted("grp-42")
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.GroupDeleted)
        assertEquals("grp-42", (parsed as GroupWire.Packet.GroupDeleted).groupId)
    }

    @Test
    fun groupDeletedRejectsMalformedEnvelope() {
        // Лишнее поле и пустой идентификатор группы — пакет отбрасывается.
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_GROUP_DELETED}|g|лишнее"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_GROUP_DELETED}||"))
    }

    @Test
    fun directoryRoundTrip() {
        // Роевой каталог: владелец делится каналом, получатель разбирает конверт.
        val envelope = GroupWire.buildDirectory(
            groupId = "grp-9",
            title = "Канал Владимира",
            about = "про рыбалку",
            ownerId = "pk_owner",
            slug = "Abcdefghijkmnopq",
            isChannel = true,
            needsApproval = false,
            hops = 1,
        )
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Directory)
        val dir = parsed as GroupWire.Packet.Directory
        assertEquals("grp-9", dir.groupId)
        assertEquals("Канал Владимира", dir.title)
        assertEquals("про рыбалку", dir.about)
        assertEquals("pk_owner", dir.ownerId)
        assertEquals("Abcdefghijkmnopq", dir.slug)
        assertTrue(dir.isChannel)
        assertFalse(dir.needsApproval)
        assertEquals(1, dir.hops)
    }

    @Test
    fun directoryRejectsShortEnvelope() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_DIRECTORY}|g|t|a|o|s|1|0"))
    }

    // ---------------- @ник-реестр ----------------

    @Test
    fun nicknameRoundTrip() {
        val envelope = GroupWire.buildNick("owner_pk", "nickname", 1770000000000L, hops = 0)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Nick)
        val nick = parsed as GroupWire.Packet.Nick
        assertEquals("owner_pk", nick.ownerId)
        assertEquals("nickname", nick.name)
        assertEquals(1770000000000L, nick.registeredAtMs)
        assertEquals(0, nick.hops)
    }

    @Test
    fun nicknameStripsLeadingAt() {
        val parsed = GroupWire.parse(GroupWire.buildNick("owner", "@nickname", 5L, hops = 1))
        val nick = parsed as GroupWire.Packet.Nick
        assertEquals("nickname", nick.name)
    }

    @Test
    fun nicknameRejectsShortEnvelope() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_NICK}|owner|bmFtZQ=="))
    }

    // ---------------- аватар-конверт ----------------

    @Test
    fun avatarRoundTrip() {
        val envelope = GroupWire.buildAvatar("owner_pk", "QUJD", 1770000000000L, hops = 1)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Avatar)
        val av = parsed as GroupWire.Packet.Avatar
        assertEquals("owner_pk", av.ownerId)
        assertEquals("QUJD", av.dataB64)
        assertEquals(1770000000000L, av.updatedAtMs)
        assertEquals(1, av.hops)
    }

    @Test
    fun avatarRejectsShortEnvelope() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_AVAT}|owner|QUJD|5"))
    }

    @Test
    fun avatarRejectsBlankData() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_AVAT}|owner| |5|0"))
    }

    /** Значок темы доходит до получателя шестым полем конверта. */
    @Test
    fun topicCreatedCarriesIcon() {
        val parsed = GroupWire.parse(GroupWire.buildTopicCreated("g", "t", "Флуд", "🔥"))
        assertTrue(parsed is GroupWire.Packet.TopicCreated)
        assertEquals("🔥", (parsed as GroupWire.Packet.TopicCreated).iconEmoji)
    }

    /** Конверты старого образца (5 частей) принимаются со пустым значком. */
    @Test
    fun legacyTopicWithoutIconStillParses() {
        val name = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Старая тема".toByteArray(Charsets.UTF_8))
        val parsed = GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_TOPIC}|g|t|$name")
        assertTrue(parsed is GroupWire.Packet.TopicCreated)
        assertEquals("", (parsed as GroupWire.Packet.TopicCreated).iconEmoji)
    }

    /** Список тем: строки со значком и без него в одном конверте. */
    @Test
    fun topicsListCarriesIconsAndLegacyRows() {
        val envelope = GroupWire.buildTopics(
            "g",
            listOf(
                GroupWire.TopicEntry("t1", "Общий", "💬"),
                GroupWire.TopicEntry("t2", "Флуд"),
            ),
        )
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Topics)
        val topics = (parsed as GroupWire.Packet.Topics).entries
        assertEquals("💬", topics[0].iconEmoji)
        assertEquals("", topics[1].iconEmoji)
    }

    /** «Представься»: конверт собирается и разбирается обратно. */
    @Test
    fun whoIsRoundTrip() {
        val envelope = GroupWire.buildWhoIs("pk_abc")
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.WhoIs)
        assertEquals("pk_abc", (parsed as GroupWire.Packet.WhoIs).requesterId)
    }

    // ── Досылка старых постов и правка ──────────────────────────────────────

    /**
     * Досланный пост несёт настоящего автора и исходное время: у получателя
     * он должен числиться за автором, а не за владельцем, который переслал.
     */
    @Test
    fun relayedMessageCarriesAuthorAndTime() {
        val envelope = GroupWire.buildMessage(
            groupId = "ch",
            topicId = "post-1",
            text = "старый пост",
            messageId = "m-1",
            senderName = "Автор",
            authorId = "pk_author",
            sentAtMs = 1_700_000_000_000L,
        )
        assertEquals(9, envelope.split('|').size)
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("m-1", msg.messageId)
        assertEquals("Автор", msg.senderName)
        assertEquals("pk_author", msg.authorId)
        assertEquals(1_700_000_000_000L, msg.sentAtMs)
    }

    /** Живое сообщение по-прежнему уходит 7-полевым конвертом: старые телефоны его понимают. */
    @Test
    fun liveMessageStaysSevenFields() {
        val envelope = GroupWire.buildMessage("g", "t", "текст", "m-2", "Имя")
        assertEquals(7, envelope.split('|').size)
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("", msg.authorId)
        assertEquals(0L, msg.sentAtMs)
    }

    /** Имя может быть пустым, а автор - нет: поле имени остаётся на месте. */
    @Test
    fun relayedMessageWithoutNameKeepsFieldOrder() {
        val envelope = GroupWire.buildMessage("g", "t", "текст", "m-3", "", "pk_a", 5L)
        val msg = GroupWire.parse(envelope) as GroupWire.Packet.Message
        assertEquals("m-3", msg.messageId)
        assertEquals("", msg.senderName)
        assertEquals("pk_a", msg.authorId)
        assertEquals(5L, msg.sentAtMs)
    }

    @Test
    fun editRoundTrip() {
        val envelope = GroupWire.buildEdit("g", "t", "m-9", "новый | текст\nвторая строка")
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Edit)
        val edit = parsed as GroupWire.Packet.Edit
        assertEquals("g", edit.groupId)
        assertEquals("t", edit.topicId)
        assertEquals("m-9", edit.messageId)
        assertEquals("новый | текст\nвторая строка", edit.text)
    }

    /** Правка без id сообщения бессмысленна - такой конверт отбрасывается. */
    @Test
    fun editWithoutMessageIdIsRejected() {
        val empty = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(0))
        assertNull(GroupWire.parse("APUGRP1|edit|g|t|$empty|$empty"))
    }

    @Test
    fun postsRequestRoundTrip() {
        val envelope = GroupWire.buildPostsRequest("ch", 20, listOf("t-1", "t-2"))
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.PostsRequest)
        val req = parsed as GroupWire.Packet.PostsRequest
        assertEquals("ch", req.groupId)
        assertEquals(20, req.limit)
        assertEquals(listOf("t-1", "t-2"), req.have)
    }

    @Test
    fun postsRequestWithoutHaveList() {
        val req = GroupWire.parse(GroupWire.buildPostsRequest("ch", 5)) as GroupWire.Packet.PostsRequest
        assertEquals(5, req.limit)
        assertTrue(req.have.isEmpty())
        assertNull(GroupWire.parse("APUGRP1|preq|ch|0"))
        assertNull(GroupWire.parse("APUGRP1|preq|ch|abc"))
    }

    // ── Рой, этап 2: полосы кусков и выборка соседей ─────────────────────────

    @Test
    fun pieceWantRoundTrip() {
        val envelope = GroupWire.buildPieceWant("ch", "topic|с трубой", 2, 4, listOf("m-1", "p|2"))
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.PieceWant)
        val want = parsed as GroupWire.Packet.PieceWant
        assertEquals("ch", want.groupId)
        assertEquals("topic|с трубой", want.topicId)
        assertEquals(2, want.stripe)
        assertEquals(4, want.stripes)
        assertEquals(listOf("m-1", "p|2"), want.have)
        assertEquals(7, envelope.split('|').size)
    }

    @Test
    fun pieceWantWithoutHaveList() {
        val want = GroupWire.parse(GroupWire.buildPieceWant("ch", "t", 0, 1, emptyList())) as GroupWire.Packet.PieceWant
        assertEquals(0, want.stripe)
        assertEquals(1, want.stripes)
        assertTrue(want.have.isEmpty())
    }

    /** Полоса вне диапазона - и при сборке, и при разборе - не проходит. */
    @Test
    fun pieceWantRejectsBadStripes() {
        val t = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("t".toByteArray(Charsets.UTF_8))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|4|4|"))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|0|0|"))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|0|${GroupWire.MAX_STRIPES + 1}|"))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|-1|2|"))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|x|2|"))
        assertNull(GroupWire.parse("APUGRP1|pwant|ch|$t|0|2"))
        var thrown = false
        try {
            GroupWire.buildPieceWant("ch", "t", 3, 3, emptyList())
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    /** Полосы вместе покрывают весь пост ровно один раз: номер 0 - текст, дальше куски. */
    @Test
    fun stripesPartitionPieces() {
        val ids = (0 until 19).map { "piece-$it" }
        for (m in 1..GroupWire.MAX_STRIPES) {
            val covered = ArrayList<String>()
            for (k in 0 until m) covered.addAll(GroupWire.stripe(ids, k, m, emptySet()))
            assertEquals(ids.sorted(), covered.sorted())
            assertEquals(ids.size, covered.size)
        }
        // Уже имеющиеся куски выпадают из полосы.
        assertEquals(listOf("piece-0", "piece-4"), GroupWire.stripe(ids.take(6), 0, 2, setOf("piece-2")))
        assertEquals(emptyList<String>(), GroupWire.stripe(ids, 1, 2, ids.toSet()))
    }

    @Test
    fun peersRoundTrip() {
        val ids = (1..GroupWire.MAX_PEERS).map { "pk_node$it" }
        val envelope = GroupWire.buildPeers("ch", 12_345, ids)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Peers)
        val peers = parsed as GroupWire.Packet.Peers
        assertEquals("ch", peers.groupId)
        assertEquals(12_345, peers.memberCount)
        assertEquals(ids, peers.nodeIds)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 4_000)
    }

    /** Лишние адреса сборка отрезает, пустой список и нулевой счётчик допустимы, мусор - нет. */
    @Test
    fun peersLimitsAndRejects() {
        val many = (1..GroupWire.MAX_PEERS + 5).map { "n$it" }
        val trimmed = GroupWire.parse(GroupWire.buildPeers("ch", 7, many)) as GroupWire.Packet.Peers
        assertEquals(GroupWire.MAX_PEERS, trimmed.nodeIds.size)
        val empty = GroupWire.parse(GroupWire.buildPeers("ch", 0, emptyList())) as GroupWire.Packet.Peers
        assertEquals(0, empty.memberCount)
        assertTrue(empty.nodeIds.isEmpty())
        assertNull(GroupWire.parse("APUGRP1|peers|ch|-1|a,b"))
        assertNull(GroupWire.parse("APUGRP1|peers|ch|x|a,b"))
        assertNull(GroupWire.parse("APUGRP1|peers|ch|5"))
        assertNull(GroupWire.parse("APUGRP1|peers|ch|5|" + (1..GroupWire.MAX_PEERS + 1).joinToString(",") { "n$it" }))
    }

    // ── Счётчики через владельца (этап 3) ─────────────────────────────────────

    @Test
    fun countersRequestRoundTrip() {
        val topics = (1..GroupWire.MAX_COUNTER_TOPICS).map { "topic-$it" }
        val envelope = GroupWire.buildCountersRequest("ch", topics + "topic-1" + "")
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.CountersRequest)
        val request = parsed as GroupWire.Packet.CountersRequest
        assertEquals("ch", request.groupId)
        assertEquals(topics, request.topicIds)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 1_000)
        assertNull(GroupWire.parse("APUGRP1|pcreq|ch|"))
        assertNull(GroupWire.parse("APUGRP1|pcreq|ch"))
        assertNull(GroupWire.parse("APUGRP1|pcreq|ch|" + (1..GroupWire.MAX_COUNTER_TOPICS + 1).joinToString(",") { "dA" }))
    }

    @Test
    fun countersRoundTripKeepsViewsAndTopReactions() {
        val many = (1..GroupWire.MAX_COUNTER_EMOJI + 3).map { i -> "e$i" to i }
        val cells = listOf(
            GroupWire.PostCounters("t1", "m1", 1_234, listOf("❤️" to 10, "🔥" to 3, "👍" to 0)),
            GroupWire.PostCounters("t2", "m2", 0, emptyList()),
            GroupWire.PostCounters("t3", "", 5, many),
        )
        val envelope = GroupWire.buildCounters("ch", cells)
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Counters)
        val counters = parsed as GroupWire.Packet.Counters
        assertEquals("ch", counters.groupId)
        assertEquals(3, counters.cells.size)
        assertEquals(GroupWire.PostCounters("t1", "m1", 1_234, listOf("❤️" to 10, "🔥" to 3)), counters.cells[0])
        assertEquals(GroupWire.PostCounters("t2", "m2", 0, emptyList()), counters.cells[1])
        val third = counters.cells[2]
        assertEquals("", third.messageId)
        assertEquals(GroupWire.MAX_COUNTER_EMOJI, third.reactions.size)
        assertEquals(many.sortedByDescending { it.second }.take(GroupWire.MAX_COUNTER_EMOJI), third.reactions)
    }

    @Test
    fun countersPacketWithMaxCellsFitsBroker() {
        val reactions = (1..GroupWire.MAX_COUNTER_EMOJI).map { i -> "\uD83D\uDE00$i" to 100_000 + i }
        val cells = (1..GroupWire.MAX_COUNTER_CELLS + 2).map { i ->
            GroupWire.PostCounters(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), 1_000_000 + i, reactions)
        }
        val envelope = GroupWire.buildCounters("ch", cells)
        val parsed = GroupWire.parse(envelope) as GroupWire.Packet.Counters
        assertEquals(GroupWire.MAX_COUNTER_CELLS, parsed.cells.size)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 4_000)
    }

    @Test
    fun countersRejectGarbage() {
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|dA,bQ,-1,"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|dA,bQ,x,"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|dA,bQ,1"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|dA,bQ,1,4pyF"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|dA,bQ,1,=3"))
        assertNull(GroupWire.parse("APUGRP1|pcnt|ch|,bQ,1,"))
        val empty = GroupWire.parse("APUGRP1|pcnt|ch|") as GroupWire.Packet.Counters
        assertTrue(empty.cells.isEmpty())
    }

    // ── Комментарии большого канала (рой, этап 4) ────────────────────────────

    @Test
    fun commentsRequestRoundTrip() {
        val have = (1..GroupWire.MAX_COMMENT_HAVE + 5).map { java.util.UUID.randomUUID().toString() }
        val envelope = GroupWire.buildCommentsRequest("ch", "topic|1", 99, 1_700_000_000_000L, 5L, have, listOf("abcdef01"))
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.CommentsRequest)
        val request = parsed as GroupWire.Packet.CommentsRequest
        assertEquals("ch", request.groupId)
        assertEquals("topic|1", request.topicId)
        assertEquals(GroupWire.MAX_COMMENT_BACKFILL, request.limit)
        assertEquals(1_700_000_000_000L, request.beforeMs)
        assertEquals(5L, request.afterMs)
        assertEquals(have.take(GroupWire.MAX_COMMENT_HAVE).map { it.take(GroupWire.COMMENT_ID_CHARS) }, request.have)
        assertEquals(listOf("abcdef01"), request.want)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 400)
    }

    @Test
    fun commentsRequestWithoutListsAndDefaults() {
        val request = GroupWire.parse(GroupWire.buildCommentsRequest("ch", "t", 5)) as GroupWire.Packet.CommentsRequest
        assertEquals(5, request.limit)
        assertEquals(0L, request.beforeMs)
        assertEquals(0L, request.afterMs)
        assertTrue(request.have.isEmpty())
        assertTrue(request.want.isEmpty())
        // Нулевой предел поднимается до единицы, а не ломает конверт.
        assertEquals(1, (GroupWire.parse(GroupWire.buildCommentsRequest("ch", "t", 0)) as GroupWire.Packet.CommentsRequest).limit)
    }

    @Test
    fun commentsRequestRejectsGarbage() {
        val topic = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("t".toByteArray())
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|20|0|0|"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|0|0|0||"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|21|0|0||"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|20|-1|0||"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|20|0|x||"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch||20|0|0||"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|20|0|0|" + (1..GroupWire.MAX_COMMENT_HAVE + 1).joinToString(",") { "a" } + "|"))
        assertNull(GroupWire.parse("APUGRP1|creq|ch|$topic|20|0|0|toolongidkey|"))
    }

    @Test
    fun commentIdsRoundTripAndLimit() {
        val ids = (1..GroupWire.MAX_COMMENT_IDS + 7).map { java.util.UUID.randomUUID().toString() }
        val envelope = GroupWire.buildCommentIds("ch", "t", 1_234, ids)
        val parsed = GroupWire.parse(envelope) as GroupWire.Packet.CommentIds
        assertEquals("t", parsed.topicId)
        assertEquals(1_234, parsed.count)
        assertEquals(GroupWire.MAX_COMMENT_IDS, parsed.ids.size)
        assertEquals(ids.take(GroupWire.MAX_COMMENT_IDS).map { GroupWire.commentIdKey(it) }, parsed.ids)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 1_100)
        val empty = GroupWire.parse(GroupWire.buildCommentIds("ch", "t", 0, emptyList())) as GroupWire.Packet.CommentIds
        assertTrue(empty.ids.isEmpty())
        assertNull(GroupWire.parse("APUGRP1|cids|ch|dA|-1|"))
        assertNull(GroupWire.parse("APUGRP1|cids|ch|dA|1"))
    }

    @Test
    fun commentCountsRoundTrip() {
        val counts = (1..GroupWire.MAX_COUNTER_TOPICS + 2).map { "topic-$it" to it * 10 } + ("topic-1" to 5) + ("" to 1) + ("neg" to -1)
        val envelope = GroupWire.buildCommentCounts("ch", counts)
        val parsed = GroupWire.parse(envelope) as GroupWire.Packet.CommentCounts
        assertEquals(GroupWire.MAX_COUNTER_TOPICS, parsed.counts.size)
        assertEquals("topic-1" to 10, parsed.counts.first())
        assertFalse(parsed.counts.any { it.first == "neg" || it.first.isBlank() })
        val empty = GroupWire.parse("APUGRP1|cinf|ch|") as GroupWire.Packet.CommentCounts
        assertTrue(empty.counts.isEmpty())
        assertNull(GroupWire.parse("APUGRP1|cinf|ch|dA=x"))
        assertNull(GroupWire.parse("APUGRP1|cinf|ch|=3"))
        assertNull(GroupWire.parse("APUGRP1|cinf|ch|dA=-2"))
    }

    @Test
    fun commentIdKeyIsStablePrefix() {
        assertEquals("12345678", GroupWire.commentIdKey("12345678-abcd-ef00-1111-222222222222"))
        assertEquals("abc", GroupWire.commentIdKey("abc"))
    }
}
