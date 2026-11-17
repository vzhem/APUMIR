package com.vladimir.messenger.data.directpoll

import com.vladimir.messenger.data.local.entity.DirectPollEntity
import com.vladimir.messenger.data.local.entity.DirectPollVoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ карты карточек личных опросов.
 *
 * Экран чата ищет карточку по id сообщения (`uiState.polls[message.id]`), как и
 * групповые опросы. Пока `observeChat` клад в карту `dp:<messageId>`, карточка
 * не находилась никогда — ни у автора сразу после отправки, ни у собеседника.
 */
class DirectPollCardsTest {

    private fun row(
        messageId: String,
        options: List<String> = listOf("Первый", "Второй"),
        anonymous: Boolean = false,
        creatorId: String = "pk_me",
    ) = DirectPollEntity(
        pollId = DirectPollWire.pollIdOf(messageId),
        chatId = "chat-1",
        messageId = messageId,
        question = "Куда идём?",
        optionsCsv = options.joinToString(",") { DirectPollWire.enc(it) },
        anonymous = anonymous,
        creatorId = creatorId,
        createdAtMs = 1_700_000_000_000L,
    )

    @Test
    fun `card is indexed by message id - the key the chat screen looks up`() {
        val cards = directPollCards(listOf(row("m-1")), emptyList(), self = "pk_me")

        assertEquals(setOf("m-1"), cards.keys)
        assertFalse("pollId не должен быть ключом карты", cards.containsKey("dp:m-1"))
        assertEquals("dp:m-1", cards.getValue("m-1").pollId)
        assertEquals("m-1", cards.getValue("m-1").messageId)
    }

    @Test
    fun `own poll is mine and keeps my choice`() {
        val votes = listOf(
            DirectPollVoteEntity(pollId = "dp:m-1", voterId = "self", choicesCsv = "1"),
            DirectPollVoteEntity(pollId = "dp:m-1", voterId = "pk_peer", choicesCsv = "0"),
        )
        val card = directPollCards(listOf(row("m-1")), votes, self = "pk_me").getValue("m-1")

        assertTrue(card.isMine)
        assertEquals(listOf(1), card.myChoices)
        assertEquals(listOf(1, 1), card.options.map { it.votes })
        assertEquals(2, card.totalVoters)
    }

    @Test
    fun `someone else's poll is not mine`() {
        val card = directPollCards(listOf(row("m-2", creatorId = "pk_peer")), emptyList(), self = "pk_me")
            .getValue("m-2")

        assertFalse(card.isMine)
    }

    @Test
    fun `anonymous poll hides voters but keeps tallies`() {
        val votes = listOf(DirectPollVoteEntity(pollId = "dp:m-3", voterId = "pk_peer", choicesCsv = "0"))
        val card = directPollCards(listOf(row("m-3", anonymous = true)), votes, self = "pk_me")
            .getValue("m-3")

        assertTrue(card.voters.isEmpty())
        assertEquals(1, card.options.first().votes)
    }

    @Test
    fun `option text with a comma survives the csv round trip`() {
        val card = directPollCards(
            listOf(row("m-4", options = listOf("Да, конечно", "Нет"))),
            emptyList(),
            self = "pk_me",
        ).getValue("m-4")

        assertEquals(listOf("Да, конечно", "Нет"), card.options.map { it.text })
    }

    @Test
    fun `votes for another poll do not leak into this card`() {
        val votes = listOf(DirectPollVoteEntity(pollId = "dp:other", voterId = "self", choicesCsv = "0"))
        val card = directPollCards(listOf(row("m-5")), votes, self = "pk_me").getValue("m-5")

        assertTrue(card.myChoices.isEmpty())
        assertEquals(listOf(0, 0), card.options.map { it.votes })
    }
}
