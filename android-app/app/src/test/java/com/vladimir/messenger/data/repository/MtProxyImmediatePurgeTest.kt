package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило владельца «нерабочие прокси удалять сразу»: не-Manual прокси, проваливший текущую
 * проверку и набравший ≥2 суммарных провалов, удаляется немедленно; первый промах прощается;
 * Manual-прокси и прошедшие проверку не трогаются.
 */
class MtProxyImmediatePurgeTest {
    private fun proxy(
        id: String,
        source: String = "CHANNEL",
        failCount: Int = 0,
        isActive: Boolean = false,
    ) = MtProtoProxyEntity(
        id = id,
        host = "host-$id",
        port = 443,
        secret = "ee",
        source = source,
        failCount = failCount,
        isActive = isActive,
    )

    @Test
    fun secondFailurePurgesImmediately() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("dead", failCount = 1))

        // healthcheck уже инкрементировал счётчик (markFailed), затем идёт чистка
        repo.markFailed("dead")
        val purged = repo.purgeFailedNow(listOf("dead"))

        assertEquals(1, purged)
        assertNull(dao.getById("dead"))
    }

    @Test
    fun firstFailureIsForgiven() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("flaky", failCount = 0))

        repo.markFailed("flaky")
        val purged = repo.purgeFailedNow(listOf("flaky"))

        assertEquals(0, purged)
        assertTrue(dao.getById("flaky") != null)
    }

    @Test
    fun manualProxiesAreNeverAutoPurged() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("mine", source = "MANUAL", failCount = 5))

        val purged = repo.purgeFailedNow(listOf("mine"))

        assertEquals(0, purged)
        assertTrue(dao.getById("mine") != null)
    }

    @Test
    fun passingProxiesAreNotPurgedEvenWithHistory() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("survivor", failCount = 0))

        // прошёл проверку в этом раунде — в списке на удаление не попадает
        val purged = repo.purgeFailedNow(emptyList())

        assertEquals(0, purged)
        assertTrue(dao.getById("survivor") != null)
    }

    @Test
    fun activeFlagSwitchesToTheNewBest() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("old-best", isActive = true))
        dao.insert(proxy("new-best"))

        repo.setActive("new-best")

        assertFalse(dao.getById("old-best")!!.isActive)
        assertTrue(dao.getById("new-best")!!.isActive)
        assertEquals("new-best", dao.getActive()!!.id)
    }

    @Test
    fun successResetsFailStreak() = runTest {
        val dao = FakeMtProtoProxyDao()
        val repo = MtProxyRepository(dao)
        dao.insert(proxy("recovered", failCount = 1))

        repo.markSuccess("recovered")
        repo.markFailed("recovered")

        // после успеха счётчик сброшен: единственный новый промах не должен удалять прокси
        val purged = repo.purgeFailedNow(listOf("recovered"))
        assertEquals(0, purged)
        assertTrue(dao.getById("recovered") != null)
    }
}
