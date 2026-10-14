package com.vladimir.messenger.data.maintenance

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.ArrayDeque

/** Bounded leaf-only sweep. Caller supplies an allowlisted app-owned temporary root. */
internal object TemporaryFileSweep {
    data class Result(val examined: Int, val deleted: Int, val freedBytes: Long, val failed: Int)
    fun sweep(root: File, nowMs: Long, onlyAtomicTemps: Boolean = false,
              ageMs: Long = 7L * 24 * 60 * 60 * 1000,
              depthLimit: Int = 3, acceptName: (String) -> Boolean = { true }): Result {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return Result(0, 0, 0, 0)
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        val started = System.nanoTime()
        var examined = 0; var deleted = 0; var freed = 0L; var failed = 0
        while (queue.isNotEmpty() && examined < 4096 && deleted < 128 && System.nanoTime() - started < 200_000_000L) {
            val (directory, depth) = queue.removeFirst()
            try {
                Files.newDirectoryStream(directory.toPath()).use { entries ->
                    for (path in entries) {
                        if (examined >= 4096 || deleted >= 128 || System.nanoTime() - started >= 200_000_000L) break
                        examined++
                        if (Files.isSymbolicLink(path)) continue
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            if (depth < depthLimit) queue.add(path.toFile() to depth + 1)
                            continue
                        }
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                        val file = path.toFile()
                        if (!acceptName(file.name)) continue
                        if (onlyAtomicTemps && !(file.name.startsWith(".") && file.name.endsWith(".tmp"))) continue
                        val modified = file.lastModified()
                        if (modified <= 0 || modified > nowMs || nowMs - modified < ageMs) continue
                        val bytes = file.length()
                        if (file.delete()) { deleted++; freed += bytes } else failed++
                    }
                }
            } catch (_: Exception) { failed++ }
        }
        return Result(examined, deleted, freed, failed)
    }
}
