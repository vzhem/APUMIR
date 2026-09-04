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
}
