package com.vladimir.messenger.data.update

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Temporary APK copy handed to the system installer.
 *
 * The filename carries the target version so cleanup can distinguish a pending
 * newer install from an already-installed handoff. Legacy `install-<sha>.apk`
 * names are kept readable for one grace period after an upgrade.
 */
object UpdateInstallHandoff {
    private const val TAG = "UpdateInstallHandoff"
    private const val GRACE_MS = 24L * 60 * 60 * 1000
    private val HANDOFF = Regex("^install-(?:(\\d{1,4}(?:\\.\\d{1,4}){1,3})-)?[0-9a-f]{16}\\.apk$")
    private val PUBLIC_NAME = Regex("^APU-v(\\d{1,4}(?:\\.\\d{1,4}){1,3})\\.apk$")

    data class Result(val deleted: Int, val freedBytes: Long, val failed: Int)

    fun target(context: Context, sha256: String, version: String): File {
        val safeVersion = ApkUpdate.normalize(version)
        require(ApkUpdate.parseVersion(safeVersion) != null) { "Invalid update version" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid update sha256" }
        return File(UpdateFileRoots.installHandoff(context), "install-$safeVersion-${sha256.take(16)}.apk")
    }

    fun copy(context: Context, source: File, sha256: String, version: String): File {
        val target = target(context, sha256, version)
        if (target.isFile && target.length() == source.length()) return target
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(temporary.length() == source.length()) { "Incomplete installer handoff copy" }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "cannot remove temporary installer handoff")
            }
        }
    }

    /** Pure retention decision, covered by JVM tests. */
    fun shouldDelete(name: String, installedVersion: String, modifiedMs: Long, nowMs: Long): Boolean {
        if (modifiedMs <= 0 || modifiedMs > nowMs) return false
        val age = nowMs - modifiedMs
        HANDOFF.matchEntire(name)?.let { match ->
            val version = match.groupValues[1]
            if (version.isEmpty()) return age >= GRACE_MS
            val comparison = ApkUpdate.compareVersions(version, installedVersion) ?: return age >= GRACE_MS
            return comparison <= 0 || age >= ApkUpdate.TRANSFER_TTL_MS
        }
        PUBLIC_NAME.matchEntire(name)?.let { match ->
            val comparison = ApkUpdate.compareVersions(match.groupValues[1], installedVersion) ?: return false
            return comparison <= 0 && age >= 10 * 60 * 1000
        }
        return name == "update.bspatch" && age >= GRACE_MS
    }

    fun cleanup(context: Context, installedVersion: String, nowMs: Long = System.currentTimeMillis()): Result {
        val root = File(context.applicationContext.cacheDir, "updates")
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return Result(0, 0, 0)
        var deleted = 0
        var freed = 0L
        var failed = 0
        val files = root.listFiles().orEmpty().sortedBy { it.lastModified() }
        for (file in files.take(256)) {
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue
            if (!shouldDelete(file.name, installedVersion, file.lastModified(), nowMs)) continue
            val bytes = file.length()
            if (file.delete()) {
                deleted++
                freed += bytes
            } else {
                failed++
            }
        }
        if (deleted > 0 || failed > 0) {
            Log.i(TAG, "installer handoff cleanup: deleted=$deleted freed_bytes=$freed failed=$failed")
        }
        return Result(deleted, freed, failed)
    }
}
