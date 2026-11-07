package com.vladimir.messenger.data.swarm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище и применение пользовательских настроек IPv6-прокси («Настройки → Сеть»).
 *
 * Позволяет ввести реквизиты купленного IPv6-прокси (SOCKS5 или HTTP(S),
 * IPv4-шлюз или прямой IPv6-адрес, логин и пароль) и сразу пустить через него
 * соединения движка и сетевые запросы приложения независимо от ранга и автопилота.
 */
object Ipv6NetworkSettings {

    private const val TAG = "Ipv6NetworkSettings"
    private const val PREFS = "ipv6_network_proxy_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD_ENC = "password_enc"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "apu_ipv6_proxy_secret_v1"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var loaded = false

    private val _config = MutableStateFlow(Ipv6ProxyConfig())
    val config: StateFlow<Ipv6ProxyConfig> = _config.asStateFlow()

    private val rustSink = object : Ipv6ProxyEngineSink {
        override fun setSocks5Proxy(host: String, port: Int, username: String, password: String) {
            try {
                RustBridge.setMqttSocks5Proxy(host, port, username, password)
            } catch (t: Throwable) {
                Log.w(TAG, "setSocks5Proxy failed: ${t.message}")
            }
        }

        override fun clearSocks5Proxy() {
            try {
                RustBridge.clearMqttSocks5Proxy()
            } catch (t: Throwable) {
                Log.w(TAG, "clearSocks5Proxy failed: ${t.message}")
            }
        }

        override fun wakeNetwork() {
            try {
                RustBridge.onNetworkAvailable()
            } catch (t: Throwable) {
                Log.w(TAG, "wakeNetwork failed: ${t.message}")
            }
        }
    }

    @Synchronized
    fun initialize(context: Context): Ipv6ProxyConfig {
        if (!loaded) {
            _config.value = readFromPrefs(context.applicationContext)
            loaded = true
        }
        return _config.value
    }

    fun current(context: Context): Ipv6ProxyConfig = initialize(context)

    fun isActive(context: Context): Boolean = initialize(context).isActive

    /**
     * Сохранить реквизиты, включить работу через этот IPv6-прокси и сразу
     * переключить транспорт приложения.
     */
    @Synchronized
    fun saveAndActivate(context: Context, candidate: Ipv6ProxyConfig): Boolean {
        if (!candidate.isConfigured) return false
        val app = context.applicationContext
        val active = candidate.copy(enabled = true)
        writeToPrefs(app, active)
        _config.value = active
        loaded = true
        if (active.isIpv6Host && !NetworkPathSettings.ipv6Enabled(app)) {
            NetworkPathSettings.setIpv6Enabled(app, true)
        }
        Ipv6ProxyRuntime.apply(active, rustSink)
        Log.i(TAG, "IPv6 proxy saved and activated: ${active.safeSummary()}")
        return true
    }

    /**
     * Включить или выключить уже сохранённый IPv6-прокси без потери реквизитов.
     */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val existing = initialize(app)
        if (enabled && !existing.isConfigured) return false
        val updated = existing.copy(enabled = enabled && existing.isConfigured)
        writeToPrefs(app, updated)
        _config.value = updated
        if (updated.isActive && updated.isIpv6Host && !NetworkPathSettings.ipv6Enabled(app)) {
            NetworkPathSettings.setIpv6Enabled(app, true)
        }
        Ipv6ProxyRuntime.apply(updated, rustSink)
        Log.i(TAG, "IPv6 proxy enabled=${updated.isActive} (${updated.safeSummary()})")
        return true
    }

    /** Полностью очистить реквизиты IPv6-прокси и вернуть прямой режим сети. */
    @Synchronized
    fun clear(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().clear().apply()
        val empty = Ipv6ProxyConfig()
        _config.value = empty
        loaded = true
        Ipv6ProxyRuntime.apply(empty, rustSink)
        Log.i(TAG, "IPv6 proxy cleared")
    }

    /** Применить сохранённый прокси при старте приложения и при смене сети. */
    fun applyToCore(context: Context) {
        val cfg = initialize(context.applicationContext)
        Ipv6ProxyRuntime.apply(cfg, rustSink)
    }

    fun probe(candidate: Ipv6ProxyConfig): Ipv6ProxyProbeResult =
        Ipv6ProxyRuntime.probe(candidate)

    private fun readFromPrefs(app: Context): Ipv6ProxyConfig {
        val sp = prefs(app)
        val host = Ipv6ProxyParser.normalizeHost(sp.getString(KEY_HOST, "").orEmpty()).orEmpty()
        val port = sp.getInt(KEY_PORT, 0)
        val protocol = Ipv6ProxyProtocol.fromStorage(sp.getString(KEY_PROTOCOL, null))
        val username = sp.getString(KEY_USERNAME, "").orEmpty()
        val password = decryptSecret(sp.getString(KEY_PASSWORD_ENC, "").orEmpty())
        val enabled = sp.getBoolean(KEY_ENABLED, false)
        val cfg = Ipv6ProxyConfig(
            enabled = enabled,
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            password = if (username.isEmpty()) "" else password,
        )
        return if (cfg.isConfigured) cfg else Ipv6ProxyConfig()
    }

    private fun writeToPrefs(app: Context, cfg: Ipv6ProxyConfig) {
        prefs(app).edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_PROTOCOL, cfg.protocol.name)
            .putString(KEY_HOST, cfg.host)
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_USERNAME, cfg.username)
            .putString(KEY_PASSWORD_ENC, encryptSecret(cfg.password))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encryptSecret(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            "ks:" + Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore wrap unavailable, using local fallback: ${e.message}")
            "b64:" + Base64.getEncoder().encodeToString(plain.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun decryptSecret(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            when {
                stored.startsWith("ks:") -> {
                    val raw = Base64.getDecoder().decode(stored.removePrefix("ks:"))
                    if (raw.size <= GCM_IV_LEN) return ""
                    val key = getOrCreateSecretKey()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_LEN))
                    String(cipher.doFinal(raw, GCM_IV_LEN, raw.size - GCM_IV_LEN), StandardCharsets.UTF_8)
                }
                stored.startsWith("b64:") -> {
                    String(Base64.getDecoder().decode(stored.removePrefix("b64:")), StandardCharsets.UTF_8)
                }
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unwrap proxy secret: ${e.message}")
            ""
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
