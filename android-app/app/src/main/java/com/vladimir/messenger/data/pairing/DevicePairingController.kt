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

    /** Joiner: the user has compared the code and pressed confirm. */
    private var joinerConfirmed = false
    private var pendingGrant: ByteArray? = null
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
                DevicePairingWire.KIND_REQUEST -> onRequest(senderId, packet.bytes, now)
                DevicePairingWire.KIND_ANSWER -> onAnswer(packet.bytes, now)
                DevicePairingWire.KIND_GRANT -> onGrant(context, packet.bytes, now)
            }
        } catch (e: Exception) {
            // A packet that does not fit the current state is simply not for this device.
            Log.d(TAG, "pairing packet ignored: ${e.message}")
        }
        return true
    }

    private fun onRequest(senderId: String, request: ByteArray, now: Long) {
        if (!offerOpen.value) return
        val answer = primaryHandlePairingRequest(request, now)
        val joinerDevice = answer.deviceId
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
        if (current.phase != JoinerPhase.WAITING_ANSWER) return
        val sas = joinerHandlePairingAnswer(answer, now)
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
    }

    /** New device: start pairing from a scanned QR. Returns false if the QR is not a pairing code. */
    suspend fun startJoin(qrText: String): Boolean {
        val context = appContext ?: return false
        val offer = DevicePairingWire.parseQr(qrText) ?: return false
        val accountNode = RustBridge.nodeId() ?: return false
        val primaryDevice = pairingOfferPrimaryDeviceId(offer)
        val request = joinerStartPairing(
            offer,
            DeviceKeyStore.deviceId(context),
            DeviceKeyStore.devicePublicKey(context),
            System.currentTimeMillis(),
        )
        joinerConfirmed = false
        pendingGrant = null
        joinerAccountNode = accountNode
        joinerMutable.value = JoinerState(JoinerPhase.WAITING_ANSWER)
        val sent = RustBridge.sendPairingPayload(
            accountNode,
            primaryDevice,
            DevicePairingWire.encode(DevicePairingWire.KIND_REQUEST, request),
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
        primaryMutable.value = null
        closeOffer()
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
        joinerAccountNode = null
        joinerMutable.value = null
        offerOpen.value = false
        primaryMutable.value = null
    }

    /** Joiner: the screen was closed after success. */
    fun dismissJoiner() {
        if (joinerMutable.value?.phase == JoinerPhase.DONE || joinerMutable.value?.phase == JoinerPhase.FAILED) {
            joinerMutable.value = null
        }
    }

}
