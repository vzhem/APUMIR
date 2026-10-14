package com.vladimir.messenger.data.story

import org.junit.Assert.*
import org.junit.Test

/**
 * Конверт откликов на историю: разбор, сборка, пределы и обратная совместимость.
 *
 * Формат критичен: его читают телефоны без сервера, а старая версия APU обязана
 * пакет молча отбросить, а не показать чужой комментарий как историю автора.
 */
class StoryFeedbackWireTest {

    /** Префикс истории (`StoryStore.PREFIX`). Литерал: StoryStore - класс Android, его нет в чистой компиляции. */
    private val storyPrefix = "APUSTORY1:"

    private val story = "1f0e6c6a-6d1a-4b7c-9d2e-2f6a0b6f6f11"
    private val author = "pk_" + "a".repeat(32)
    private val viewer = "pk_" + "b".repeat(32)

    @Test fun reactionRoundTripKeepsEmojiActorAndFlag() {
        val raw = StoryFeedbackWire.buildReaction(story, author, viewer, "Влад", "🔥", true, 1_700_000_000_000L)
        val packet = StoryFeedbackWire.parse(raw) as StoryFeedbackWire.Packet.React
        assertEquals(story, packet.storyId)
        assertEquals(author, packet.authorId)
        assertEquals(viewer, packet.actorId)
        assertEquals("Влад", packet.actorName)
        assertEquals("🔥", packet.emoji)
        assertTrue(packet.added)
        assertEquals(1_700_000_000_000L, packet.atMs)
    }

    @Test fun removedReactionTravelsAsEmptyEmojiTombstone() {
        // Снятие строится ПУСТЫМ значком: в книге откликов прежний значок уже
        // заменён надгробием, и `outgoing` отправляет именно его. Требование
        // значка на снятии означало бы, что «снял» до автора не доезжает.
        val raw = StoryFeedbackWire.buildReaction(story, author, viewer, "Влад", "", false, 1_700_000_000_001L)
        assertNotNull(raw)
        val packet = StoryFeedbackWire.parse(raw!!) as StoryFeedbackWire.Packet.React
        assertFalse(packet.added)
        assertEquals("", packet.emoji)
        assertEquals(viewer, packet.actorId)
        // Пустым значком нельзя ПОСТАВИТЬ реакцию: это мусорный пакет.
        assertNull(StoryFeedbackWire.buildReaction(story, author, viewer, "Влад", "", true, 1L))
    }

    @Test fun commentSurvivesPipesColonsAndNewlines() {
        val body = "Смотрите: огонь|жара; \nперенос\tстроки и «кавычки» 🔥🔥"
        val raw = StoryFeedbackWire.buildComment(story, author, viewer, "Влад", "c-1", body, 1_700_000_000_002L)
        val packet = StoryFeedbackWire.parse(raw) as StoryFeedbackWire.Packet.CommentPacket
        assertEquals("c-1", packet.commentId)
        // Разделители провода не рвут пакет, а переводы строк становятся пробелами.
        assertEquals("Смотрите: огонь|жара; перенос строки и «кавычки» 🔥🔥", packet.body)
    }

    @Test fun longCommentIsCutToTheStoryTextLimit() {
        val raw = StoryFeedbackWire.buildComment(story, author, viewer, "", "c-2", "ы".repeat(500), 1L)
        val packet = StoryFeedbackWire.parse(raw) as StoryFeedbackWire.Packet.CommentPacket
        assertEquals(StoryFeedbackWire.MAX_COMMENT_CHARS, packet.body.length)
    }

    @Test fun blankCommentAndBlankEmojiAreRejected() {
        assertNull(StoryFeedbackWire.buildComment(story, author, viewer, "", "c-3", "   \n\t ", 1L))
        assertNull(StoryFeedbackWire.buildReaction(story, author, viewer, "", " ", true, 1L))
        // Значок с разделителем провода - не значок, а попытка сдвинуть поля.
        assertNull(StoryFeedbackWire.buildReaction(story, author, viewer, "", "🔥|x", true, 1L))
    }

    @Test fun longNameIsCutAndFlattened() {
        val raw = StoryFeedbackWire.buildReaction(story, author, viewer, "Имя\n\nФамилия".padEnd(200, 'x'), "👍", true, 5L)
        val packet = StoryFeedbackWire.parse(raw) as StoryFeedbackWire.Packet.React
        assertEquals(StoryFeedbackWire.MAX_NAME_CHARS, packet.actorName.length)
        assertFalse(packet.actorName.contains('\n'))
    }

    @Test fun askRoundTripAndStateRoundTrip() {
        val ask = StoryFeedbackWire.parse(
            StoryFeedbackWire.buildStateRequest(story, author, viewer, "Влад", 7L)
        ) as StoryFeedbackWire.Packet.Ask
        assertEquals(story, ask.storyId)
        assertEquals(author, ask.authorId)

        val reactions = listOf(
            StoryFeedbackWire.Reaction(viewer, "Влад", "🔥", 10L),
            StoryFeedbackWire.Reaction("pk_" + "c".repeat(32), "", "", 11L),
        )
        val comments = listOf(
            StoryFeedbackWire.Comment("c-9", viewer, "Влад", "Красиво!", 12L),
        )
        val state = StoryFeedbackWire.parse(
            StoryFeedbackWire.buildState(story, author, reactions, comments, 13L)
        ) as StoryFeedbackWire.Packet.State
        assertEquals(2, state.reactions.size)
        // Надгробие снятой реакции обязано доехать: без него значок воскреснет.
        assertTrue(state.reactions.any { it.removed })
        assertEquals(1, state.comments.size)
        assertEquals("Красиво!", state.comments.single().body)
        assertEquals(author, state.actorId)
    }

    @Test fun stateIsTrimmedUntilItFitsTheStoryPacketLimit() {
        val reactions = (1..StoryFeedbackWire.MAX_STATE_REACTIONS).map {
            StoryFeedbackWire.Reaction("pk_" + it.toString().padStart(32, 'x'), "Имя$it", "🔥", it.toLong())
        }
        val comments = (1..StoryFeedbackWire.MAX_STATE_COMMENTS).map {
            StoryFeedbackWire.Comment(
                id = "c-$it",
                actorId = "pk_" + it.toString().padStart(32, 'x'),
                actorName = "Имя$it",
                body = "комментарий $it ".repeat(20),
                atMs = it.toLong(),
            )
        }
        val raw = StoryFeedbackWire.buildState(story, author, reactions, comments, 99L)
        assertNotNull(raw)
        assertTrue(raw!!.toByteArray(Charsets.UTF_8).size <= StoryFeedbackWire.MAX_PACKET_BYTES)
        val state = StoryFeedbackWire.parse(raw) as StoryFeedbackWire.Packet.State
        // Резались СТАРЫЕ отклики: самые свежие остались, предел не превышен.
        assertTrue(state.comments.isNotEmpty())
        assertTrue(state.reactions.isNotEmpty())
        assertEquals("c-${StoryFeedbackWire.MAX_STATE_COMMENTS}", state.comments.last().id)
    }

    @Test fun malformedPacketsAreDroppedSilently() {
        assertNull(StoryFeedbackWire.parse(null))
        assertNull(StoryFeedbackWire.parse(""))
        assertNull(StoryFeedbackWire.parse("$storyPrefix{\"id\":\"x\",\"text\":\"история\"}"))
        assertNull(StoryFeedbackWire.parse(StoryFeedbackWire.MARKER + "unknown|1|2|3|4|5|6|7|8"))
        // Полей меньше, чем нужно.
        assertNull(StoryFeedbackWire.parse(StoryFeedbackWire.MARKER + "react|abc"))
        // Не-base64 поле.
        assertNull(StoryFeedbackWire.parse(StoryFeedbackWire.MARKER + "react|!!!|!!!|!!!|!!!|!!!|1|!!!|1|"))
        // Время не числом и время нулём.
        val react = StoryFeedbackWire.buildReaction(story, author, viewer, "", "👍", true, 5L)!!
        assertNull(StoryFeedbackWire.parse(react.replace("|5|", "|xyz|")))
        assertNull(StoryFeedbackWire.parse(react.replace("|5|", "|0|")))
    }

    @Test fun oldVersionDropsFeedbackInsteadOfShowingItAsAStory() {
        // Старая версия разбирает пакет истории как JSON и требует поле `text`.
        // Конверт отклика - не JSON и `text` не содержит: значит, старый телефон
        // отбросит его (JSONException), а не покажет комментарий как историю.
        val packets = listOf(
            StoryFeedbackWire.buildReaction(story, author, viewer, "", "👍", true, 5L),
            StoryFeedbackWire.buildComment(story, author, viewer, "", "c-1", "текст", 5L),
            StoryFeedbackWire.buildStateRequest(story, author, viewer, "", 5L),
        )
        packets.forEach { raw ->
            assertNotNull(raw)
            assertTrue(raw!!.startsWith(storyPrefix))
            val body = raw.removePrefix(storyPrefix)
            assertFalse(body.startsWith("{"))
            assertFalse(body.contains("\"text\""))
            assertFalse(body.contains("\"kind\":\"photo\""))
        }
    }

    @Test fun transportIdIsStablePerActionAndUniquePerVersion() {
        assertEquals(
            StoryFeedbackWire.transportId(story, "react", viewer, 5L),
            StoryFeedbackWire.transportId(story, "react", viewer, 5L),
        )
        assertNotEquals(
            StoryFeedbackWire.transportId(story, "react", viewer, 5L),
            StoryFeedbackWire.transportId(story, "react", viewer, 6L),
        )
        assertNotEquals(
            StoryFeedbackWire.transportId(story, "state", author, 1L),
            StoryFeedbackWire.transportId(story, "state", author, 2L),
        )
        // Транспорт и релей работают с UUID: повторная волна обязана быть тем же сообщением.
        assertTrue(StoryFeedbackWire.transportId(story, "ask", viewer, 5L).matches(Regex("[0-9a-f-]{36}")))
    }

    @Test fun oversizedIdentifiersAreRejected() {
        assertNull(StoryFeedbackWire.buildReaction("x".repeat(101), author, viewer, "", "👍", true, 1L))
        assertNull(StoryFeedbackWire.buildReaction(story, "pk_" + "a".repeat(300), viewer, "", "👍", true, 1L))
        assertNull(StoryFeedbackWire.buildReaction(story, author, "", "", "👍", true, 1L))
    }
}
