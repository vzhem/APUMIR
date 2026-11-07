package com.vladimir.messenger.data.security

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.util.ProfileCallsign

/**
 * Хранилище и мост привязки профиля к `ANDROID_ID` текущего телефона.
 *
 * - Позволяет после удаления и новой установки APK узнать на этом же телефоне
 *   прежний профиль (`@никнейм`, имя, цифровой код `NNN-NNN`) и сразу предложить
 *   восстановление вместо создания клона.
 * - Ведёт локальный журнал проверенных TOFU-якорей собеседников и очередь
 *   доказательства замены профиля на том же устройстве (`oldOwnerId -> newOwnerId`).
 */
object DeviceProfileBinding {
    private const val TAG = "DeviceProfileBinding"
    private const val PREFS = "apu_device_profile_binding"
    private const val KEY_LEDGER = "peer_anchor_ledger_v1"
    private const val KEY_DISCOVERED_NODE = "discovered_node_id"
    private const val KEY_DISCOVERED_NICK = "discovered_nickname"
    private const val KEY_DISCOVERED_NAME = "discovered_display_name"
    private const val KEY_DISCOVERED_REG_AT = "discovered_reg_at_ms"
    private const val KEY_PENDING_SHELF = "pending_device_shelf"
    private const val KEY_PENDING_VAULT = "pending_device_vault"
    private const val KEY_LAST_SYNCED_SIG = "last_synced_profile_sig"
    private const val KEY_REPLACED_OLD_ID = "replaced_old_owner_id"
    private const val KEY_REPLACED_NEW_ID = "replaced_new_owner_id"
    private const val KEY_REPLACED_AT_MS = "replaced_at_ms"

    private val lock = Any()
    @Volatile
    private var cachedAndroidId: String? = null
    @Volatile
    private var androidIdLoaded = false
    @Volatile
    private var cachedLedger: DeviceProfileAnchor.PeerDeviceAnchorLedger? = null

    data class PendingReplacement(
        val oldOwnerId: String,
        val newOwnerId: String,
        val nickname: String,
        val displayName: String,
        val replacedAtMs: Long,
    )

    fun rawAndroidId(context: Context): String? {
        if (androidIdLoaded) return cachedAndroidId
        synchronized(lock) {
            if (androidIdLoaded) return cachedAndroidId
            val value = try {
                Settings.Secure.getString(
                    context.applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            } catch (e: Exception) {
                Log.w(TAG, "ANDROID_ID unavailable: ${e.message}")
                null
            }
            cachedAndroidId = DeviceProfileAnchor.normalizeAndroidId(value)
            androidIdLoaded = true
            return cachedAndroidId
        }
    }

    fun myAnchorPublicKeyHex(context: Context): String? =
        DeviceProfileAnchor.anchorPublicKeyHex(rawAndroidId(context))

    /**
     * Проверить на первом запуске (после переустановки), был ли уже профиль
     * на этом физическом телефоне. Спрашивает `deviceShelf` в `APU_VAULT`
     * и расшифровывает локально ключом из `ANDROID_ID`.
     */
    suspend fun discoverPreviousProfile(
        context: Context,
        botApi: BotApi,
    ): DeviceProfileAnchor.DeviceHintRecord? {
        val app = context.applicationContext
        val androidId = rawAndroidId(app) ?: return cachedDiscoveredHint(app)
        val shelf = DeviceProfileAnchor.deviceShelf(androidId) ?: return cachedDiscoveredHint(app)
        val encoded = try {
            botApi.fetchIdentityVault(shelf)
        } catch (e: Exception) {
            Log.w(TAG, "device profile shelf lookup failed: ${e.message}")
            null
        }
        if (!encoded.isNullOrBlank()) {
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                // Повреждённый base64 на полке игнорируем.
                null
            }
            val opened = DeviceProfileAnchor.openHint(bytes, androidId)
            if (opened != null) {
                rememberDiscoveredHint(app, opened)
                return opened
            }
        }
        return cachedDiscoveredHint(app)
    }

    fun cachedDiscoveredHint(context: Context): DeviceProfileAnchor.DeviceHintRecord? {
        val p = prefs(context)
        val nodeId = DeviceProfileAnchor.canonicalNodeId(p.getString(KEY_DISCOVERED_NODE, null))
            ?: return null
        val nick = p.getString(KEY_DISCOVERED_NICK, "").orEmpty()
        val name = p.getString(KEY_DISCOVERED_NAME, "").orEmpty()
        val regAt = p.getLong(KEY_DISCOVERED_REG_AT, 0L)
        return DeviceProfileAnchor.DeviceHintRecord(
            nodeId = nodeId,
            nickname = nick,
            displayName = name,
            callsign = ProfileCallsign.of(nodeId).orEmpty(),
            registeredAtMs = regAt,
            updatedAtMs = regAt,
            anchorPublicKeyHex = myAnchorPublicKeyHex(context).orEmpty(),
        )
    }

    private fun rememberDiscoveredHint(
        context: Context,
        record: DeviceProfileAnchor.DeviceHintRecord,
    ) {
        val app = context.applicationContext
        DeviceIdentityHistory.remember(app, record.nodeId)
        if (record.anchorPublicKeyHex.isNotBlank()) {
            pinOrVerifyPeer(app, record.nodeId, record.anchorPublicKeyHex)
        }
        prefs(app).edit()
            .putString(KEY_DISCOVERED_NODE, record.nodeId)
            .putString(KEY_DISCOVERED_NICK, record.nickname)
            .putString(KEY_DISCOVERED_NAME, record.displayName)
            .putLong(KEY_DISCOVERED_REG_AT, record.registeredAtMs)
            .apply()
    }

    /**
     * Зафиксировать текущий активный профиль этого телефона и выгрузить зашифрованную
     * подсказку на `deviceShelf`. Если на этом же телефоне раньше был другой `oldNodeId`,
     * сохраняет запись замены для рассылки контактам (`APUGRP1|dva1|rp|…`).
     */
    suspend fun saveCurrentProfile(
        context: Context,
        botApi: BotApi,
        nodeId: String,
        nickname: String,
        displayName: String,
        registeredAtMs: Long = System.currentTimeMillis(),
        previousOwnerId: String? = null,
    ) {
        val app = context.applicationContext
        val canonicalNode = DeviceProfileAnchor.canonicalNodeId(nodeId) ?: return
        val cleanNick = DeviceProfileAnchor.cleanNickname(nickname)
        val now = System.currentTimeMillis()
        DeviceIdentityHistory.remember(app, canonicalNode)

        val androidId = rawAndroidId(app)
        val anchorPub = DeviceProfileAnchor.anchorPublicKeyHex(androidId).orEmpty()
        if (anchorPub.isNotBlank()) {
            pinOrVerifyPeer(app, canonicalNode, anchorPub)
        }

        val priorNode = DeviceProfileAnchor.canonicalNodeId(previousOwnerId)
            ?: cachedDiscoveredHint(app)?.nodeId?.takeIf { it != canonicalNode }
        if (priorNode != null && priorNode != canonicalNode) {
            DeviceIdentityHistory.remember(app, priorNode)
            if (anchorPub.isNotBlank()) {
                pinOrVerifyPeer(app, priorNode, anchorPub)
                acceptVerifiedReplacement(app, priorNode, canonicalNode, anchorPub)
            }
            prefs(app).edit()
                .putString(KEY_REPLACED_OLD_ID, priorNode)
                .putString(KEY_REPLACED_NEW_ID, canonicalNode)
                .putLong(KEY_REPLACED_AT_MS, now)
                .apply()
        }

        val record = DeviceProfileAnchor.DeviceHintRecord(
            nodeId = canonicalNode,
            nickname = cleanNick,
            displayName = displayName.trim(),
            callsign = ProfileCallsign.of(canonicalNode).orEmpty(),
            registeredAtMs = registeredAtMs,
            updatedAtMs = now,
            anchorPublicKeyHex = anchorPub,
        )
        rememberDiscoveredHint(app, record)

        if (androidId == null) return
        val shelf = DeviceProfileAnchor.deviceShelf(androidId) ?: return
        val sealed = DeviceProfileAnchor.sealHint(record, androidId) ?: return
        val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
        val sig = "$canonicalNode|$cleanNick|${displayName.trim()}"

        prefs(app).edit()
            .putString(KEY_PENDING_SHELF, shelf)
            .putString(KEY_PENDING_VAULT, encoded)
            .apply()

        val stored = try {
            botApi.storeIdentityVault(shelf = shelf, sealedBase64 = encoded)
        } catch (e: Exception) {
            Log.w(TAG, "device profile hint upload deferred: ${e.message}")
            false
        }
        if (stored) {
            prefs(app).edit()
                .remove(KEY_PENDING_SHELF)
                .remove(KEY_PENDING_VAULT)
                .putString(KEY_LAST_SYNCED_SIG, sig)
                .apply()
        }
    }

    /** Фоновая досылка или первичная привязка уже существующего профиля после обновления APK. */
    suspend fun syncCurrentProfileToVault(context: Context, botApi: BotApi) {
        val app = context.applicationContext
        val p = prefs(app)
        val pendingShelf = p.getString(KEY_PENDING_SHELF, null)
        val pendingVault = p.getString(KEY_PENDING_VAULT, null)
        if (!pendingShelf.isNullOrBlank() && !pendingVault.isNullOrBlank()) {
            val ok = try {
                botApi.storeIdentityVault(pendingShelf, pendingVault)
            } catch (e: Exception) {
                Log.w(TAG, "pending device hint upload failed: ${e.message}")
                false
            }
            if (ok) {
                p.edit().remove(KEY_PENDING_SHELF).remove(KEY_PENDING_VAULT).apply()
            }
            return
        }

        val identityPrefs = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val nodeId = DeviceProfileAnchor.canonicalNodeId(identityPrefs.getString("node_id", null)) ?: return
        val nick = DeviceProfileAnchor.cleanNickname(identityPrefs.getString("my_username", null))
        val displayName = identityPrefs.getString("display_name", "").orEmpty().trim()
        val regAt = identityPrefs.getLong("my_nick_registered_at", 0L)
        val sig = "$nodeId|$nick|$displayName"
        if (p.getString(KEY_LAST_SYNCED_SIG, null) == sig) {
            val anchorPub = myAnchorPublicKeyHex(app)
            if (!anchorPub.isNullOrBlank()) pinOrVerifyPeer(app, nodeId, anchorPub)
            return
        }
        saveCurrentProfile(app, botApi, nodeId, nick, displayName, regAt)
    }

    fun pendingReplacement(context: Context): PendingReplacement? {
        val app = context.applicationContext
        val p = prefs(app)
        val oldId = DeviceProfileAnchor.canonicalNodeId(p.getString(KEY_REPLACED_OLD_ID, null)) ?: return null
        val newId = DeviceProfileAnchor.canonicalNodeId(p.getString(KEY_REPLACED_NEW_ID, null)) ?: return null
        val identityPrefs = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val currentNode = DeviceProfileAnchor.canonicalNodeId(identityPrefs.getString("node_id", null))
        if (currentNode != null && currentNode != newId) return null
        val nick = DeviceProfileAnchor.cleanNickname(identityPrefs.getString("my_username", null))
        val name = identityPrefs.getString("display_name", "").orEmpty().trim()
        val atMs = p.getLong(KEY_REPLACED_AT_MS, System.currentTimeMillis())
        return PendingReplacement(oldId, newId, nick, name, atMs)
    }

    fun createMyBindPacket(context: Context, ownerId: String, nowMs: Long = System.currentTimeMillis()): String? =
        DeviceProfileAnchor.Wire.createBind(ownerId, rawAndroidId(context), nowMs)

    fun createMyReplacePacket(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        val repl = pendingReplacement(context) ?: return null
        return DeviceProfileAnchor.Wire.createReplace(
            oldOwnerId = repl.oldOwnerId,
            newOwnerId = repl.newOwnerId,
            nickname = repl.nickname,
            displayName = repl.displayName,
            androidId = rawAndroidId(context),
            timestampMs = repl.replacedAtMs.takeIf { it > 0L } ?: nowMs,
        )
    }

    /** Доказано ли, что [ownerId] был создан на ЭТОМ физическом телефоне (включая прошлые установки APK). */
    fun belongsToThisPhysicalDevice(context: Context, ownerId: String): Boolean {
        val app = context.applicationContext
        val canonical = DeviceProfileAnchor.canonicalNodeId(ownerId) ?: return false
        if (DeviceIdentityHistory.wasCreatedOnThisDevice(app, canonical)) return true
        if (cachedDiscoveredHint(app)?.nodeId == canonical) return true
        val myAnchor = myAnchorPublicKeyHex(app)
        if (!myAnchor.isNullOrBlank() && ledger(app).pinnedAnchor(canonical) == myAnchor) return true
        return false
    }

    fun pinOrVerifyPeer(context: Context, ownerId: String, anchorPubHex: String): Boolean {
        synchronized(lock) {
            val current = ledger(context)
            val ok = current.pinOrVerify(ownerId, anchorPubHex)
            if (ok) saveLedgerLocked(context, current)
            return ok
        }
    }

    fun acceptVerifiedReplacement(
        context: Context,
        oldOwnerId: String,
        newOwnerId: String,
        anchorPubHex: String,
    ): Boolean {
        synchronized(lock) {
            val current = ledger(context)
            val ok = current.acceptVerifiedReplacement(oldOwnerId, newOwnerId, anchorPubHex)
            if (ok) saveLedgerLocked(context, current)
            return ok
        }
    }

    fun areSameVerifiedDevice(context: Context, firstOwnerId: String, secondOwnerId: String): Boolean {
        val app = context.applicationContext
        if (belongsToThisPhysicalDevice(app, firstOwnerId) &&
            belongsToThisPhysicalDevice(app, secondOwnerId)
        ) {
            return true
        }
        synchronized(lock) {
            return ledger(app).areSameVerifiedDevice(firstOwnerId, secondOwnerId)
        }
    }

    fun replacementOf(context: Context, ownerId: String): String? {
        synchronized(lock) {
            return ledger(context.applicationContext).replacementOf(ownerId)
        }
    }

    private fun ledger(context: Context): DeviceProfileAnchor.PeerDeviceAnchorLedger {
        cachedLedger?.let { return it }
        synchronized(lock) {
            cachedLedger?.let { return it }
            val loaded = DeviceProfileAnchor.PeerDeviceAnchorLedger.deserialize(
                prefs(context).getString(KEY_LEDGER, null),
            )
            cachedLedger = loaded
            return loaded
        }
    }

    private fun saveLedgerLocked(
        context: Context,
        ledger: DeviceProfileAnchor.PeerDeviceAnchorLedger,
    ) {
        prefs(context).edit().putString(KEY_LEDGER, ledger.serialize()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
