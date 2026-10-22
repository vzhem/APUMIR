package com.vladimir.messenger.data.textcolor

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local colour preferences. Message colours are intentionally scoped locally:
 * the selected colour for one chat/topic must not silently change another one.
 * The colour carried by a message is stored separately in the message row so
 * the recipient sees the author's choice.
 */
object TextColorSettingsStore {
    private const val PREFS = "message_text_colors"
    private const val OWN_TEXT = "own_text"
    private const val OWN_NAME = "own_name"
    private const val READ_ONLY = "read_only"
    private const val READ_COLOR = "read_color"
    private const val SCOPE_PREFIX = "scope_"
    private const val PEER_NAME_PREFIX = "peer_name_"

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _ownNameColor = MutableStateFlow(TextColorPalette.DEFAULT_ID)
    val ownNameColor: StateFlow<String> = _ownNameColor.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun touch() {
        _revision.value = _revision.value + 1L
    }

    fun initialize(context: Context) {
        val value = prefs(context).getString(OWN_NAME, TextColorPalette.DEFAULT_ID).orEmpty()
        _ownNameColor.value = TextColorPalette.normalize(value)
    }

    fun normalize(id: String?): String = TextColorPalette.normalize(id)

    fun ownTextColor(context: Context): String {
        initialize(context)
        return TextColorPalette.normalize(prefs(context).getString(OWN_TEXT, null))
    }

    fun setOwnTextColor(context: Context, id: String?) {
        initialize(context)
        val value = TextColorPalette.normalize(id)
        prefs(context).edit().putString(OWN_TEXT, value).apply()
        touch()
    }

    fun ownNameColor(context: Context): String {
        initialize(context)
        return _ownNameColor.value
    }

    fun setOwnNameColor(context: Context, id: String?) {
        val value = TextColorPalette.normalize(id)
        prefs(context).edit().putString(OWN_NAME, value).apply()
        _ownNameColor.value = value
        touch()
    }

    fun readOnlySelectedColor(context: Context): Boolean =
        prefs(context).getBoolean(READ_ONLY, false)

    fun selectedReadColor(context: Context): String =
        TextColorPalette.normalize(prefs(context).getString(READ_COLOR, null))

    fun setReadOnlySelectedColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(READ_ONLY, enabled).apply()
        touch()
    }

    fun setSelectedReadColor(context: Context, id: String?) {
        val value = TextColorPalette.normalize(id)
        prefs(context).edit().putString(READ_COLOR, value).apply()
        touch()
    }

    /**
     * Return every local colour choice and the reader filter to the normal
     * theme colour.  This deliberately removes only keys owned by this store;
     * it never clears the preference file wholesale because peer metadata and
     * future colour settings may live beside the current choices.
     */
    fun resetAll(context: Context) {
        val editor = prefs(context).edit()
            .remove(OWN_TEXT)
            .remove(OWN_NAME)
            .remove(READ_ONLY)
            .remove(READ_COLOR)
        prefs(context).all.keys
            .filter { it.startsWith(SCOPE_PREFIX) || it.startsWith(PEER_NAME_PREFIX) }
            .forEach { key -> editor.remove(key) }
        editor.apply()
        _ownNameColor.value = TextColorPalette.DEFAULT_ID
        touch()
    }

    /** Exact override used to offer Undo for a single-chat reset. */
    fun scopeOverride(context: Context, scope: String): String? =
        prefs(context).getString(SCOPE_PREFIX + keyPart(scope), null)

    /** Remove only one chat's local message-colour override. */
    fun resetScope(context: Context, scope: String) {
        if (scope.isBlank()) return
        prefs(context).edit().remove(SCOPE_PREFIX + keyPart(scope)).apply()
        touch()
    }

    /** true when a message is allowed by the optional reader colour filter. */
    fun allowsMessage(context: Context, messageColor: String?): Boolean {
        if (!readOnlySelectedColor(context)) return true
        return TextColorPalette.normalize(messageColor) == selectedReadColor(context)
    }

    fun directScope(chatId: String): String = "direct:$chatId"

    fun groupScope(groupId: String, topicId: String?, isChannel: Boolean = false): String {
        val kind = if (isChannel) "channel" else "group"
        return if (topicId.isNullOrBlank()) "$kind:$groupId" else "$kind:$groupId:topic:$topicId"
    }

    fun textColor(context: Context, scope: String): String {
        initialize(context)
        val exact = prefs(context).getString(SCOPE_PREFIX + keyPart(scope), null)
        val category = categoryFor(scope)
            ?.let { prefs(context).getString(SCOPE_PREFIX + keyPart(it), null) }
        return TextColorPalette.normalize(exact ?: category ?: ownTextColor(context))
    }

    /** Stable settings scopes used by the settings screen and by chat fallbacks. */
    fun categoryScope(category: String): String = "category:${category.lowercase()}"

    /** Empty/default removes the local override and returns to the global choice. */
    fun setTextColor(context: Context, scope: String, id: String?) {
        initialize(context)
        val value = TextColorPalette.normalize(id)
        val editor = prefs(context).edit()
        if (value == TextColorPalette.DEFAULT_ID) {
            editor.remove(SCOPE_PREFIX + keyPart(scope))
        } else {
            editor.putString(SCOPE_PREFIX + keyPart(scope), value)
        }
        editor.apply()
        touch()
    }

    private fun categoryFor(scope: String): String? = when {
        scope.startsWith("category:") -> null
        scope.startsWith("direct:") -> categoryScope("direct")
        scope.startsWith("channel:") && scope.contains(":topic:") -> categoryScope("topic")
        scope.startsWith("group:") && scope.contains(":topic:") -> categoryScope("topic")
        scope.startsWith("channel:") -> categoryScope("channel")
        scope.startsWith("group:") -> categoryScope("group")
        else -> null
    }

    fun peerNameColor(context: Context, peerId: String): String {
        if (peerId.isBlank()) return TextColorPalette.DEFAULT_ID
        val stored = peerKeyVariants(peerId)
            .asSequence()
            .mapNotNull { prefs(context).getString(PEER_NAME_PREFIX + keyPart(it), null) }
            .firstOrNull()
        return TextColorPalette.normalize(stored)
    }

    /** Learn the profile/name colour announced by a contact or group member. */
    fun rememberPeerNameColor(context: Context, peerId: String, id: String?) {
        if (peerId.isBlank() || id.isNullOrBlank()) return
        val value = TextColorPalette.normalize(id)
        val editor = prefs(context).edit()
        peerKeyVariants(peerId).forEach { key ->
            editor.putString(PEER_NAME_PREFIX + keyPart(key), value)
        }
        editor.apply()
        touch()
    }

    /** Transport and contact rows sometimes use `node=pk_…`; remember both spellings. */
    private fun peerKeyVariants(peerId: String): List<String> {
        val raw = peerId.trim()
        val canonical = when {
            raw.contains("node=pk_") -> "pk_" + raw.substringAfter("node=pk_").substringBefore('&')
            raw.startsWith("pk_") -> raw.substringBefore('|')
            else -> raw
        }
        return listOf(raw, canonical).distinct().filter { it.isNotBlank() }
    }

    private fun keyPart(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
}
