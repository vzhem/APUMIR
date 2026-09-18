package com.vladimir.messenger.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Раздача обновления (APK) роем: версии, проверка «это APK» и
 * хранилище на диске (docs/UPDATE_SEEDING.md).
 */
class ApkUpdateTest {

    // ── Версии ──────────────────────────────────────────────────────────────

    @Test
    fun versionCompareMatchesNumericExpectations() {
        assertTrue(ApkUpdate.isNewer("11.70.29", "11.70.28"))
        assertTrue(ApkUpdate.isNewer("11.16", "11.9"))
        assertFalse(ApkUpdate.isNewer("11.70.28", "11.70.29"))
        assertFalse(ApkUpdate.isNewer("11.70.29", "11.70.29"))
        assertTrue(ApkUpdate.isSame("11.70.29", "11.70.29"))
        assertFalse(ApkUpdate.isSame("11.70.29", "11.70.30"))
    }

    @Test
    fun versionParseRejectsNonNumeric() {
        assertEquals(listOf(11, 70, 29), ApkUpdate.parseVersion("11.70.29"))
        assertNull(ApkUpdate.parseVersion("v11.70"))
        assertNull(ApkUpdate.parseVersion("11.70.29-beta"))
        assertNull(ApkUpdate.parseVersion("11..70"))
        assertNull(ApkUpdate.parseVersion("11.70.29.1.2"))
        assertNull(ApkUpdate.parseVersion("12345"))
        assertNull(ApkUpdate.compareVersions("11.70", "latest"))
    }

    // ── «Это APK» ───────────────────────────────────────────────────────────

    private fun makeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun looksLikeApkChecksManifestAndDex() {
        val dir = Files.createTempDirectory("apu-apk-check-").toFile()
        try {
            val apk = File(dir, "good.apk")
            makeZip(apk, "AndroidManifest.xml" to "<manifest/>", "classes.dex" to "dex", "res/icon.png" to "x")
            assertTrue(ApkUpdate.looksLikeApk(apk))

            val multiDex = File(dir, "multi.apk")
            makeZip(multiDex, "AndroidManifest.xml" to "<manifest/>", "classes2.dex" to "dex")
            assertTrue(ApkUpdate.looksLikeApk(multiDex))

            val noDex = File(dir, "nodoc.apk")
            makeZip(noDex, "AndroidManifest.xml" to "<manifest/>")
            assertFalse(ApkUpdate.looksLikeApk(noDex))

            val noManifest = File(dir, "nomani.apk")
            makeZip(noManifest, "classes.dex" to "dex")
            assertFalse(ApkUpdate.looksLikeApk(noManifest))

            val notZip = File(dir, "text.txt")
            notZip.writeText("просто текст, не архив")
            assertFalse(ApkUpdate.looksLikeApk(notZip))

            val empty = File(dir, "empty.apk")
            empty.createNewFile()
            assertFalse(ApkUpdate.looksLikeApk(empty))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sha256OfFileMatchesKnownVector() {
        val dir = Files.createTempDirectory("apu-apk-sha-").toFile()
        try {
            val empty = File(dir, "empty.bin")
            empty.createNewFile()
            // SHA-256 пустой строки — эталонный вектор.
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ApkUpdate.sha256OfFile(empty),
            )
            // SHA-256("abc") — второй эталонный вектор.
            val abc = File(dir, "abc.bin")
            abc.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApkUpdate.sha256OfFile(abc),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun formatSizeHumanReadable() {
        assertEquals("512 Б", ApkUpdate.formatSize(512))
        assertEquals("1.0 КБ", ApkUpdate.formatSize(1024))
        assertEquals("37.0 МБ", ApkUpdate.formatSize(38_797_312L))
    }

    // ── Хранилище ───────────────────────────────────────────────────────────

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun seedRoundTripAndDelete() {
        val root = Files.createTempDirectory("apu-apk-seed-").toFile()
        try {
            val store = ApkUpdateStore(root)
            assertNull(store.loadSeed())

            val seed = ApkUpdateStore.SeedInfo("11.70.29", sha, 37_930_406L, "APU-v11.70.29.apk", 111L, true)
            store.saveSeed(seed)
            assertEquals(seed, store.loadSeed())

            store.saveSeed(null)
            assertNull(store.loadSeed())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offersRoundTripKeepsLatestPerNode() {
        val root = Files.createTempDirectory("apu-apk-offers-").toFile()
        try {
            val store = ApkUpdateStore(root)
            val first = ApkUpdateStore.Offer("pk_1111111111111111111111111111111111111111111111111111111111111111", "11.70.29", sha, 100L, 1L)
            val second = ApkUpdateStore.Offer("pk_2222222222222222222222222222222222222222222222222222222222222222", "11.70.29", sha, 100L, 2L)
            store.saveOffers(listOf(first, second))
            assertEquals(2, store.loadOffers().size)

            // Узел объявил новую версию: свежее объявление заменяет старое
            // (сливает вызывающий — хранилище хранит то, что дали).
            val newer = first.copy(version = "11.70.30", atMs = 3L)
            val merged = store.loadOffers().filterNot { it.nodeId == newer.nodeId } + newer
            store.saveOffers(merged)
            val loaded = store.loadOffers()
            assertEquals(2, loaded.size)
            assertEquals("11.70.30", loaded.first { it.nodeId == first.nodeId }.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingRoundTrip() {
        val root = Files.createTempDirectory("apu-apk-req-").toFile()
        try {
            val store = ApkUpdateStore(root)
            assertNull(store.loadPending())

            val pending = ApkUpdateStore.Pending(
                version = "11.70.29",
                sha256 = sha,
                startedAtMs = 1000L,
                attempts = 3,
                askedSeed = "pk_3333333333333333333333333333333333333333333333333333333333333333",
                askedAtMs = 2000L,
                seeds = listOf(
                    "pk_1111111111111111111111111111111111111111111111111111111111111111",
                    "pk_2222222222222222222222222222222222222222222222222222222222222222",
                ),
            )
            store.savePending(pending)
            assertEquals(pending, store.loadPending())

            store.savePending(null)
            assertNull(store.loadPending())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedStoreFileDoesNotThrow() {
        val root = Files.createTempDirectory("apu-apk-corrupt-").toFile()
        try {
            val store = ApkUpdateStore(root)
            File(root, ApkUpdate.SEED_FILE).writeText("APUSEED1|сломано\nи ещё мусор")
            assertNull(store.loadSeed())
            File(root, ApkUpdate.OFFERS_FILE).writeText("APUOFFER1\nне-узел|11.70.29|zz|1|1")
            assertTrue(store.loadOffers().isEmpty())
            File(root, ApkUpdate.REQUEST_FILE).writeText("APUREQ1\n11.70.29|zz")
            assertNull(store.loadPending())
        } finally {
            root.deleteRecursively()
        }
    }
}
