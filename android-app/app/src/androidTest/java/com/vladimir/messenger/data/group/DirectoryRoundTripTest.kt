package com.vladimir.messenger.data.group

// =============================================================================
// DIRECTORYROUNDTRIPTEST.KT
// =============================================================================
// Реестр групп: конверт dir собирается и разбирается без потерь, записи
// переживают цикл Room-базы (вставка -> чтение по slug/owner).
// =============================================================================

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryRoundTripTest {

    @Test
    fun envelopeRoundTrip() {
        val envelope = GroupWire.buildDirectory(
            groupId = "grp_1",
            title = "Семья | дом",
            about = "описание\nс переносом",
            ownerId = "owner_pk",
            slug = "Abcdefghijkmnopq",
            isChannel = true,
            needsApproval = false,
            hops = 1,
        )
        val parsed = GroupWire.parse(envelope)
        assertTrue(parsed is GroupWire.Packet.Directory)
        val dir = parsed as GroupWire.Packet.Directory
        assertEquals("grp_1", dir.groupId)
        assertEquals("Семья | дом", dir.title)
        assertEquals("описание\nс переносом", dir.about)
        assertEquals("owner_pk", dir.ownerId)
        assertEquals("Abcdefghijkmnopq", dir.slug)
        assertTrue(dir.isChannel)
        assertEquals(false, dir.needsApproval)
        assertEquals(1, dir.hops)
    }

    @Test
    fun entityRoundTripThroughRoom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.directoryDao()
            val row = DirectoryEntity(
                groupId = "g2",
                title = "Канал",
                about = "",
                ownerId = "o2",
                slug = "Zz99abcdefghijkm",
                isChannel = true,
                needsApproval = true,
                updatedAtMs = 7L,
            )
            kotlinx.coroutines.runBlocking { dao.upsert(row) }
            val bySlug = kotlinx.coroutines.runBlocking { dao.bySlug("Zz99abcdefghijkm") }
            assertNotNull(bySlug)
            assertEquals("Канал", bySlug!!.title)
            val byOwner = kotlinx.coroutines.runBlocking { dao.byOwner("o2") }
            assertEquals(1, byOwner.size)
            // Обновление той же группы не плодит строки.
            kotlinx.coroutines.runBlocking { dao.upsert(row.copy(title = "Канал 2", updatedAtMs = 8L)) }
            val all = kotlinx.coroutines.runBlocking { dao.all().first() }
            assertEquals(1, all.size)
            assertEquals("Канал 2", all.first().title)
        } finally {
            db.close()
        }
    }

    @Test
    fun shortEnvelopeRejected() {
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|${GroupWire.KIND_DIRECTORY}|g|t|a|o|s|1|0"))
    }
}
