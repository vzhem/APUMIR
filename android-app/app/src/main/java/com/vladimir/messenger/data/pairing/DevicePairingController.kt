package com.vladimir.messenger.data.pairing

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.DeviceKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.p2p_core.closePairingOffer
import uniffi.p2p_core.createPairingOffer
import uniffi.p2p_core.joinerAcceptPairingGrant
import uniffi.p2p_core.joinerCancelPairing
import uniffi.p2p_core.joinerHandlePairingAnswer
import uniffi.p2p_core.joinerStartPairing
import uniffi.p2p_core.pairingOfferPrimaryDeviceId
import uniffi.p2p_core.pairingOfferContactHints
import uniffi.p2p_core.primarySealPairingSync
import uniffi.p2p_core.joinerOpenPairingSync
import uniffi.p2p_core.recordPairingEndpoint
import uniffi.p2p_core.primaryConfirmPairing
import uniffi.p2p_core.primaryHandlePairingRequest
import uniffi.p2p_core.setLocalDeviceId

/**
 * Device pairing on the phone side (P3). The protocol is in the Rust core; this object moves the
 * packets and keeps the screen state. Nothing here decides trust: the core checks every MAC,
 * the code comparison is done by the user on both devices, and the certificate is verified.
 */
object DevicePairingController {
    private const val TAG = "DevicePairing"

    /** Primary side: a new device asked to join and the code to compare. */
    data class PrimaryRequest(
        val joinerDeviceId: ByteArray,
        val sas: String,
    )

    enum class JoinerPhase { WAITING_ANSWER, CONFIRM_CODE, WAITING_GRANT, DONE, FAILED }

    data class JoinerState(
        val phase: JoinerPhase,
        val sas: String? = null,
        val message: String? = null,
    )

    private val primaryMutable = MutableStateFlow<PrimaryRequest?>(null)
    val primary: StateFlow<PrimaryRequest?> = primaryMutable.asStateFlow()

    private val joinerMutable = MutableStateFlow<JoinerState?>(null)
    val joiner: StateFlow<JoinerState?> = joinerMutable.asStateFlow()

    private val offerOpen = MutableStateFlow(false)
    val isOfferOpen: StateFlow<Boolean> = offerOpen.asStateFlow()

    /** Granting side: the pairing finished here; automatic data sync is taking over. */
    private val primaryDoneMutable = MutableStateFlow(false)
    val primaryDone: StateFlow<Boolean> = primaryDoneMutable.asStateFlow()

    /** Joiner: the user has compared the code and pressed confirm. */
    private var joinerConfirmed = false
    private var pendingGrant: ByteArray? = null
    private var pendingSync: ByteArray? = null
    private var joinerAccountNode: String? = null

    @Volatile
    private var appContext: Context? = null

    /** Called once from Application.onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Give the core this installation's device key id. Must run before the engine starts: the
     * core publishes it in mDNS when discovery starts. Safe to call again.
     */
    fun prepareLocal() {
        val context = appContext ?: return
        runCatching { setLocalDeviceId(DeviceKeyStore.deviceId(context)) }
            .onFailure { Log.w(TAG, "local device id not set: ${it.message}") }
    }

    /** Primary: show a QR that a new device can scan. Returns the QR text. */
    fun openOffer(): String {
        primaryDoneMutable.value = false
        prepareLocal()
        val offer = createPairingOffer(System.currentTimeMillis())
        offerOpen.value = true
        return DevicePairingWire.qrPayload(offer)
    }

    fun closeOffer() {
        runCatching { closePairingOffer() }
        offerOpen.value = false
        primaryMutable.value = null
    }

    /** Route one incoming text. Returns true when the text was a pairing packet. */
    suspend fun routeIncoming(context: Context, senderId: String, text: String): Boolean {
        val packet = DevicePairingWire.decode(text)
            ?: return DevicePairingWire.isPacket(text).also {
                if (it) Log.w(TAG, "malformed pairing packet from $senderId, dropped")
            }
        val now = System.currentTimeMillis()
        try {
            when (packet.kind) {
                DevicePairingWire.KIND_REQUEST -> onRequest(context, senderId, packet.bytes, now)
                DevicePairingWire.KIND_ANSWER -> onAnswer(packet.bytes, now)
                DevicePairingWire.KIND_GRANT -> onGrant(context, packet.bytes, now)
                DevicePairingWire.KIND_SYNC -> onSync(context, packet.bytes)
            }
        } catch (e: Exception) {
            // A packet that does not fit the current state is simply not for this device.
            Log.d(TAG, "pairing packet ignored: ${e.message}")
        }
        return true
    }

    private fun onRequest(context: Context, senderId: String, request: ByteArray, now: Long) {
        if (!offerOpen.value) return
        val answer = primaryHandlePairingRequest(request, now)
        val joinerDevice = answer.deviceId
        // Эхо собственного запроса (брокер вернул наш же пакет, а у нас открыт
        // свой QR): подключать телефон к самому себе нельзя.
        if (joinerDevice.contentEquals(DeviceKeyStore.deviceId(context))) {
            Log.d(TAG, "pairing request from own device ignored")
            return
        }
        val sent = RustBridge.sendPairingPayload(
            senderId,
            joinerDevice,
            DevicePairingWire.encode(DevicePairingWire.KIND_ANSWER, answer.answer),
        )
        if (!sent) Log.w(TAG, "pairing answer was not delivered")
        primaryMutable.value = PrimaryRequest(joinerDeviceId = joinerDevice, sas = answer.sas)
    }

    private fun onAnswer(answer: ByteArray, now: Long) {
        val current = joinerMutable.value ?: return
        // With a v2 QR the code is already on screen (CONFIRM_CODE); the arriving answer is
        // still verified against the transcript and simply confirms the same code.
        if (current.phase != JoinerPhase.WAITING_ANSWER && current.phase != JoinerPhase.CONFIRM_CODE) return
        val sas = joinerHandlePairingAnswer(answer, now)
        if (pendingGrant != null && joinerConfirmed) return
        joinerMutable.value = JoinerState(JoinerPhase.CONFIRM_CODE, sas = sas)
    }

    private fun onGrant(context: Context, grant: ByteArray, now: Long) {
        val current = joinerMutable.value ?: return
        if (current.phase != JoinerPhase.CONFIRM_CODE && current.phase != JoinerPhase.WAITING_GRANT) return
        if (!joinerConfirmed) {
            // The primary confirmed first. Keep the grant until this device confirms its code.
            pendingGrant = grant
            joinerMutable.value = current.copy(phase = JoinerPhase.CONFIRM_CODE)
            return
        }
        acceptGrant(context, grant, now)
    }

    private fun acceptGrant(context: Context, grant: ByteArray, now: Long) {
        val certificate = joinerAcceptPairingGrant(grant, now)
        if (!DeviceKeyStore.storeCertificate(context, certificate)) {
            joinerMutable.value = JoinerState(JoinerPhase.FAILED, message = "certificate not saved")
            return
        }
        pendingGrant = null
        joinerAccountNode = null
        joinerMutable.value = JoinerState(JoinerPhase.DONE)
        // The sync bootstrap may have arrived before the grant: open it now that
        // the pairing keys exist on this side too.
        pendingSync?.let { packet ->
            pendingSync = null
            onSync(context, packet)
        }
    }

    /** New device: start pairing from a scanned QR. Returns false if the QR is not a pairing code. */
    suspend fun startJoin(qrText: String): Boolean {
        val context = appContext ?: return false
        val offer = DevicePairingWire.parseQr(qrText) ?: return false
        val accountNode = RustBridge.nodeId() ?: return false
        val primaryDevice = pairingOfferPrimaryDeviceId(offer)
        // The QR carries the other phone's addresses: remember them as the direct route to
        // that exact device, so the request below does not wait for LAN discovery.
        runCatching { pairingOfferContactHints(offer) }
            .getOrDefault(emptyList())
            .forEach { hint -> runCatching { recordPairingEndpoint(primaryDevice, hint) } }
        val started = joinerStartPairing(
            offer,
            DeviceKeyStore.deviceId(context),
            DeviceKeyStore.devicePublicKey(context),
            System.currentTimeMillis(),
        )
        joinerConfirmed = false
        pendingGrant = null
        pendingSync = null
        joinerAccountNode = accountNode
        // A v2 offer already contained the whole handshake: the comparison code is known
        // right now, without waiting for the other phone's answer.
        joinerMutable.value = if (started.sas != null) {
            JoinerState(JoinerPhase.CONFIRM_CODE, sas = started.sas)
        } else {
            JoinerState(JoinerPhase.WAITING_ANSWER)
        }
        val sent = RustBridge.sendPairingPayload(
            accountNode,
            primaryDevice,
            DevicePairingWire.encode(DevicePairingWire.KIND_REQUEST, started.request),
        )
        if (!sent) {
            joinerMutable.value = JoinerState(JoinerPhase.FAILED, message = "request not delivered")
        }
        return true
    }

    /** New device: the user says the codes match. */
    fun confirmJoinerCode() {
        val context = appContext ?: return
        val current = joinerMutable.value ?: return
        if (current.phase != JoinerPhase.CONFIRM_CODE) return
        joinerConfirmed = true
        val grant = pendingGrant
        if (grant != null) {
            acceptGrant(context, grant, System.currentTimeMillis())
        } else {
            joinerMutable.value = current.copy(phase = JoinerPhase.WAITING_GRANT)
        }
    }

    /** Primary: the user says the codes match. Issues the certificate and sends it. */
    fun confirmPrimary() {
        val request = primaryMutable.value ?: return
        val accountNode = RustBridge.nodeId() ?: return
        val granted = primaryConfirmPairing(request.joinerDeviceId, System.currentTimeMillis())
        val sent = RustBridge.sendPairingPayload(
            accountNode,
            request.joinerDeviceId,
            DevicePairingWire.encode(DevicePairingWire.KIND_GRANT, granted.grant),
        )
        if (!sent) Log.w(TAG, "pairing grant was not delivered")
        sendSyncBootstrap(accountNode, request.joinerDeviceId)
        primaryMutable.value = null
        primaryDoneMutable.value = true
        closeOffer()
    }

    /**
     * After the certificate: hand the new device our mirror room token, sealed with the
     * pairing keys. With the same token both phones enter the same mirror room, and the
     * mirror channel then copies everything by itself: chat history (catch-up from the
     * partner's newest message), profile, contacts, drafts, pins, read marks and files.
     * No user action and no password are involved.
     */
    private fun sendSyncBootstrap(accountNode: String, joinerDeviceId: ByteArray) {
        val context = appContext ?: return
        val token = com.vladimir.messenger.data.mirror.MirrorRoomToken.load(context)
            ?: com.vladimir.messenger.data.mirror.MirrorRoomToken.create(context)
            ?: return
        // Our own mirror channel may predate this token: make it adopt the same one.
        com.vladimir.messenger.data.mirror.MirrorHub.refreshRoomToken()
        val sealed = primarySealPairingSync(joinerDeviceId, "${token.value}:${token.createdAtMs}")
        if (sealed.isEmpty()) return
        val sent = RustBridge.sendPairingPayload(
            accountNode,
            joinerDeviceId,
            DevicePairingWire.encode(DevicePairingWire.KIND_SYNC, sealed),
        )
        if (!sent) Log.w(TAG, "pairing sync bootstrap was not delivered")
    }

    /** Paired device: the granting side sent its mirror room token. Adopt it and reconnect. */
    private fun onSync(context: Context, packet: ByteArray) {
        val text = joinerOpenPairingSync(packet)
        if (text.isBlank()) {
            // Too early: the grant has not been accepted yet, so there are no pairing
            // keys to open this with. Keep it and retry right after the grant lands.
            if (joinerMutable.value?.phase != JoinerPhase.DONE) pendingSync = packet
            return
        }
        val value = text.substringBefore(':')
        val at = text.substringAfter(':', "").toLongOrNull() ?: System.currentTimeMillis()
        if (!value.matches(Regex("^[0-9a-f]{32}$"))) return
        val theirs = com.vladimir.messenger.data.mirror.MirrorRoomToken.RoomToken(value, at)
        // Пэйринг - прямое указание «мы теперь пара»: маркер показывающего телефона
        // главный без правила «старший побеждает». Иначе пустой телефон, только что
        // создавший себе свежий маркер, и полный телефон со старым маркером ушли бы
        // в разные комнаты и никогда не встретились - синхронизации бы не было.
        com.vladimir.messenger.data.mirror.MirrorRoomToken.save(context, theirs, sync = true)
        // Same room from now on: the channel reconnects there and catch-up starts on its own.
        com.vladimir.messenger.data.mirror.MirrorHub.refreshRoomToken()
        Log.i(TAG, "pairing sync bootstrap adopted; mirror room shared")
    }

    fun rejectPrimary() {
        primaryMutable.value = null
        closeOffer()
    }

    /** Either side: stop everything and forget the pending state. */
    fun cancel() {
        runCatching { joinerCancelPairing() }
        runCatching { closePairingOffer() }
        joinerConfirmed = false
        pendingGrant = null
        pendingSync = null
        joinerAccountNode = null
        joinerMutable.value = null
        offerOpen.value = false
        primaryMutable.value = null
        primaryDoneMutable.value = false
    }

    /** Joiner: the screen was closed after success. */
    fun dismissJoiner() {
        if (joinerMutable.value?.phase == JoinerPhase.DONE || joinerMutable.value?.phase == JoinerPhase.FAILED) {
            joinerMutable.value = null
        }
    }

}
