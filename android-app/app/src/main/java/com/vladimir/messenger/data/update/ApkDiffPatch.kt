package com.vladimir.messenger.data.update

// =============================================================================
// APKDIFFPATCH.KT - компактное обновление (раунд 132)
// =============================================================================
// Задача владельца: «при скачивании обновление должно качаться минимального
// размера». Релиз публикуется с патчем (tools/ci/make_apk_patch.py, формат
// APUBSP1): новый APK собирается из одинаковых 4 КБ блоков установленного
// файла + сжатых новых блоков. Сборка байт-в-байт равна релизному APK
// (UpdateChecker сверяет sha256), поэтому подпись верна и установка проходит.
// =============================================================================

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

object ApkDiffPatch {

    private const val MAGIC = "APUBSP1"

    /** Применить патч к [oldFile] (установленный APK), результат в [newFile]. */
    fun apply(oldFile: File, patchFile: File, newFile: File): Boolean {
        return try {
            val patch = patchFile.readBytes()
            if (!startsWithMagic(patch)) return false
            val blockBits = patch[7].toInt() and 0xFF
            if (blockBits !in 9..16) return false
            val blockSize = 1 shl blockBits
            val newSize = readU64(patch, 8)
            val oldCount = readU32(patch, 16).toInt()
            val newCount = readU32(patch, 20).toInt()
            if (newSize <= 0L || oldCount <= 0 || newCount <= 0) return false

            // Дайджесты блоков старого файла: 16 байт sha256 на блок.
            var pos = 24
            val digestEnd = pos + 16 * oldCount
            if (digestEnd > patch.size) return false
            val oldDigestToIndex = HashMap<String, Long>(oldCount * 2)
            for (i in 0 until oldCount) {
                val hex = hexOf(patch, pos)
                if (!oldDigestToIndex.containsKey(hex)) oldDigestToIndex[hex] = i.toLong()
                pos += 16
            }

            // Дескрипторы новых блоков: kind(1) + value(4).
            data class Desc(val kind: Int, val value: Int)
            val descriptors = ArrayList<Desc>(newCount)
            for (i in 0 until newCount) {
                if (pos + 5 > patch.size) return false
                val kind = patch[pos].toInt() and 0xFF
                val value = readU32(patch, pos + 1).toInt()
                if (kind != 1 && kind != 2) return false
                descriptors.add(Desc(kind, value))
                pos += 5
            }
            val rawStart = pos

            // Карта дайджест -> смещение в установленном APK.
            val oldSize = oldFile.length()
            val oldRandom = RandomAccessFile(oldFile, "r")
            try {
                val block = ByteArray(blockSize)
                var oldIndex = 0L
                while (oldIndex * blockSize < oldSize) {
                    val offset = oldIndex * blockSize
                    oldRandom.seek(offset)
                    val len = oldRandom.read(
                        block, 0,
                        minOf(blockSize.toLong(), oldSize - offset).toInt(),
                    )
                    if (len <= 0) break
                    val digest = MessageDigest.getInstance("SHA-256").digest(block.copyOf(len))
                    val hex = hexOf(digest, 0)
                    if (!oldDigestToIndex.containsKey(hex)) {
                        oldDigestToIndex[hex] = oldIndex
                    }
                    oldIndex++
                }

                // Собрать новый файл.
                newFile.parentFile?.mkdirs()
                if (newFile.exists()) newFile.delete()
                val out = newFile.outputStream().buffered(1 shl 16)
                var rawCursor = rawStart
                val inflater = Inflater(true)
                try {
                    for ((index, desc) in descriptors.withIndex()) {
                        val expected = minOf(
                            blockSize.toLong(),
                            newSize - index.toLong() * blockSize,
                        ).toInt()
                        if (expected <= 0) return false
                        when (desc.kind) {
                            1 -> {
                                val srcOffset = (oldDigestToIndex[hexOf(patch, 24 + 16 * desc.value)]
                                    ?: return false) * blockSize
                                oldRandom.seek(srcOffset)
                                var done = 0
                                while (done < expected) {
                                    val read = oldRandom.read(block, 0, expected - done)
                                    if (read <= 0) return false
                                    out.write(block, 0, read)
                                    done += read
                                }
                            }
                            else -> {
                                if (rawCursor + desc.value > patch.size) return false
                                inflater.setInput(patch, rawCursor, desc.value)
                                val outBuf = ByteArray(blockSize)
                                var done = 0
                                while (done < expected) {
                                    val inflated = inflater.inflate(outBuf, done, expected - done)
                                    if (inflater.needsInput() || inflated <= 0) return false
                                    done += inflated
                                }
                                if (!inflater.finished()) return false
                                out.write(outBuf, 0, expected)
                                rawCursor += desc.value
                            }
                        }
                    }
                } finally {
                    inflater.end()
                    out.flush()
                    out.close()
                }
                newFile.length() == newSize
            } finally {
                oldRandom.close()
            }
        } catch (e: Exception) {
            android.util.Log.w("ApkDiffPatch", "apply failed: ${e.message}")
            runCatching { newFile.delete() }
            false
        }
    }

    private fun startsWithMagic(patch: ByteArray): Boolean =
        patch.size >= 7 && String(patch, 0, 7, Charsets.US_ASCII) == MAGIC

    private fun readU64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun hexOf(data: ByteArray, offset: Int): String {
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            sb.append("%02x".format(data[offset + i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** SHA-256 файла (нижний регистр) - сверка с релизом. */
    fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
