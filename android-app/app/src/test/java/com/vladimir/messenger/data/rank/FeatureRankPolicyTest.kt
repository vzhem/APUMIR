package com.vladimir.messenger.data.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт «что доступно до третьего ранга и что открывается потом».
 *
 * Правило одно на всё приложение: ранг 0–2 — минимальный режим (текст, приём
 * медиа, вступление в сообщества, игра сердечек, контакты). Отправка своего
 * наружу открывается с «Круга друзей» (3), а с «Проводника» (10) разрешено
 * всё: видео, создание групп и каналов, автосбор прокси. Выше 10 новых
 * возможностей нет.
 */
class FeatureRankPolicyTest {

    @Test
    fun basicModeBeforeThirdRankStaysMinimalButUsable() {
        listOf(0, 1, 2).forEach { rank ->
            assertTrue(FeatureRankPolicy.isAllowed(rank, RankCapability.SEND_TEXT))
            assertTrue(FeatureRankPolicy.isAllowed(rank, RankCapability.RECEIVE_MEDIA))
            assertTrue(FeatureRankPolicy.isAllowed(rank, RankCapability.JOIN_COMMUNITIES))
            // Без игры сердечек ранг не заработать — она обязана быть бесплатной.
            assertTrue(FeatureRankPolicy.isAllowed(rank, RankCapability.HEART_GAME))
            assertTrue(FeatureRankPolicy.isAllowed(rank, RankCapability.SHARE_CONTACT))
        }
    }

    @Test
    fun everythingThatSendsOwnContentOutwardOpensAtThirdRank() {
        val circle = listOf(
            RankCapability.SEND_PHOTO,
            RankCapability.SEND_VOICE,
            RankCapability.SEND_GIF,
            RankCapability.SEND_STICKER,
            RankCapability.SEND_DOCUMENT,
            RankCapability.SHARE_LOCATION,
            RankCapability.PUBLISH_STORY,
            // Видеоаватар — там же, где истории: это публикация своего кадра
            // наружу, а статичный аватар остаётся бесплатным.
            RankCapability.VIDEO_AVATAR,
            RankCapability.CALL_VOICE,
            RankCapability.CALL_VIDEO,
            RankCapability.CREATE_POLL,
        )
        circle.forEach { capability ->
            assertEquals(
                "${capability.name} must open exactly at rank 3",
                3,
                capability.minimumQualifiedReferrals,
            )
            assertFalse(FeatureRankPolicy.isAllowed(2, capability))
            assertTrue(FeatureRankPolicy.isAllowed(3, capability))
            assertTrue(FeatureRankPolicy.isAllowed(4, capability))
        }
    }

    @Test
    fun videoAndCommunitiesOpenAboveThirdRank() {
        listOf(
            RankCapability.SEND_VIDEO,
            RankCapability.CREATE_GROUP,
            RankCapability.CREATE_CHANNEL,
            RankCapability.PROXY_AUTOMATIC,
        ).forEach { capability ->
            assertEquals(10, capability.minimumQualifiedReferrals)
            assertFalse("${capability.name} stays closed on the circle rank",
                FeatureRankPolicy.isAllowed(3, capability))
            assertFalse("${capability.name} stays closed just below the conductor rank",
                FeatureRankPolicy.isAllowed(9, capability))
            assertTrue(FeatureRankPolicy.isAllowed(10, capability))
        }
    }

    @Test
    fun everythingIsAllowedFromTheTenthRank() {
        // Решение владельца: с 10 ранга разрешено всё. Ни один порог не вправе
        // уехать выше «Проводника», иначе здесь появится закрытая возможность.
        assertEquals(10, FeatureRankPolicy.FULL_ACCESS_RANK)
        assertEquals(
            10,
            RankCapability.entries.maxOf { it.minimumQualifiedReferrals },
        )
        assertFalse(FeatureRankPolicy.isEverythingAllowed(9))
        assertTrue(FeatureRankPolicy.isEverythingAllowed(10))
        RankCapability.entries.forEach { capability ->
            assertTrue(
                "${capability.name} must be open at rank 10",
                FeatureRankPolicy.isAllowed(10, capability),
            )
            assertEquals(0, FeatureRankPolicy.referralsShort(10, capability))
        }
        assertEquals(
            RankCapability.entries.toList(),
            FeatureRankPolicy.unlockedFeatures(10),
        )
    }

    @Test
    fun manualProxyStaysTheOnlyEarlyUnlock() {
        assertEquals(1, RankCapability.PROXY_MANUAL.minimumQualifiedReferrals)
        assertFalse(FeatureRankPolicy.isAllowed(0, RankCapability.PROXY_MANUAL))
        assertTrue(FeatureRankPolicy.isAllowed(1, RankCapability.PROXY_MANUAL))
    }

    @Test
    fun guestSummaryListsExactlyTheBasicMode() {
        val guest = RankFeatureSummary.describe(0)
        assertTrue("Текстовые сообщения" in guest)
        assertTrue("Получение файлов, фото и видео" in guest)
        assertTrue("Вступление в группы и каналы" in guest)
        assertTrue("Игра «Сердечки»" in guest)
        // Ничего из отправки до третьего ранга.
        assertTrue("Отправка фото" !in guest)
        assertTrue("Голосовые сообщения" !in guest)
        assertTrue("Голосовые звонки" !in guest)
        assertTrue("Публикация историй" !in guest)
        // Видеоаватар — тоже отправка своего наружу, поэтому гостю закрыт.
        assertTrue("Видеоаватар профиля" !in guest)
        assertTrue("Отправка видео" !in guest)
        assertTrue("Создание групп" !in guest)
    }

    @Test
    fun thirdRankCardNamesEveryNewUnlock() {
        val newAtCircle = RankFeatureSummary.describeNewAt(3)
        listOf(
            "Отправка фото",
            "Голосовые сообщения",
            "Отправка GIF",
            "Отправка стикеров",
            "Отправка файлов",
            "Отправка геолокации",
            "Публикация историй",
            "Видеоаватар профиля",
            "Голосовые звонки",
            "Видеозвонки",
            "Создание опросов",
        ).forEach { title ->
            assertTrue("$title must unlock at rank 3", title in newAtCircle)
        }
        // Видео и сообщества — не на третьем ранге.
        assertTrue("Отправка видео" !in newAtCircle)
        assertTrue("Создание групп" !in newAtCircle)
        assertTrue("Создание каналов" !in newAtCircle)
        val newAtConductor = RankFeatureSummary.describeNewAt(10)
        assertTrue("Отправка видео" in newAtConductor)
        assertTrue("Создание групп" in newAtConductor)
        assertTrue("Создание каналов" in newAtConductor)
        // Выше «Проводника» новых возможностей нет: только статус и знак.
        assertTrue(RankFeatureSummary.describeNewAt(30).isEmpty())
    }

    @Test
    fun everyCapabilityHasAUniqueTitle() {
        val titles = RankCapability.entries.map(RankFeatureSummary::titleOf)
        assertEquals(titles.size, titles.toSet().size)
        assertTrue(titles.none { it.isBlank() })
    }

    @Test
    fun rankNamesMatchTheSharedRankLadder() {
        assertEquals("Круг друзей", FeatureRankPolicy.rankNameFor(RankCapability.SEND_PHOTO))
        assertEquals("Круг друзей", FeatureRankPolicy.rankNameFor(RankCapability.VIDEO_AVATAR))
        assertEquals("Проводник", FeatureRankPolicy.rankNameFor(RankCapability.SEND_VIDEO))
        assertEquals("Проводник", FeatureRankPolicy.rankNameFor(RankCapability.CREATE_CHANNEL))
    }

    @Test
    fun strictGateRepeatsTheRuleForNonUiCallers() {
        FeatureRankPolicy.requireAllowed(3, RankCapability.SEND_PHOTO)
        FeatureRankPolicy.requireAllowed(10, RankCapability.CALL_VOICE)
        val failure = assertThrows(IllegalStateException::class.java) {
            FeatureRankPolicy.requireAllowed(2, RankCapability.CALL_VOICE)
        }
        assertTrue(failure.message.orEmpty().contains("CALL_VOICE"))
    }

    @Test
    fun negativeRankCountsAsGuest() {
        // Испорченное хранилище не должно открывать возможности, а подсказка
        // «сколько осталось» считается как у гостя: 3 - 0 = 3.
        assertEquals(3, FeatureRankPolicy.referralsShort(-5, RankCapability.SEND_PHOTO))
        assertFalse(FeatureRankPolicy.isAllowed(-1, RankCapability.SEND_PHOTO))
        assertEquals(1, FeatureRankPolicy.referralsShort(2, RankCapability.SEND_PHOTO))
        assertEquals(0, FeatureRankPolicy.referralsShort(10, RankCapability.SEND_PHOTO))
    }
}
