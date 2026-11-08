package com.vladimir.messenger.data.rank

import com.vladimir.messenger.data.local.dao.PeerRankDao
import com.vladimir.messenger.data.local.entity.PeerRankEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRankStoreTest {
    /** Simulate a lower-rank insert by another coroutine between UPDATE and INSERT. */
    private class RacingDao : PeerRankDao {
        var row: PeerRankEntity? = null
        override fun observeVipNodeIds(vipThreshold: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun qualifiedFor(nodeId: String): Int? = row?.qualified
        override suspend fun updateIfNewer(nodeId: String, qualified: Int, updatedAtMs: Long): Int {
            val current = row ?: return 0
            if (current.updatedAtMs >= updatedAtMs && current.qualified >= qualified) return 0
            row = current.copy(qualified = maxOf(current.qualified, qualified), updatedAtMs = maxOf(current.updatedAtMs, updatedAtMs))
            return 1
        }
        override suspend fun insertIfAbsent(row: PeerRankEntity): Long {
            if (this.row == null) this.row = row.copy(qualified = 2)
            return -1
        }
    }

    @Test fun `concurrent first insert cannot discard the VIP rank`() = runTest {
        val dao = RacingDao()
        val store = PeerRankStore(dao)
        assertTrue(store.remember("PK_ABCDEFG", 10, 100))
        assertEquals(10, dao.row?.qualified)
        store.remember("pk_abcdefg", 1, 200)
        assertEquals(10, dao.row?.qualified)
    }
}
