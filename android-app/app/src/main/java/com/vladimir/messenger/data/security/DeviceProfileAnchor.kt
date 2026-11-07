package com.vladimir.messenger.data.security

import com.vladimir.messenger.data.swarm.Ed25519
import com.vladimir.messenger.util.ProfileCallsign
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографическое ядро привязки профиля к физическому устройству (`ANDROID_ID`)
 * без раскрытия самого `ANDROID_ID` и без зависимости от Android SDK (чистый JVM).
 *
 * Решает две задачи:
 *  1. После удаления и повторной установки APK телефон находит на сервере свою
 *     зашифрованную подсказку (`deviceShelf` -> `AES-256-GCM`) и сразу предлагает
 *     войти в прежний `@никнейм`, не создавая профиль-дубль.
 *  2. Если владелец всё же завёл новый профиль на том же телефоне, детерминированный
 *     ключ якоря устройства (`Ed25519`) выпускает неподделываемое доказательство
 *     замены (`APUGRP1|dva1|rp|…`), по которому контакты безопасно схлопывают
 *     старый дубль и переводят диалог на новый живой адрес.
 */
object DeviceProfileAnchor {
    private const val SHELF_DOMAIN = "apu-device-vault-shelf-v1"
    private const val SEAL_DOMAIN = "apu-device-hint-seal-v1"
    private const val ANCHOR_DOMAIN = "apu-device-anchor-ed25519-v1"
    private val HINT_AAD = "apu-device-hint-v1".toByteArray(Charsets.US_ASCII)

    private const val PBKDF2_ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val HINT_VERSION: Byte = 1

    private val NODE_ID_REGEX = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")
    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")
    private val HEX_128_REGEX = Regex("^[0-9a-f]{128}$")
    private val NICK_CLEAN_REGEX = Regex("^[a-z0-9_]{1,32}$")

    /** Известные константы AOSP/эмуляторов, которые не уникальны для устройства. */
    private val KNOWN_PLACEHOLDER_ANDROID_IDS = setOf(
        "9774d56d682e549c",
        "0123456789abcdef",
        "fedcba9876543210",
        "deadbeefdeadbeef",
        "unknown",
        "null",
    )

    private val random = SecureRandom()
    private val shelfCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val anchorPubCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class DeviceHintRecord(
        val nodeId: String,
        val nickname: String,
        val displayName: String,
        val callsign: String,
        val registeredAtMs: Long,
        val updatedAtMs: Long,
        val anchorPublicKeyHex: String,
    )

    /** Проверка, что системный `ANDROID_ID` пригоден и не является заглушкой эмулятора. */
    fun normalizeAndroidId(raw: String?): String? {
        val clean = raw?.trim()?.lowercase().orEmpty()
        if (clean.length < 12 || clean.length > 64) return null
        if (clean in KNOWN_PLACEHOLDER_ANDROID_IDS) return null
        if (clean.all { it == clean[0] }) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'z' }) return null
        return clean
    }

    fun isValidAndroidId(raw: String?): Boolean = normalizeAndroidId(raw) != null

    /**
     * Полка `APU_VAULT` (64 hex-символа) для зашифрованной подсказки профиля этого телефона.
     * Совместима с уже развёрнутым воркером `/vault/put` и `/vault/get`.
     */
    fun deviceShelf(androidId: String?): String? {
        val id = normalizeAndroidId(androidId) ?: return null
        shelfCache[id]?.let { return it }
        val derived = pbkdf2(
            secret = "$SHELF_DOMAIN:$id",
            salt = SHELF_DOMAIN.toByteArray(Charsets.US_ASCII),
        ) ?: return null
        val hex = try {
            toHex(derived)
        } finally {
            derived.fill(0)
        }
        if (shelfCache.size < 8) shelfCache[id] = hex
        return hex
    }

    /** Детерминированная пара ключей Ed25519 физического телефона (переживает переустановку APK). */
    fun anchorKeyPair(androidId: String?): Ed25519.KeyPair? {
        val id = normalizeAndroidId(androidId) ?: return null
        val seed = pbkdf2(
            secret = "$ANCHOR_DOMAIN:$id",
            salt = ANCHOR_DOMAIN.toByteArray(Charsets.US_ASCII),
        ) ?: return null
        return try {
            val kp = Ed25519.keyPairFromSeed(seed)
            if (anchorPubCache.size < 8) anchorPubCache[id] = toHex(kp.publicKey)
            kp
        } finally {
            seed.fill(0)
        }
    }

    fun anchorPublicKeyHex(androidId: String?): String? {
        val id = normalizeAndroidId(androidId) ?: return null
        anchorPubCache[id]?.let { return it }
        return anchorKeyPair(id)?.let { toHex(it.publicKey) }
    }

    /** Зашифровать карточку профиля этого устройства ключом, выведенным из `ANDROID_ID`. */
    fun sealHint(record: DeviceHintRecord, androidId: String?): ByteArray? {
        val id = normalizeAndroidId(androidId) ?: return null
        val canonicalNode = canonicalNodeId(record.nodeId) ?: return null
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = pbkdf2("$SEAL_DOMAIN:$id", salt) ?: return null
        return try {
            val payload = encodeRecord(
                record.copy(
                    nodeId = canonicalNode,
                    callsign = record.callsign.ifBlank { ProfileCallsign.of(canonicalNode).orEmpty() },
                    anchorPublicKeyHex = record.anchorPublicKeyHex.ifBlank {
                        anchorPublicKeyHex(id).orEmpty()
                    },
                ),
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(HINT_AAD)
            val encrypted = cipher.doFinal(payload)
            byteArrayOf(HINT_VERSION) + salt + iv + encrypted
        } catch (_: GeneralSecurityException) {
            // Сбой криптопровайдера трактуется как отсутствие зашифрованного конверта.
            null
        } finally {
            key.fill(0)
        }
    }

    /** Расшифровать карточку профиля этого устройства; чужой `ANDROID_ID` или битые байты вернут null. */
    fun openHint(sealed: ByteArray?, androidId: String?): DeviceHintRecord? {
        if (sealed == null) return null
        val id = normalizeAndroidId(androidId) ?: return null
        val headerSize = 1 + SALT_BYTES + IV_BYTES
        if (sealed.size <= headerSize + 16 || sealed[0] != HINT_VERSION) return null
        val salt = sealed.copyOfRange(1, 1 + SALT_BYTES)
        val iv = sealed.copyOfRange(1 + SALT_BYTES, headerSize)
        val body = sealed.copyOfRange(headerSize, sealed.size)
        val key = pbkdf2("$SEAL_DOMAIN:$id", salt) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(HINT_AAD)
            decodeRecord(cipher.doFinal(body))
        } catch (_: GeneralSecurityException) {
            // Неверный ключ устройства или нарушенная целостность GCM — штатный отказ.
            null
        } catch (_: IllegalArgumentException) {
            // Некорректная структура полезной нагрузки — отклоняем без падения.
            null
        } finally {
            key.fill(0)
        }
    }

    internal fun canonicalNodeId(raw: String?): String? {
        val clean = raw?.trim()?.removeSuffix("_public")?.lowercase().orEmpty()
        return clean.takeIf { NODE_ID_REGEX.matches(it) }
    }

    internal fun cleanNickname(raw: String?): String {
        val clean = raw?.trim()?.trimStart('@')?.lowercase().orEmpty()
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .take(32)
        return if (clean.isEmpty() || NICK_CLEAN_REGEX.matches(clean)) clean else ""
    }

    private fun pbkdf2(secret: String, salt: ByteArray): ByteArray? = try {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    } catch (_: GeneralSecurityException) {
        // Платформа без PBKDF2WithHmacSHA256 возвращает честный отказ.
        null
    }

    private fun encodeRecord(record: DeviceHintRecord): ByteArray {
        val safeName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(record.displayName.take(64).toByteArray(Charsets.UTF_8))
        val line = listOf(
            "v1",
            record.nodeId,
            cleanNickname(record.nickname),
            safeName,
            record.callsign.take(16),
            record.registeredAtMs.toString(),
            record.updatedAtMs.toString(),
            record.anchorPublicKeyHex.lowercase(),
        ).joinToString("|")
        return line.toByteArray(Charsets.UTF_8)
    }

    private fun decodeRecord(bytes: ByteArray): DeviceHintRecord? {
        val parts = String(bytes, Charsets.UTF_8).split('|')
        if (parts.size != 8 || parts[0] != "v1") return null
        val nodeId = canonicalNodeId(parts[1]) ?: return null
        val nick = cleanNickname(parts[2])
        val displayName = String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
        val callsign = parts[4].ifBlank { ProfileCallsign.of(nodeId).orEmpty() }
        val regAt = parts[5].toLongOrNull() ?: 0L
        val updAt = parts[6].toLongOrNull() ?: 0L
        val anchorHex = parts[7].lowercase()
        if (anchorHex.isNotEmpty() && !HEX_64_REGEX.matches(anchorHex)) return null
        return DeviceHintRecord(
            nodeId = nodeId,
            nickname = nick,
            displayName = displayName,
            callsign = callsign,
            registeredAtMs = regAt,
            updatedAtMs = updAt,
            anchorPublicKeyHex = anchorHex,
        )
    }

    internal fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Проводной формат служебных пакетов привязки и замены профиля устройства (`APUGRP1|dva1|…`).
     * Старые клиенты поглощают пакет как неизвестный групповой конверт без мусора в чате.
     */
    object Wire {
        const val PREFIX = "APUGRP1|dva1|"
        private const val MAX_WIRE_LENGTH = 1024

        sealed interface Packet {
            data class Bind(
                val ownerId: String,
                val anchorPubHex: String,
                val timestampMs: Long,
                val signatureHex: String,
            ) : Packet

            data class Replace(
                val oldOwnerId: String,
                val newOwnerId: String,
                val nickname: String,
                val displayName: String,
                val timestampMs: Long,
                val anchorPubHex: String,
                val signatureHex: String,
                val hops: Int = 0,
            ) : Packet {
                fun nextHop(): String? {
                    if (hops >= MAX_REPLACE_HOPS) return null
                    val nameB64 = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(displayName.trim().take(50).toByteArray(Charsets.UTF_8))
                    return "${PREFIX}rp|$oldOwnerId|$newOwnerId|$nickname|$nameB64|$timestampMs|$anchorPubHex|$signatureHex|${hops + 1}"
                }
            }
        }

        const val MAX_REPLACE_HOPS = 2

        fun isPacket(text: String): Boolean = text.startsWith(PREFIX)

        fun createBind(ownerId: String, androidId: String?, timestampMs: Long): String? {
            val canonicalOwner = canonicalNodeId(ownerId) ?: return null
            val kp = anchorKeyPair(androidId) ?: return null
            val pubHex = toHex(kp.publicKey)
            val msg = bindSigningBytes(canonicalOwner, pubHex, timestampMs)
            val sigHex = toHex(Ed25519.sign(kp.privateKey, msg))
            return "${PREFIX}bn|$canonicalOwner|$pubHex|$timestampMs|$sigHex"
        }

        fun createReplace(
            oldOwnerId: String,
            newOwnerId: String,
            nickname: String,
            displayName: String,
            androidId: String?,
            timestampMs: Long,
            hops: Int = 0,
        ): String? {
            if (hops !in 0..MAX_REPLACE_HOPS) return null
            val oldCanonical = canonicalNodeId(oldOwnerId) ?: return null
            val newCanonical = canonicalNodeId(newOwnerId) ?: return null
            if (oldCanonical == newCanonical) return null
            val kp = anchorKeyPair(androidId) ?: return null
            val pubHex = toHex(kp.publicKey)
            val cleanNick = cleanNickname(nickname)
            val nameB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(displayName.trim().take(50).toByteArray(Charsets.UTF_8))
            val msg = replaceSigningBytes(oldCanonical, newCanonical, cleanNick, nameB64, timestampMs, pubHex)
            val sigHex = toHex(Ed25519.sign(kp.privateKey, msg))
            return "${PREFIX}rp|$oldCanonical|$newCanonical|$cleanNick|$nameB64|$timestampMs|$pubHex|$sigHex|$hops"
        }

        /** Разбор И криптографическая проверка подписи Ed25519. Поддельные пакеты возвращают null. */
        fun parseVerified(text: String): Packet? {
            if (!isPacket(text) || text.length > MAX_WIRE_LENGTH) return null
            val parts = text.split('|')
            if (parts.size < 3 || parts[0] != "APUGRP1" || parts[1] != "dva1") return null
            return when (parts[2]) {
                "bn" -> {
                    if (parts.size != 7) return null
                    val owner = canonicalNodeId(parts[3]) ?: return null
                    val pubHex = parts[4].lowercase().takeIf { HEX_64_REGEX.matches(it) } ?: return null
                    val ts = parts[5].toLongOrNull()?.takeIf { it > 0L } ?: return null
                    val sigHex = parts[6].lowercase().takeIf { HEX_128_REGEX.matches(it) } ?: return null
                    val pub = fromHex(pubHex) ?: return null
                    val sig = fromHex(sigHex) ?: return null
                    val msg = bindSigningBytes(owner, pubHex, ts)
                    if (!Ed25519.verify(pub, msg, sig)) return null
                    Packet.Bind(owner, pubHex, ts, sigHex)
                }
                "rp" -> {
                    if (parts.size != 10 && parts.size != 11) return null
                    val oldOwner = canonicalNodeId(parts[3]) ?: return null
                    val newOwner = canonicalNodeId(parts[4]) ?: return null
                    if (oldOwner == newOwner) return null
                    val nick = cleanNickname(parts[5])
                    val nameB64 = parts[6]
                    if (nameB64.length > 120) return null
                    val displayName = try {
                        String(Base64.getUrlDecoder().decode(nameB64), Charsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        // Некорректный base64url в сетевом пакете — отклоняем.
                        return null
                    }
                    val ts = parts[7].toLongOrNull()?.takeIf { it > 0L } ?: return null
                    val pubHex = parts[8].lowercase().takeIf { HEX_64_REGEX.matches(it) } ?: return null
                    val sigHex = parts[9].lowercase().takeIf { HEX_128_REGEX.matches(it) } ?: return null
                    val hops = if (parts.size == 11) {
                        parts[10].toIntOrNull()?.takeIf { it in 0..MAX_REPLACE_HOPS } ?: return null
                    } else {
                        0
                    }
                    val pub = fromHex(pubHex) ?: return null
                    val sig = fromHex(sigHex) ?: return null
                    val msg = replaceSigningBytes(oldOwner, newOwner, nick, nameB64, ts, pubHex)
                    if (!Ed25519.verify(pub, msg, sig)) return null
                    Packet.Replace(oldOwner, newOwner, nick, displayName, ts, pubHex, sigHex, hops)
                }
                else -> null
            }
        }

        private fun bindSigningBytes(owner: String, pubHex: String, ts: Long): ByteArray =
            "apu-dva1-bind|$owner|$pubHex|$ts".toByteArray(Charsets.UTF_8)

        private fun replaceSigningBytes(
            oldOwner: String,
            newOwner: String,
            nick: String,
            nameB64: String,
            ts: Long,
            pubHex: String,
        ): ByteArray =
            "apu-dva1-replace|$oldOwner|$newOwner|$nick|$nameB64|$ts|$pubHex".toByteArray(Charsets.UTF_8)
    }

    /**
     * Локальный журнал TOFU-привязок «узел -> публичный ключ якоря устройства».
     *
     * Правило безопасности: первый валидный `Bind` закрепляет `anchorPubHex` за `ownerId`.
     * Перезаписать чужим `anchorPubHex` нельзя. Замена `oldOwnerId -> newOwnerId`
     * принимается ТОЛЬКО если `oldOwnerId` уже был закреплён за тем же `anchorPubHex`.
     */
    class PeerDeviceAnchorLedger(
        initialPins: Map<String, String> = emptyMap(),
        initialReplacements: Map<String, String> = emptyMap(),
    ) {
        private val pins = LinkedHashMap<String, String>(initialPins)
        private val replacedBy = LinkedHashMap<String, String>(initialReplacements)

        @Synchronized
        fun pinOrVerify(ownerId: String, anchorPubHex: String): Boolean {
            val owner = canonicalNodeId(ownerId) ?: return false
            val pub = anchorPubHex.lowercase().takeIf { HEX_64_REGEX.matches(it) } ?: return false
            val existing = pins[owner]
            if (existing != null) return existing == pub
            pins[owner] = pub
            trimIfNeeded()
            return true
        }

        @Synchronized
        fun pinnedAnchor(ownerId: String): String? =
            canonicalNodeId(ownerId)?.let { pins[it] }

        @Synchronized
        fun acceptVerifiedReplacement(
            oldOwnerId: String,
            newOwnerId: String,
            anchorPubHex: String,
        ): Boolean {
            val oldCanonical = canonicalNodeId(oldOwnerId) ?: return false
            val newCanonical = canonicalNodeId(newOwnerId) ?: return false
            if (oldCanonical == newCanonical) return false
            val pub = anchorPubHex.lowercase().takeIf { HEX_64_REGEX.matches(it) } ?: return false
            val oldPin = pins[oldCanonical] ?: return false
            if (oldPin != pub) return false
            val newPin = pins[newCanonical]
            if (newPin != null && newPin != pub) return false
            pins[newCanonical] = pub
            replacedBy[oldCanonical] = newCanonical
            trimIfNeeded()
            return true
        }

        @Synchronized
        fun areSameVerifiedDevice(firstOwnerId: String, secondOwnerId: String): Boolean {
            val a = canonicalNodeId(firstOwnerId) ?: return false
            val b = canonicalNodeId(secondOwnerId) ?: return false
            if (a == b) return true
            val pinA = pins[a] ?: return false
            val pinB = pins[b] ?: return false
            return pinA == pinB
        }

        @Synchronized
        fun replacementOf(ownerId: String): String? {
            var cursor = canonicalNodeId(ownerId) ?: return null
            var hops = 0
            while (hops < 8) {
                val next = replacedBy[cursor] ?: break
                if (next == cursor) break
                cursor = next
                hops++
            }
            return cursor.takeIf { it != canonicalNodeId(ownerId) }
        }

        @Synchronized
        fun serialize(): String {
            val sb = StringBuilder()
            for ((owner, pub) in pins) {
                sb.append("p|").append(owner).append('|').append(pub).append('\n')
            }
            for ((oldId, newId) in replacedBy) {
                sb.append("r|").append(oldId).append('|').append(newId).append('\n')
            }
            return sb.toString()
        }

        private fun trimIfNeeded() {
            while (pins.size > MAX_LEDGER_ENTRIES) {
                val oldestKey = pins.keys.firstOrNull() ?: break
                pins.remove(oldestKey)
            }
            while (replacedBy.size > MAX_LEDGER_ENTRIES) {
                val oldestKey = replacedBy.keys.firstOrNull() ?: break
                replacedBy.remove(oldestKey)
            }
        }

        companion object {
            private const val MAX_LEDGER_ENTRIES = 2048

            fun deserialize(serialized: String?): PeerDeviceAnchorLedger {
                if (serialized.isNullOrBlank()) return PeerDeviceAnchorLedger()
                val pins = LinkedHashMap<String, String>()
                val replacements = LinkedHashMap<String, String>()
                for (rawLine in serialized.lineSequence()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    val parts = line.split('|')
                    if (parts.size != 3) continue
                    when (parts[0]) {
                        "p" -> {
                            val owner = canonicalNodeId(parts[1]) ?: continue
                            val pub = parts[2].lowercase().takeIf { HEX_64_REGEX.matches(it) } ?: continue
                            pins[owner] = pub
                        }
                        "r" -> {
                            val oldId = canonicalNodeId(parts[1]) ?: continue
                            val newId = canonicalNodeId(parts[2]) ?: continue
                            if (oldId != newId) replacements[oldId] = newId
                        }
                    }
                }
                return PeerDeviceAnchorLedger(pins, replacements)
            }
        }
    }
}
