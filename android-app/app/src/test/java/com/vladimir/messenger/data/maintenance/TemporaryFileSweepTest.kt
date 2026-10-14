package com.vladimir.messenger.data.maintenance

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TemporaryFileSweepTest {
    private val week = 7L * 24 * 60 * 60 * 1000
    @Test fun oldCopiesAreRemovedButFreshAndFutureFilesRemain() {
        val root = Files.createTempDirectory("apu-temp-sweep").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = root.resolve("old-share.jpg").apply { writeBytes(ByteArray(64)); setLastModified(now - week - 1000) }
            val fresh = root.resolve("active-camera.jpg").apply { writeText("active") }
            val future = root.resolve("future.jpg").apply { writeText("future"); setLastModified(now + week) }
            val result = TemporaryFileSweep.sweep(root, now)
            assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(future.exists())
            assertEquals(1, result.deleted); assertEquals(64L, result.freedBytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun encryptedRootsLoseOnlyStaleAtomicTempsNeverChunksOrKeys() {
        val root = Files.createTempDirectory("apu-atomic-sweep").toFile()
        try {
            val now = System.currentTimeMillis()
            val dir = root.resolve("transfer/chunks").apply { mkdirs() }
            val files = listOf(".00000000.chunk.uuid.tmp", "00000000.chunk", "key-envelope.v1", "manifest.v1", "identity.bak")
                .map { dir.resolve(it).apply { writeText("keep unless atomic temp"); setLastModified(now - week - 1000) } }
            assertEquals(1, TemporaryFileSweep.sweep(root, now, onlyAtomicTemps = true).deleted)
            assertFalse(files.first().exists()); files.drop(1).forEach { assertTrue(it.exists()) }
        } finally { root.deleteRecursively() }
    }
    @Test fun symlinkDoesNotLeadOutsideTheTemporaryRootAndPassIsBounded() {
        val root = Files.createTempDirectory("apu-sweep-root").toFile()
        val outside = Files.createTempDirectory("apu-sweep-outside").toFile()
        try {
            val now = System.currentTimeMillis()
            val document = outside.resolve("user-document.txt").apply { writeText("original"); setLastModified(now - week - 1000) }
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            for (i in 0..200) root.resolve("copy-$i").apply { writeText("temporary"); setLastModified(now - week - 1000) }
            val result = TemporaryFileSweep.sweep(root, now)
            assertTrue(document.exists()); assertTrue(result.deleted <= 128); assertTrue(result.examined <= 4096)
            Files.delete(root.resolve("link").toPath())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun topLevelImportSweepNeverDescendsIntoReferencedVoiceHistory() {
        val root = Files.createTempDirectory("apu-import-sweep").toFile()
        try {
            val now = System.currentTimeMillis()
            val temp = root.resolve("apu_pick_old.apk").apply { writeText("temporary"); setLastModified(now - week - 1000) }
            val original = root.resolve("personal-document.txt").apply { writeText("original"); setLastModified(now - week - 1000) }
            val voice = root.resolve("voice").apply { mkdirs() }.resolve("apu_pick_do-not-touch.apk").apply { writeText("referenced"); setLastModified(now - week - 1000) }
            TemporaryFileSweep.sweep(root, now, depthLimit = 0, acceptName = { it.startsWith("apu_pick_") && it.endsWith(".apk") })
            assertFalse(temp.exists()); assertTrue(original.exists()); assertTrue(voice.exists())
        } finally { root.deleteRecursively() }
    }

}
