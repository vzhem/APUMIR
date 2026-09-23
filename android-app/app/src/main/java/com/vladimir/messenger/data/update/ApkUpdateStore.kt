package com.vladimir.messenger.data.update

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Раздача обновления (APK) роем на диске (docs/UPDATE_SEEDING.md).
 *
 * Три маленьких файла в `noBackupFilesDir/apk_seed/v1`: моя помеченная
 * раздача ([SeedInfo]), объявления соседей ([Offer]) и моя просьба о
 * новой версии ([Pending]). Формат — строка на запись, поля через `|`,
 * имя файла в base64url; первая строка — заголовок. Файлы пишутся в
 * целом во временный файл и переименовываются атомарно; испорченная
 * строка пропускается, не роняя остальные. Никакого Android: JVM-тест
 * читает и пишет напрямую (как `GroupFileRequestStore`).
 */
class ApkUpdateStore(private val root: File) {

    /**
     * Моя помеченная раздача. [autoReseed] — «раздавать дальше» включено:
     * после установки этой версии телефон сам станет сидом (строкой-
     * источником будет его `INCOMING/COMPLETE`, перешифровки нет).
     */
    data class SeedInfo(
        val version: String,
        val sha256: String,
        val sizeBytes: Long,
        val name: String,
        val atMs: Long,
        val autoReseed: Boolean,
    )

    /** Объявление соседа: он раздаёт версию [version] (файл [sha256]). */
    data class Offer(
        val nodeId: String,
        val version: String,
        val sha256: String,
        val sizeBytes: Long,
        val atMs: Long,
    )

    /** Моя просьба о новой версии: у кого спрашивали, сколько раз, кто ещё сид. */
    data class Pending(
        val version: String,
        val sha256: String,
        val startedAtMs: Long,
        val attempts: Int,
        val askedSeed: String,
        val askedAtMs: Long,
        val seeds: List<String>,
    )

    /**
     * Скачанный файл обновления, уже взятый в раздел «Обновления»
     * (автоматически после DownloadManager). Помним путь и размер, чтобы
     * при следующем старте не пересчитывать sha256 большого файла зря.
     */
    data class Adopted(
        val path: String,
        val name: String,
        val sha256: String,
        val sizeBytes: Long,
        val atMs: Long,
    )

    /**
     * Раунд 133: моя раздача ДИФФ-ПАТЧА обновления (от [fromVersion] до
     * [toVersion]). [transferId] — строка общей копии в `apkseed` (пусто,
     * если копия ещё не построена — достроит помпа).
     */
    data class PatchSeed(
        val fromVersion: String,
        val toVersion: String,
        val patchSha256: String,
        val apkSha256: String,
        val sizeBytes: Long,
        val name: String,
        val atMs: Long,
        val transferId: String,
    )

    /** Объявление соседа: он раздаёт патч от [fromVersion] до [toVersion]. */
    data class PatchOffer(
        val nodeId: String,
        val fromVersion: String,
        val toVersion: String,
        val patchSha256: String,
        val apkSha256: String,
        val sizeBytes: Long,
        val atMs: Long,
    )

    /** Моя просьба о патче: у кого спрашивали, сколько раз. */
    data class PatchPending(
        val fromVersion: String,
        val toVersion: String,
        val patchSha256: String,
        val apkSha256: String,
        val startedAtMs: Long,
        val attempts: Int,
        val askedSeed: String,
        val askedAtMs: Long,
        val seeds: List<String>,
    )

    // ── Раздача ─────────────────────────────────────────────────────────────

    @Synchronized
    fun loadSeed(): SeedInfo? {
        val file = File(root, ApkUpdate.SEED_FILE)
        if (!file.isFile) return null
        val line = runCatching { file.readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
            ?: return null
        // Одна строка целиком: заголовок первым полем.
        if (!line.trimStart().startsWith("$SEED_HEADER|")) return null
        return parseSeed(line)
    }

    @Synchronized
    fun saveSeed(seed: SeedInfo?) {
        val file = File(root, ApkUpdate.SEED_FILE)
        if (seed == null) {
            file.delete()
            return
        }
        writeAll(file, formatSeed(seed))
    }

    // ── Объявления соседей ──────────────────────────────────────────────────

    @Synchronized
    fun loadOffers(): List<Offer> {
        val file = File(root, ApkUpdate.OFFERS_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != OFFERS_HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parseOffer(it) }
            .take(MAX_OFFERS).toList()
    }

    /** Переписать все объявления; пустой список — файл убирается. */
    @Synchronized
    fun saveOffers(offers: Collection<Offer>) {
        val file = File(root, ApkUpdate.OFFERS_FILE)
        if (offers.isEmpty()) {
            file.delete()
            return
        }
        val body = buildString {
            append(OFFERS_HEADER).append('\n')
            for (offer in offers.take(MAX_OFFERS)) append(formatOffer(offer)).append('\n')
        }
        writeAll(file, body)
    }

    // ── Просьба ─────────────────────────────────────────────────────────────

    @Synchronized
    fun loadPending(): Pending? {
        val file = File(root, ApkUpdate.REQUEST_FILE)
        if (!file.isFile) return null
        val line = runCatching { file.readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
            ?: return null
        // Одна строка целиком: заголовок первым полем.
        if (!line.trimStart().startsWith("$REQUEST_HEADER|")) return null
        return parsePending(line)
    }

    @Synchronized
    fun savePending(pending: Pending?) {
        val file = File(root, ApkUpdate.REQUEST_FILE)
        if (pending == null) {
            file.delete()
            return
        }
        writeAll(file, formatPending(pending))
    }

    // ── Уже взятые в раздел скачанные файлы ─────────────────────────────────
    // ── Раздача патча (раунд 133) ───────────────────────────────────────────

    @Synchronized
    fun loadPatchSeed(): PatchSeed? {
        val file = File(root, ApkUpdate.PATCH_SEED_FILE)
        if (!file.isFile) return null
        val line = runCatching { file.readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
            ?: return null
        if (!line.trimStart().startsWith("$PATCH_SEED_HEADER|")) return null
        return parsePatchSeed(line)
    }

    @Synchronized
    fun savePatchSeed(seed: PatchSeed?) {
        val file = File(root, ApkUpdate.PATCH_SEED_FILE)
        if (seed == null) {
            file.delete()
            return
        }
        writeAll(file, formatPatchSeed(seed))
    }

    @Synchronized
    fun loadPatchOffers(): List<PatchOffer> {
        val file = File(root, ApkUpdate.PATCH_OFFERS_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != PATCH_OFFERS_HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parsePatchOffer(it) }
            .take(MAX_OFFERS).toList()
    }

    /** Переписать объявления патчей; пустой список — файл убирается. */
    @Synchronized
    fun savePatchOffers(offers: Collection<PatchOffer>) {
        val file = File(root, ApkUpdate.PATCH_OFFERS_FILE)
        if (offers.isEmpty()) {
            file.delete()
            return
        }
        val body = buildString {
            append(PATCH_OFFERS_HEADER).append('\n')
            for (offer in offers.take(MAX_OFFERS)) append(formatPatchOffer(offer)).append('\n')
        }
        writeAll(file, body)
    }

    @Synchronized
    fun loadPatchPending(): PatchPending? {
        val file = File(root, ApkUpdate.PATCH_REQUEST_FILE)
        if (!file.isFile) return null
        val line = runCatching { file.readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
            ?: return null
        if (!line.trimStart().startsWith("$PATCH_REQUEST_HEADER|")) return null
        return parsePatchPending(line)
    }

    @Synchronized
    fun savePatchPending(pending: PatchPending?) {
        val file = File(root, ApkUpdate.PATCH_REQUEST_FILE)
        if (pending == null) {
            file.delete()
            return
        }
        writeAll(file, formatPatchPending(pending))
    }


    @Synchronized
    fun loadAdopted(): List<Adopted> {
        val file = File(root, ADOPTED_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != ADOPTED_HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parseAdopted(it) }.take(MAX_ADOPTED).toList()
    }

    /** Запомнить взятый файл; новые записи в конце, старые вытесняются. */
    @Synchronized
    fun addAdopted(entry: Adopted) {
        val next = (loadAdopted().filterNot { it.path == entry.path } + entry).takeLast(MAX_ADOPTED)
        val body = buildString {
            append(ADOPTED_HEADER).append('\n')
            for (adopted in next) append(formatAdopted(adopted)).append('\n')
        }
        writeAll(File(root, ADOPTED_FILE), body)
    }

    // ── Формат ──────────────────────────────────────────────────────────────

    // ── Флаг «автораздачу выключил человек» ──────────────────────────────────

    /**
     * «Остановить раздачу» запоминается навсегда: самосев (ensureSelfSeeding)
     * не воскресит раздачу, пока человек сам её не включит (пометив файл).
     */
    @Synchronized
    fun loadAutoSeedOptOut(): Boolean {
        val file = File(root, AUTOSEED_FILE)
        if (!file.isFile) return false
        val line = runCatching { file.readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
            ?: return false
        return line.trimStart().startsWith("$AUTOSEED_HEADER|")
    }

    @Synchronized
    fun saveAutoSeedOptOut(disabled: Boolean) {
        val file = File(root, AUTOSEED_FILE)
        if (disabled) writeAll(file, "$AUTOSEED_HEADER|off") else file.delete()
    }

    private fun writeAll(file: File, body: String) {
        val parent = file.absoluteFile.parentFile
        if (parent != null) check(parent.mkdirs() || parent.isDirectory) { "Cannot create update store directory" }
        val temporary = File(parent, file.name + ".tmp")
        try {
            temporary.writeText(body, StandardCharsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun formatSeed(seed: SeedInfo): String =
        SEED_HEADER + "|" + seed.version + "|" + seed.sha256 + "|" + seed.sizeBytes + "|" +
            encode(seed.name) + "|" + seed.atMs + "|" + if (seed.autoReseed) 1 else 0

    private fun parseSeed(line: String): SeedInfo? {
        val parts = line.split('|')
        if (parts.size != 7) return null
        val version = parts[1]
        val sha = parts[2]
        val size = parts[3].toLongOrNull() ?: return null
        val name = decode(parts[4]) ?: return null
        val at = parts[5].toLongOrNull() ?: return null
        val auto = parts[6] == "1"
        if (ApkUpdate.parseVersion(version) == null || !GroupWireSha.isSha256(sha) ||
            size < 0L || at < 0L || name.isBlank()
        ) {
            return null
        }
        return SeedInfo(version, sha, size, name, at, auto)
    }

    private fun formatOffer(offer: Offer): String =
        offer.nodeId + "|" + offer.version + "|" + offer.sha256 + "|" + offer.sizeBytes + "|" + offer.atMs

    private fun parseOffer(line: String): Offer? {
        val parts = line.split('|')
        if (parts.size != 5) return null
        val nodeId = parts[0]
        val version = parts[1]
        val sha = parts[2]
        val size = parts[3].toLongOrNull() ?: return null
        val at = parts[4].toLongOrNull() ?: return null
        if (nodeId.isBlank() || !nodeId.startsWith("pk_") ||
            ApkUpdate.parseVersion(version) == null || !GroupWireSha.isSha256(sha) ||
            size < 0L || at < 0L
        ) {
            return null
        }
        return Offer(nodeId, version, sha, size, at)
    }

    private fun formatPending(pending: Pending): String =
        REQUEST_HEADER + "|" + pending.version + "|" + pending.sha256 + "|" + pending.startedAtMs + "|" +
            pending.attempts + "|" + pending.askedSeed + "|" + pending.askedAtMs + "|" +
            pending.seeds.joinToString(";") { encode(it) }

    private fun parsePending(line: String): Pending? {
        val parts = line.split('|')
        if (parts.size != 8) return null
        val version = parts[1]
        val sha = parts[2]
        val started = parts[3].toLongOrNull() ?: return null
        val attempts = parts[4].toIntOrNull() ?: return null
        val askedAt = parts[6].toLongOrNull() ?: return null
        val seeds = parts[7].split(';').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        if (ApkUpdate.parseVersion(version) == null || !GroupWireSha.isSha256(sha) ||
            started < 0L || attempts < 0 || askedAt < 0L
        ) {
            return null
        }
        return Pending(version, sha, started, attempts, parts[5], askedAt, seeds)
    }

    private fun formatAdopted(adopted: Adopted): String =
        adopted.path.replace('\n', ' ') + "|" + encode(adopted.name) + "|" +
            adopted.sha256 + "|" + adopted.sizeBytes + "|" + adopted.atMs

    private fun parseAdopted(line: String): Adopted? {
        val parts = line.split('|')
        if (parts.size != 5) return null
        val path = parts[0]
        val name = decode(parts[1]) ?: return null
        val sha = parts[2]
        val size = parts[3].toLongOrNull() ?: return null
        val at = parts[4].toLongOrNull() ?: return null
        if (path.isBlank() || path.length > 1024 || name.isBlank() ||
            !GroupWireSha.isSha256(sha) || size < 0L || at < 0L
        ) {
            return null
        }
        return Adopted(path, name, sha, size, at)
    }

    private fun formatPatchSeed(seed: PatchSeed): String =
        PATCH_SEED_HEADER + "|" + seed.fromVersion + "|" + seed.toVersion + "|" + seed.patchSha256 + "|" +
            seed.apkSha256 + "|" + seed.sizeBytes + "|" + encode(seed.name) + "|" + seed.atMs + "|" +
            encode(seed.transferId)

    private fun parsePatchSeed(line: String): PatchSeed? {
        val parts = line.split('|')
        if (parts.size != 9) return null
        val from = parts[1]
        val to = parts[2]
        val patchSha = parts[3]
        val apkSha = parts[4]
        val size = parts[5].toLongOrNull() ?: return null
        val name = decode(parts[6]) ?: return null
        val at = parts[7].toLongOrNull() ?: return null
        val transferId = decode(parts[8]) ?: return null
        if (ApkUpdate.parseVersion(from) == null || ApkUpdate.parseVersion(to) == null ||
            !GroupWireSha.isSha256(patchSha) || !GroupWireSha.isSha256(apkSha) ||
            size < 0L || at < 0L || name.isBlank()
        ) {
            return null
        }
        return PatchSeed(from, to, patchSha, apkSha, size, name, at, transferId)
    }

    private fun formatPatchOffer(offer: PatchOffer): String =
        offer.nodeId + "|" + offer.fromVersion + "|" + offer.toVersion + "|" + offer.patchSha256 + "|" +
            offer.apkSha256 + "|" + offer.sizeBytes + "|" + offer.atMs

    private fun parsePatchOffer(line: String): PatchOffer? {
        val parts = line.split('|')
        if (parts.size != 7) return null
        val nodeId = parts[0]
        val from = parts[1]
        val to = parts[2]
        val patchSha = parts[3]
        val apkSha = parts[4]
        val size = parts[5].toLongOrNull() ?: return null
        val at = parts[6].toLongOrNull() ?: return null
        if (nodeId.isBlank() || !nodeId.startsWith("pk_") ||
            ApkUpdate.parseVersion(from) == null || ApkUpdate.parseVersion(to) == null ||
            !GroupWireSha.isSha256(patchSha) || !GroupWireSha.isSha256(apkSha) ||
            size < 0L || at < 0L
        ) {
            return null
        }
        return PatchOffer(nodeId, from, to, patchSha, apkSha, size, at)
    }

    private fun formatPatchPending(pending: PatchPending): String =
        PATCH_REQUEST_HEADER + "|" + pending.fromVersion + "|" + pending.toVersion + "|" +
            pending.patchSha256 + "|" + pending.apkSha256 + "|" + pending.startedAtMs + "|" +
            pending.attempts + "|" + pending.askedSeed + "|" + pending.askedAtMs + "|" +
            pending.seeds.joinToString(";") { encode(it) }

    private fun parsePatchPending(line: String): PatchPending? {
        val parts = line.split('|')
        if (parts.size != 10) return null
        val from = parts[1]
        val to = parts[2]
        val patchSha = parts[3]
        val apkSha = parts[4]
        val started = parts[5].toLongOrNull() ?: return null
        val attempts = parts[6].toIntOrNull() ?: return null
        val askedAt = parts[8].toLongOrNull() ?: return null
        val seeds = parts[9].split(';').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        if (ApkUpdate.parseVersion(from) == null || ApkUpdate.parseVersion(to) == null ||
            !GroupWireSha.isSha256(patchSha) || !GroupWireSha.isSha256(apkSha) ||
            started < 0L || attempts < 0 || askedAt < 0L
        ) {
            return null
        }
        return PatchPending(from, to, patchSha, apkSha, started, attempts, parts[7], askedAt, seeds)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val SEED_HEADER = "APUSEED1"
        const val OFFERS_HEADER = "APUOFFER1"
        const val REQUEST_HEADER = "APUREQ1"
        const val ADOPTED_FILE = "adopted.v1"
        const val ADOPTED_HEADER = "APUADOPT1"
        const val AUTOSEED_FILE = "autoseed.v1"
        const val AUTOSEED_HEADER = "APUAUTOSEED1"
        const val AUTOSEED_FILE = "autoseed.v1"
        const val AUTOSEED_HEADER = "APUAUTOSEED1"
        // Раунд 133: дифф-патч обновления.
        const val PATCH_SEED_HEADER = "APUPSEED1"
        const val PATCH_OFFERS_HEADER = "APUPOFFER1"
        const val PATCH_REQUEST_HEADER = "APUPREQ1"
        const val MAX_OFFERS = 64
        const val MAX_ADOPTED = 32
    }
}

/** Хэш: 64 знака шестнадцатеричного в нижнем регистре (без зависимостей). */
private object GroupWireSha {
    fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
