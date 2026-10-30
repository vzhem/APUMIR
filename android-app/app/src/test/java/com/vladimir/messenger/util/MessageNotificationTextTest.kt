package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationTextTest {
    @Test
    fun legacyInvitationFromNotificationShowsGroupNotTransportLink() {
        val text = "Группа «БВ» в APU.\n" +
            "https://p2p-relay.apuchat.workers.dev/i?slug=Abcdefghijkmnopq&g=group-id&o=pk_owner&a=1"
        val preview = MessageNotificationText.format(text)
        assertTrue(preview.contains("Приглашение в группу «БВ»"))
        assertTrue(preview.contains("Описание не указано"))
        assertTrue(preview.contains("Вступление по заявке"))
        assertFalse(preview.contains("https://"))
        assertFalse(preview.contains("pk_owner"))
        assertFalse(preview.contains("slug="))
    }

    @Test
    fun newInvitationIncludesDetailsWithoutInviteOrDownloadLinks() {
        val text = GroupInviteCardSender.build(
            false, "Соседи", "Чат нашего дома", "https://apumir.app/s/Abcdefgh",
            isPublic = false, needsApproval = true,
        ) + "\nСкачать APU:\nhttps://example.org/app.apk"
        val preview = MessageNotificationText.format(text)
        assertEquals(
            "Приглашение в группу «Соседи»\nЗакрытая группа\nЧат нашего дома\nВступление по заявке — требуется одобрение",
            preview,
        )
    }

    @Test
    fun channelWithDirectAdmissionIsNotCalledGroup() {
        val preview = MessageNotificationText.format(GroupInviteCardSender.build(
            true, "Новости", "Объявления", "https://apumir.app/s/Abcdefgh",
            isPublic = true, needsApproval = false,
        ))
        assertTrue(preview.startsWith("Приглашение в канал «Новости»"))
        assertTrue(preview.contains("Открытый канал"))
        assertTrue(preview.contains("Вступление без одобрения"))
        assertFalse(preview.contains("групп"))
    }

    @Test
    fun legacyShortInvitationDoesNotInventAdmissionPolicy() {
        val preview = MessageNotificationText.format("Группа «БВ» в APU.\nhttps://apumir.app/s/Abcdefgh")
        assertTrue(preview.contains("Тип доступа не указан"))
        assertTrue(preview.contains("Условия вступления не указаны"))
        assertFalse(preview.contains("https://"))
    }

    @Test
    fun multipleInvitationsShowNamesAndKindsOnly() {
        val preview = MessageNotificationText.format(
            "Присоединяйся к моим сообществам в APU.\n" +
                "Группа «Соседи»:\nhttps://apumir.app/s/Abcdefgh\n" +
                "Канал «Новости»:\nhttps://apumir.app/s/Bcdefghi\n" +
                "Скачать APU:\nhttps://example.org/app.apk"
        )
        assertEquals("Приглашение в сообщества APU\nГруппа «Соседи»\nКанал «Новости»", preview)
    }

    @Test
    fun ordinaryMessagesAndLinksAreNotModified() {
        listOf("Привет!", "", "Посмотри https://example.org", "https://example.org").forEach {
            assertEquals(it, MessageNotificationText.format(it))
        }
    }
}
