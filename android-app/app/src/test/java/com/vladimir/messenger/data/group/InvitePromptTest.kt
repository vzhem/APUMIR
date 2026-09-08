package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слова первого окна по ссылке-приглашению. Владелец (2026-09-08): пересланная
 * ссылка на пост канала спрашивала «Войти в группу?» - «нужно чтобы не путал
 * людей». Проверяем ровно то, что человек видит.
 */
class InvitePromptTest {

    private val slug = "Abcdefghijkmnopq"

    @Test
    fun `forwarded post link asks to open the post, not to join a group`() {
        val link = GroupInviteLinks.buildWebLink(
            slug = slug,
            groupId = "grp-1",
            ownerId = "pk_owner",
            isChannel = true,
            postTopicId = "topic-77",
        )
        val prompt = invitePromptFor(GroupInviteLinks.parseTarget(link))
        assertEquals("Открыть пост?", prompt.title)
        assertEquals("Открыть", prompt.confirm)
        assertTrue(prompt.body, prompt.body.contains("пост"))
        assertFalse("слово «группа» путает людей", prompt.body.contains("групп"))
        assertFalse(prompt.body, prompt.body.contains("заявк"))
    }

    /** Та же ссылка, но скопированная вместе с текстом сообщения. */
    @Test
    fun `post link pasted with the message text is still a post`() {
        val link = GroupInviteLinks.buildWebLink(
            slug = slug,
            groupId = "grp-1",
            ownerId = "pk_owner",
            isChannel = true,
            postTopicId = "topic-77",
        )
        val pasted = "Смотри какой пост: $link очень интересно"
        assertEquals("Открыть пост?", invitePromptFor(GroupInviteLinks.parseTarget(pasted)).title)
    }

    @Test
    fun `post in a channel with approval says the owner will approve`() {
        val link = GroupInviteLinks.build(
            slug = slug,
            groupId = "grp-1",
            ownerId = "pk_owner",
            isChannel = true,
            requestApproval = true,
            postTopicId = "topic-77",
        )
        val prompt = invitePromptFor(GroupInviteLinks.parseTarget(link))
        assertEquals("Открыть пост?", prompt.title)
        assertTrue(prompt.body, prompt.body.contains("одобрит владелец канала"))
    }

    @Test
    fun `channel invite talks about subscribing`() {
        val open = GroupInviteLinks.build(slug, "grp-1", "pk_owner", isChannel = true)
        val openPrompt = invitePromptFor(GroupInviteLinks.parseTarget(open))
        assertEquals("Подписаться на канал?", openPrompt.title)
        assertEquals("Подписаться", openPrompt.confirm)
        assertFalse(openPrompt.body, openPrompt.body.contains("групп"))

        val approved = GroupInviteLinks.build(
            slug, "grp-1", "pk_owner", isChannel = true, requestApproval = true,
        )
        val approvedPrompt = invitePromptFor(GroupInviteLinks.parseTarget(approved))
        assertEquals("Подписаться на канал?", approvedPrompt.title)
        assertEquals("Отправить заявку", approvedPrompt.confirm)
    }

    @Test
    fun `group invite keeps the old wording and promises a request only with approval`() {
        val open = GroupInviteLinks.build(slug, "grp-1", "pk_owner")
        val openPrompt = invitePromptFor(GroupInviteLinks.parseTarget(open))
        assertEquals("Войти в группу?", openPrompt.title)
        assertEquals("Войти", openPrompt.confirm)
        assertFalse(openPrompt.body, openPrompt.body.contains("заявк"))

        val approved = GroupInviteLinks.build(slug, "grp-1", "pk_owner", requestApproval = true)
        val approvedPrompt = invitePromptFor(GroupInviteLinks.parseTarget(approved))
        assertEquals("Войти в группу?", approvedPrompt.title)
        assertEquals("Отправить заявку", approvedPrompt.confirm)
        assertTrue(approvedPrompt.body.contains("заявку на вступление"))
    }

    /** Ссылка старого образца без признаков и вовсе неразобранная - прежние слова. */
    @Test
    fun `old link without flags and unparsable input fall back to the group wording`() {
        val old = GroupInviteLinks.build(slug)
        assertEquals("Войти в группу?", invitePromptFor(GroupInviteLinks.parseTarget(old)).title)
        assertEquals("Войти в группу?", invitePromptFor(null).title)
    }

    @Test
    fun `joined messages name channels`() {
        val channel = JoinOutcome.Joined("grp-1", "Новости", isChannel = true)
        assertEquals("Вы подписаны на канал «Новости»", joinedMessage(channel))
        val group = JoinOutcome.Joined("grp-2", "Двор", isChannel = false)
        assertEquals("Вы вошли в группу «Двор»", joinedMessage(group))
    }

    @Test
    fun `request messages do not promise a request when none is needed`() {
        val instant = JoinOutcome.RequestSent("grp-1", "", isChannel = true, needsApproval = false)
        val instantText = requestSentMessage(instant, forPost = true)
        assertTrue(instantText, instantText.startsWith("Подписываемся на канал"))
        assertTrue(instantText, instantText.contains("а пост - в его ленте"))
        assertFalse(instantText, instantText.contains("Заявка"))

        val approval = JoinOutcome.RequestSent("grp-1", "Новости", isChannel = true, needsApproval = true)
        val approvalText = requestSentMessage(approval, forPost = false)
        assertTrue(approvalText, approvalText.startsWith("Заявка на подписку в «Новости»"))
        assertTrue(approvalText, approvalText.contains("канал появится в списке"))

        val group = JoinOutcome.RequestSent("grp-2", "Двор", isChannel = false, needsApproval = true)
        val groupText = requestSentMessage(group, forPost = false)
        assertTrue(groupText, groupText.startsWith("Заявка в «Двор»"))
        assertTrue(groupText, groupText.contains("группа появится в списке"))

        // Название ещё неизвестно (владелец не ответил): слово стоит в нужном падеже.
        val nameless = JoinOutcome.RequestSent("grp-3", "", isChannel = false, needsApproval = false)
        val namelessText = requestSentMessage(nameless, forPost = false)
        assertTrue(namelessText, namelessText.startsWith("Входим в группу:"))
        assertFalse(namelessText, namelessText.contains("в группа"))
    }
}
