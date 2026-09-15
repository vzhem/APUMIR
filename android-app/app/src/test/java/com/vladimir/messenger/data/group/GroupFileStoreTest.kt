package com.vladimir.messenger.data.group

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupFileStoreTest {
    private lateinit var root: File
    private lateinit var store: GroupFileStore
    private val sha = "3c".repeat(32)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("apu-group-files-").toFile()
        store = GroupFileStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun putThenFindByGroupAndHash() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val stored = bytes.inputStream().use { store.put("grp-1", sha, "Отчёт май.pdf", it) }
        assertTrue(stored.isFile)
        assertEquals(5000L, stored.length())
        // Имя очищено (кириллица и пробел - подчёркивания), путь внутри
        // корня, временных файлов не осталось.
        assertEquals("_________.pdf", stored.name)
        assertTrue(stored.canonicalPath.startsWith(root.canonicalPath))
        assertNull(stored.parentFile!!.listFiles()!!.firstOrNull { it.name.startsWith('.') })
        val found = store.file("grp-1", sha)
        assertNotNull(found)
        assertTrue(found!!.readBytes().contentEquals(bytes))
        // В другой группе того же файла нет: копии считаются по паре группа + хэш.
        assertNull(store.file("grp-2", sha))
        assertEquals(5000L, store.totalBytes())
    }

    @Test
    fun secondPutOfSameFileIsNoop() {
        val bytes = ByteArray(10) { 1 }
        val first = bytes.inputStream().use { store.put("g", sha, "a.bin", it) }
        val second = ByteArray(10) { 2 }.inputStream().use { store.put("g", sha, "a.bin", it) }
        assertEquals(first, second)
        assertTrue(second.readBytes().contentEquals(bytes))
    }

    @Test
    fun deleteAndSweep() {
        ByteArray(3).inputStream().use { store.put("g", sha, "a.bin", it) }
        assertTrue(store.delete("g", sha))
        assertNull(store.file("g", sha))
        ByteArray(3).inputStream().use { store.put("g", sha, "a.bin", it) }
        // Свежая копия остаётся, старая уходит.
        assertEquals(0, store.sweep(System.currentTimeMillis()))
        assertNotNull(store.file("g", sha))
        assertEquals(1, store.sweep(System.currentTimeMillis() + GroupFileStore.DEFAULT_TTL_MS + 1))
        assertNull(store.file("g", sha))
        assertFalse(File(root, "g").exists())
    }

    @Test
    fun groupKeyIsFilesystemSafe() {
        assertEquals("abc-DEF_09", store.groupKey("abc-DEF_09"))
        val hashed = store.groupKey("группа/с пробелами|и|палками")
        assertTrue(hashed.startsWith("g_"))
        assertEquals(34, hashed.length)
        assertTrue(hashed.drop(2).all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hashed, store.groupKey("группа/с пробелами|и|палками"))
    }

    @Test
    fun rejectsBadHash() {
        try {
            store.file("g", "not-a-hash")
            org.junit.Assert.fail("bad hash accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
