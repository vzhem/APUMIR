package com.vladimir.messenger.data.directpoll

import com.vladimir.messenger.data.rank.FeatureRankPolicy
import com.vladimir.messenger.data.rank.RankCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Опрос в личном чате — возможность ранга «Круг друзей» (3), решение владельца
 * от 2026-11-03.
 *
 * Проверяем именно связку «опрос ↔ ранг», а не таблицу целиком (её покрывает
 * `FeatureRankPolicyTest`): если кто-то поменяет порог у `CREATE_POLL`, экран
 * чата и это правило разъедутся молча — кнопка появится у «Гостя».
 */
class DirectPollRankTest {

    @Test
    fun `poll needs the third rank`() {
        assertTrue(FeatureRankPolicy.isAllowed(3, RankCapability.CREATE_POLL))
        assertFalse(FeatureRankPolicy.isAllowed(2, RankCapability.CREATE_POLL))
        assertFalse(FeatureRankPolicy.isAllowed(0, RankCapability.CREATE_POLL))
        assertTrue(FeatureRankPolicy.isAllowed(10, RankCapability.CREATE_POLL))
    }

    @Test
    fun `voting itself is never gated`() {
        // Голосовать можно всегда: иначе опрос автора ранга 3 остался бы без
        // ответа у собеседника, который ещё не заработал ранг.
        assertTrue(DirectPollTally.canVote(closed = false, optionCount = 2, index = 0))
        // Пустая строка - не пакет опроса: случайный текст в чат не съедается.
        assertFalse(DirectPollWire.isPollPacket(""))
    }
}
