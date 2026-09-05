package com.vladimir.messenger.ui.screens.contacts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.data.referral.ReferralAttributionSender
import com.vladimir.messenger.data.referral.ReferralWire
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.util.InviteLinkParser
import com.vladimir.messenger.util.VerifiedReferralInviteLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddContactUiState(
    val inviteLink: String = "",
    val displayName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val contactAdded: Boolean = false,
    // Поиск по @никнейму в роевом реестре.
    val nickQuery: String = "",
    val nickResults: List<NicknameEntity> = emptyList(),
    val nickSearching: Boolean = false,
)

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val nicknameDao: NicknameDao,
    private val referralAttribution: ReferralAttributionSender,
    private val exchangePeerStore: com.vladimir.messenger.data.file.FileExchangePeerStore,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
) : ViewModel() {

    companion object {
        private const val TAG = "AddContactVM"
    }

    private val _uiState = MutableStateFlow(AddContactUiState())
    val uiState = _uiState.asStateFlow()

    /** Поиск по части @никнейма в роевом реестре (кто раньше - тот выше). */
    fun onNickQueryChanged(value: String) {
        val clean = value.trim().removePrefix("@")
        _uiState.update { it.copy(nickQuery = value, nickSearching = clean.isNotBlank()) }
        viewModelScope.launch {
            val found = if (clean.isBlank()) {
                emptyList()
            } else {
                try {
                    nicknameDao.search(clean.lowercase())
                } catch (e: Exception) {
                    Log.w(TAG, "nickname search failed", e)
                    emptyList()
                }
            }
            _uiState.update { it.copy(nickResults = found, nickSearching = false) }
        }
    }

    /** Добавить в контакты прямо из поиска по @никнейму. */
    fun onAddByNicknameClicked(entry: NicknameEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, contactAdded = false) }
            val result = contactRepository.addContact(
                displayName = entry.name,
                fingerprint = entry.ownerId,
                username = entry.name,
            )
            result
                .onSuccess { contact ->
                    chatRepository.createChat(entry.ownerId, contact.displayName)
                    _uiState.update { it.copy(isLoading = false, contactAdded = true) }
                }
                .onFailure { error ->
                    if (error.message == "Contact already exists") {
                        val existing = contactRepository.getContactByFingerprint(entry.ownerId)
                        if (existing != null) {
                            chatRepository.getOrCreateChat(entry.ownerId, existing.displayName)
                        }
                        _uiState.update { it.copy(isLoading = false, contactAdded = true) }
                    } else {
                        Log.e(TAG, "add by nickname failed", error)
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "Не удалось добавить")
                        }
                    }
                }
        }
    }

    fun onInviteLinkChanged(value: String) {
        _uiState.update { it.copy(inviteLink = value, error = null, contactAdded = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onDisplayNameChanged(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun onAddContactClicked() {
        val raw = _uiState.value.inviteLink.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(error = "Invite link is empty") }
            return
        }

        // Сначала общий разборщик ссылок: приложение само генерирует
        // p2pmessenger://add?node_id=..., а этот блок раньше понимал только
        // p2p://invite/, p2p://key/ и голый pk_, то есть QR контакта не срабатывал.
        val parsedInvite = InviteLinkParser.parse(raw)
        val fingerprint = parsedInvite?.nodeId ?: when {
            raw.contains("node=pk_") -> "pk_" + raw.substringAfter("node=pk_").substringBefore("&")
            raw.startsWith("p2p://invite/") -> raw.removePrefix("p2p://invite/").trim()
            raw.startsWith("p2p://key/") -> raw.removePrefix("p2p://key/").trim()
            raw.startsWith("pk_") -> raw.trim()
            else -> raw.trim()
        }

        if (fingerprint.isBlank()) {
            _uiState.update { it.copy(error = "Invalid invite link") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val displayName = _uiState.value.displayName.trim().takeIf { it.isNotBlank() }
                ?: parsedInvite?.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: "Contact ${fingerprint.takeLast(8)}"

            // 1. обавляем контакт
            val contactResult = contactRepository.addContact(
                displayName = displayName,
                fingerprint = fingerprint,
                username = parsedInvite?.username.orEmpty(),
            )

            contactResult
                .onSuccess { contact ->
                    Log.i(TAG, "Contact added: ${contact.displayName}")
                    // 2. Сразу создаём чат для этого контакта
                    val chat = chatRepository.createChat(fingerprint, contact.displayName)
                    Log.i(TAG, "Chat created: ${chat.id} for ${chat.contactName}")
                    // Контакт пришёл по пригласительной ссылке: запоминаем
                    // подписанный токен и сразу пробуем отправить пригласившему
                    // атрибуцию. Если транспорта нет, отправка повторится на
                    // первом же сообщении (см. ChatRepository.sendMessage).
                    rememberReferral(parsedInvite, fingerprint, chat.id)
                    requestReferralToken(parsedInvite, fingerprint)
                    _uiState.update { it.copy(isLoading = false, contactAdded = true, inviteLink = "", displayName = "") }
                }
                .onFailure { error ->
                    // сли контакт уже есть — ищем существующий чат
                    if (error.message == "Contact already exists") {
                        Log.i(TAG, "Contact already exists, finding existing chat")
                        // Всё равно регистрируем peer в Rust
                        try { RustBridge.connectViaInvite(raw) } catch (_: Exception) {}
                        // Пересканирование QR уже известного контакта = явное
                        // подтверждение личности. Только здесь снимаем
                        // закреплённый ключ: после переустановки у собеседника
                        // новый ключ, старый закреплён навсегда, и обмен
                        // ключами заклинивает - переписка идёт лишь в одну
                        // сторону. Сами, без участия человека, мы этого не
                        // делаем: так подмену ключа не отличить от переустановки.
                        runCatching { exchangePeerStore.forgetPin(fingerprint) }
                            .onSuccess { removed ->
                                if (removed) {
                                    com.vladimir.messenger.data.security.MessageSealer
                                        .forget(appContext, fingerprint)
                                    Log.i(TAG, "Exchange pin reset for rescanned contact")
                                }
                            }
                            .onFailure { Log.w(TAG, "Pin reset failed: ${it.message}") }
                        val existing = contactRepository.getContactByFingerprint(fingerprint)
                        if (existing != null) {
                            // роверяем есть ли уже чат, если нет — создаём
                            val existingChat = chatRepository.getOrCreateChat(fingerprint, existing.displayName)
                            rememberReferral(parsedInvite, fingerprint, existingChat.id)
                        }
                        _uiState.update { it.copy(isLoading = false, contactAdded = true, inviteLink = "", displayName = "") }
                    } else {
                        Log.e(TAG, "Failed to add contact", error)
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "Failed to add contact")
                        }
                    }
                }
        }
    }

    /**
     * Ссылка несла подписанный токен приглашения — проверяем подпись и
     * запоминаем приглашение. Без токена (обычная ссылка на контакт) функция
     * ничего не делает: начисление ранга возможно только по подписанному
     * приглашению.
     */
    private fun rememberReferral(
        parsedInvite: InviteLinkParser.Invite?,
        fingerprint: String,
        chatId: String,
    ) {
        val encoded = parsedInvite?.referralToken ?: return
        val token = ReferralWire.decode(encoded) ?: return
        val verified = VerifiedReferralInviteLink.verifyToken(token)
        if (verified == null) {
            Log.w(TAG, "invite token failed verification, attribution skipped")
            return
        }
        if (!verified.inviterNodeId.equals(fingerprint, ignoreCase = true)) {
            Log.w(TAG, "invite token does not match the added contact")
            return
        }
        if (referralAttribution.rememberInviter(fingerprint, verified.inviterNodeId, verified.token)) {
            referralAttribution.sendPending(chatId, fingerprint)
        }
    }

    /**
     * Короткая ссылка `apu://` не несёт подписанный токен - он занимал 427
     * символов из 589 и делал QR густой сеткой. Поэтому сразу после добавления
     * контакта просим токен у пригласившего по уже установленной связи: он
     * ответит подписью, и ранг начислится ровно как раньше.
     *
     * Для длинной ссылки с токеном запрос не нужен.
     */
    private fun requestReferralToken(
        parsedInvite: InviteLinkParser.Invite?,
        fingerprint: String,
    ) {
        if (!parsedInvite?.referralToken.isNullOrBlank()) return
        val me = ReferralWire.canonicalNodeId(RustBridge.nodeId()) ?: return
        if (me.equals(fingerprint, ignoreCase = true)) return
        val request = ReferralWire.buildTokenRequest(me) ?: return
        runCatching {
            RustBridge.sendMessage(
                java.util.UUID.randomUUID().toString(),
                "referral",
                fingerprint,
                request,
            )
        }.onFailure { Log.w(TAG, "token request failed: ${it.message}") }
    }
}
