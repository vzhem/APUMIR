package com.vladimir.messenger.data.group

import com.vladimir.messenger.util.GroupFileMarker
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Просьбы о файлах группы на диске (рой, этап 10): запись, чтение, испорченные строки. */
class GroupFileRequestStoreTest {
    private lateinit var dir: File
    private lateinit var store: GroupFileRequestStore

    private val sha = "ab".repeat(32)
    private val info = GroupFileMarker.Info(sha, 5_000_000L, "application/pdf", "Отчёт за квартал | v2.pdf")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("apu-file-requests-").toFile()
        store = GroupFileRequestStore(File(dir, "nested/requests.v1"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(
        groupId: String = "group-7",
        attempts: Int = 2,
        seeds: List<String> = listOf("pk_" + "11".repeat(16), "pk_" + "22".repeat(16)),
    ) = GroupFileRequestStore.Record(
        groupId = groupId,
        info = info,
        messageId = "msg-1|with|bars",
        startedAtMs = 1_700_000_000_000L,
        manual = true,
        attempts = attempts,
        askedSeed = seeds.firstOrNull().orEmpty(),
        askedAtMs = 1_700_000_090_000L,
        seeds = seeds,
    )

    @Test
    fun roundTripKeepsEveryField() {
        val records = listOf(record(), record(groupId = "группа с пробелами", attempts = 0, seeds = emptyList()))
        store.save(records)
        assertEquals(records, store.load())
    }

    @Test
    fun emptySaveRemovesFile() {
        store.save(listOf(record()))
        assertTrue(File(dir, "nested/requests.v1").isFile)
        store.save(emptyList())
        assertFalse(File(dir, "nested/requests.v1").exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() {
        store.save(listOf(record()))
        val file = File(dir, "nested/requests.v1")
        val good = file.readLines().last()
        file.writeText(
            GroupFileRequestStore.HEADER + "\n" +
                "мусор\n" +
                good.replace(sha, "zz".repeat(32)) + "\n" + // плохой хэш
                good.replaceFirst("|1|", "|x|") + "\n" + // плохой флаг
                good + "\n",
        )
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(record(), loaded.single())
    }

    @Test
    fun wrongHeaderMeansNothing() {
        val file = File(dir, "nested/requests.v1")
        file.parentFile.mkdirs()
        file.writeText("other-format\n" + store.format(record()) + "\n")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun parseRejectsWrongCellCount() {
        assertNull(store.parse("a|b|c"))
        assertNull(store.parse(store.format(record()) + "|extra"))
    }

    @Test
    fun seedsWithDelimitersAreDropped() {
        val saved = record(seeds = listOf("pk_ok", "bad,comma", "bad|bar"))
        store.save(listOf(saved))
        assertEquals(listOf("pk_ok"), store.load().single().seeds)
    }
}
