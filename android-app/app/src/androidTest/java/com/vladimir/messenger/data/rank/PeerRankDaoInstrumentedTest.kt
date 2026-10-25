package com.vladimir.messenger.data.rank

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for the persisted, monotonic peer-rank entitlement. */
@RunWith(AndroidJUnit4::class)
class PeerRankDaoInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun vipSurvivesNewerLowerRankPacket() = runBlocking {
        val nodeId = "pk_" + "ab".repeat(16)
        val store = PeerRankStore(database.peerRankDao())

        assertTrue(store.remember(nodeId, qualified = 10, updatedAtMs = 1_000))
        assertTrue(store.remember(nodeId, qualified = 9, updatedAtMs = 2_000))

        assertEquals(10, store.qualifiedFor(nodeId))
        assertTrue(store.isVip(nodeId))
        assertEquals(setOf(nodeId), store.vipNodeIds.first())
    }
}
