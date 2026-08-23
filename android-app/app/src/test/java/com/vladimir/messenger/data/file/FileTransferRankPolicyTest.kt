package com.vladimir.messenger.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferRankPolicyTest {

    @Test
    fun exactReferralThresholdsSelectExpectedRanks() {
        val expected = listOf(
            0 to "Гость",
            1 to "Первый связной",
            3 to "Круг друзей",
            10 to "Проводник",
            20 to "Организатор",
            30 to "Навигатор",
            50 to "Амбассадор",
            100 to "Строитель сообщества",
            200 to "Хранитель сети",
            300 to "Маяк APU",
            500 to "Лидер сообщества",
            700 to "Легенда APU",
            1000 to "Создатель сети",
        )
        expected.forEach { (count, name) ->
            assertEquals(name, FileTransferRankPolicy.entitlement(count).rankName)
        }
        assertEquals("Первый связной", FileTransferRankPolicy.entitlement(2).rankName)
        assertEquals("Создатель сети", FileTransferRankPolicy.entitlement(10_000).rankName)
    }

    @Test
    fun photoFileAndVideoSendingIsAvailableAtEveryRank() {
        canSend(0, "image/jpeg", 1)
        canSend(0, "application/pdf", 1)
        canSend(0, "video/mp4", 1)
        canSend(1, "application/pdf", 1)
        canSend(3, "video/mp4", 1)
    }

    @Test
    fun rankDoesNotCapFileBytes() {
        val mib = 1024L * 1024
        canSend(0, "image/png", Long.MAX_VALUE)
        canSend(1, "application/zip", 10 * mib + 1)
        canSend(10, "video/mp4", Long.MAX_VALUE)
        assertEquals("Проводник", FileTransferRankPolicy.entitlement(10).rankName)
    }

    @Test
    fun unknownMimeIsConservativelyTreatedAsGenericFile() {
        assertEquals(
            FileTransferRankPolicy.Category.FILE,
            FileTransferRankPolicy.categoryFor("application/octet-stream"),
        )
        canSend(0, "application/octet-stream", 1)
        canSend(1, "application/octet-stream", 1)
    }

    @Test
    fun groupAndChannelCreationUnlockAtTenAndThirty() {
        assertTrue(!FileTransferRankPolicy.canCreateGroup(9))
        assertTrue(FileTransferRankPolicy.canCreateGroup(10))
        assertTrue(FileTransferRankPolicy.canCreateGroup(1_000))
        assertTrue(!FileTransferRankPolicy.canCreateChannel(29))
        assertTrue(FileTransferRankPolicy.canCreateChannel(30))
        assertTrue(FileTransferRankPolicy.canCreateChannel(1_000))
    }

    @Test
    fun automaticProxyUnlocksAtTenAndManualAtOne() {
        assertTrue(!FileTransferRankPolicy.canUseAutomaticProxy(9))
        assertTrue(FileTransferRankPolicy.canUseAutomaticProxy(10))
        assertTrue(FileTransferRankPolicy.canUseAutomaticProxy(1_000))
        assertTrue("Автосбор и автоматический выбор прокси" !in
            FileTransferRankPolicy.entitlement(9).unlockedFeatureSummary())
        assertTrue("Автосбор и автоматический выбор прокси" in
            FileTransferRankPolicy.entitlement(10).unlockedFeatureSummary())
        // Ручные прокси — с первого квалифицированного приглашения; свежая установка — нет.
        assertTrue(!FileTransferRankPolicy.canUseManualProxy(0))
        assertTrue(FileTransferRankPolicy.canUseManualProxy(1))
        assertTrue("Ручное добавление прокси" !in
            FileTransferRankPolicy.entitlement(0).unlockedFeatureSummary())
        assertTrue("Ручное добавление прокси" in
            FileTransferRankPolicy.entitlement(1).unlockedFeatureSummary())
    }

    @Test
    fun freshInstallKeepsBasicCommunicationAndJoining() {
        assertTrue(FileTransferRankPolicy.canSendTextAtAnyRank())
        assertTrue(FileTransferRankPolicy.canJoinGroupsAtAnyRank())
        assertTrue(FileTransferRankPolicy.canJoinChannelsAtAnyRank())
        assertTrue(FileTransferRankPolicy.canReceiveAtAnyRank())
        val base = FileTransferRankPolicy.entitlement(0).unlockedFeatureSummary()
        assertTrue("Текстовые сообщения" in base)
        assertTrue("Вступление в группы и каналы" in base)
        assertTrue("Получение файлов, фото и видео" in base)
        assertTrue("Отправка фото" in base)
        assertTrue("Отправка файлов" in base)
        assertTrue("Отправка видео" in base)
        assertTrue("Создание групп" !in base)
        assertTrue("Создание каналов" !in base)
    }

    @Test
    fun rankSummariesDescribeCommunityUnlocks() {
        val conductor = FileTransferRankPolicy.entitlement(10).unlockedFeatureSummary()
        assertTrue("Создание групп" in conductor)
        assertTrue("Создание каналов" !in conductor)
        val navigator = FileTransferRankPolicy.entitlement(30).unlockedFeatureSummary()
        assertTrue("Создание групп" in navigator)
        assertTrue("Создание каналов" in navigator)
    }

    private fun canSend(referrals: Int, mediaType: String, bytes: Long) {
        FileTransferRankPolicy.requireCanSend(referrals, mediaType, bytes)
    }

}
