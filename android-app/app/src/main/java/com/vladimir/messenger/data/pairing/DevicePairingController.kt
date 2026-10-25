package com.vladimir.messenger.data.pairing

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.DeviceKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
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
        /** Short human-checkable id of the device asking to join, e.g. "A1B2-C3D4". */
        val deviceShort: String,
        /** Non-null when the last local confirmation attempt failed and can be retried. */
        val error: String? = null,
    )

    enum class JoinerPhase { WAITING_CODE, WAITING_ANSWER, CONFIRM_CODE, WAITING_GRANT, DONE, FAILED }

    data class JoinerState(
        val phase: JoinerPhase,
        val sas: String? = null,
        val message: String? = null,
        /** Short id of THIS device, so the user can check it is the one being connected. */
        val deviceShort: String = "",
    )

    private val primaryMutable = MutableStateFlow<PrimaryRequest?>(null)
    val primary: StateFlow<PrimaryRequest?> = primaryMutable.asStateFlow()

    private val joinerMutable = MutableStateFlow<JoinerState?>(null)
    val joiner: StateFlow<JoinerState?> = joinerMutable.asStateFlow()

    private val offerOpen = MutableStateFlow(false)
    val isOfferOpen: StateFlow<Boolean> = offerOpen.asStateFlow()

    /** Short code used only to find the full QR offer when a camera is unavailable. */
    private val offerCodeMutable = MutableStateFlow<String?>(null)
    val offerCode: StateFlow<String?> = offerCodeMutable.asStateFlow()

    /** Granting side: the pairing finished here; automatic data sync is taking over. */
    private val primaryDoneMutable = MutableStateFlow(false)
    val primaryDone: StateFlow<Boolean> = primaryDoneMutable.asStateFlow()

    // Incoming packets and taps arrive on different coroutines. Without one small critical
    // section a late answer could put the joiner back on the code screen just after the user
    // confirmed it, making the button look dead. Native calls stay off the main thread; this
    // lock only serializes the pairing state machine.
    private val stateLock = Any()

    private val retryScope = CoroutineScope(SupervisorJob() + CoroutineDispatchers.IO)

    /** Joiner: the user has compared the code and pressed confirm. */
    private var joinerConfirmed = false
    @Volatile private var answerReceived = false
    private var pendingRequestTarget: Pair<String, ByteArray>? = null
    private var pendingRequestPacket: String? = null
    /** Granting side: keep the authenticated answer while the final packets are retried. */
    private var lastAnswerTarget: Pair<String, ByteArray>? = null
    private var lastAnswerPacket: String? = null
    /** Granting side: last grant+sync packets, so they can be re-sent on demand. */
    private var lastGrantTarget: Pair<String, ByteArray>? = null
    private var lastGrantPackets: List<String> = emptyList()
    private var pendingGrant: ByteArray? = null
    private var pendingSync: ByteArray? = null
    private var joinerAccountNode: String? = null
    /** Code entered on the joiner; a retained broker offer completes this rendezvous. */
    private var pendingManualCode: String? = null
    private var lastRendezvous: DevicePairingWire.RendezvousOffer? = null
    private var lastRendezvousAtMs: Long = 0L
    private var offerBroadcastJob: kotlinx.coroutines.Job? = null

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
        val qrText = synchronized(stateLock) {
            primaryDoneMutable.value = false
            prepareLocal()
            val offer = createPairingOffer(System.currentTimeMillis())
            val qr = DevicePairingWire.qrPayload(offer)
            offerOpen.value = true
            offerCodeMutable.value = DevicePairingWire.pairingCode(qr)
            qr
        }
        val code = offerCodeMutable.value
        val sender = RustBridge.nodeId()
        val payload = if (code != null && sender != null) {
            DevicePairingWire.rendezvousPayload(sender, code, qrText)
        } else {
            null
        }
        if (code != null && payload != null) {
            Log.i(TAG, "pairing rendezvous offer prepared; retained broadcast scheduled")
            startOfferBroadcast(code, payload)
        } else {
            Log.w(TAG, "pairing rendezvous offer could not be prepared")
        }
        return qrText
    }

    fun closeOffer() {
        val code = synchronized(stateLock) {
            val oldCode = offerCodeMutable.value
            offerBroadcastJob?.cancel()
            offerBroadcastJob = null
            runCatching { closePairingOffer() }
            offerOpen.value = false
            offerCodeMutable.value = null
            primaryMutable.value = null
            oldCode
        }
        // Remove the retained rendezvous offer; a later user must not enter an old code.
        if (code != null) {
            retryScope.launch { RustBridge.publishPairingCode(code, "") }
        }
    }

    private fun startOfferBroadcast(code: String, payload: String) {
        offerBroadcastJob?.cancel()
        offerBroadcastJob = retryScope.launch {
            for (waitMs in listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 180_000L, 240_000L)) {
                if (waitMs > 0) delay(waitMs)
                val active = synchronized(stateLock) {
                    offerOpen.value && offerCodeMutable.value == code
                }
                if (!active) return@launch
                val sent = RustBridge.publishPairingCode(code, payload)
                if (sent) {
                    Log.i(TAG, "pairing rendezvous retained publish queued")
                } else {
                    Log.w(TAG, "pairing rendezvous retained publish failed; retry remains scheduled")
                }
            }
        }
    }

    /** Route one incoming text. Returns true when the text was a pairing packet. */
    suspend fun routeIncoming(context: Context, senderId: String, text: String): Boolean {
        if (DevicePairingWire.isRendezvousPacket(text)) {
            val rendezvous = DevicePairingWire.parseRendezvous(text)
            synchronized(stateLock) {
                if (rendezvous == null) {
                    Log.w(TAG, "malformed pairing rendezvous from $senderId, dropped")
                } else {
                    Log.i(TAG, "pairing rendezvous offer received from broker")
                    onRendezvous(rendezvous)
                }
            }
            return true
        }
        val packet = DevicePairingWire.decode(text)
            ?: return DevicePairingWire.isPacket(text).also {
                if (it) Log.w(TAG, "malformed pairing packet from $senderId, dropped")
            }
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            try {
                when (packet.kind) {
                    DevicePairingWire.KIND_REQUEST -> onRequest(context, senderId, packet.bytes, now)
                    DevicePairingWire.KIND_ANSWER -> onAnswer(context, packet.bytes, now)
                    DevicePairingWire.KIND_GRANT -> onGrant(context, packet.bytes, now)
                    DevicePairingWire.KIND_SYNC -> onSync(context, packet.bytes)
                }
            } catch (e: Exception) {
                // Keep identity, certificate, transcript and SAS checks in the Rust pairing
                // functions. This log distinguishes an authenticated rejection from a missing
                // rendezvous without exposing keys or packet contents.
                Log.w(TAG, "pairing packet rejected by authenticated state: ${e.message ?: "unknown"}")
            }
        }
        return true
    }

    /** A user-entered code found the retained offer. Continue through the normal QR path. */
    private fun onRendezvous(offer: DevicePairingWire.RendezvousOffer) {
        lastRendezvous = offer
        lastRendezvousAtMs = System.currentTimeMillis()
        if (pendingManualCode != offer.code) {
            Log.d(TAG, "pairing rendezvous cached; code is not currently requested")
            return
        }
        Log.i(TAG, "pairing rendezvous code matched; starting secure QR flow")
        pendingManualCode = null
        if (!startJoinInternal(offer.qrText)) {
            Log.w(TAG, "pairing rendezvous offer could not start")
        }
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
        val answerPacket = DevicePairingWire.encode(DevicePairingWire.KIND_ANSWER, answer.answer)
        // Keep the answer until the grant flow finishes. A v2 QR can show the SAS before this
        // packet is received, so the primary may be confirmed and the offer closed while the
        // joiner still needs one more authenticated answer attempt to derive its root.
        lastAnswerTarget = senderId to joinerDevice
        lastAnswerPacket = answerPacket
        val sent = RustBridge.sendPairingPayload(senderId, joinerDevice, answerPacket)
        if (!sent) {
            Log.w(TAG, "pairing answer was not delivered; retry remains available")
        } else {
            Log.i(TAG, "pairing answer delivery accepted by transport")
        }
        primaryMutable.value = PrimaryRequest(joinerDeviceId = joinerDevice, sas = answer.sas, deviceShort = shortDeviceId(joinerDevice))
    }

    private fun onAnswer(context: Context, answer: ByteArray, now: Long) {
        val current = joinerMutable.value ?: return
        // A v2 QR exposes the SAS before the answer has crossed the network. The user can
        // therefore confirm while the joiner is already in WAITING_GRANT. The answer must still
        // be authenticated in Rust, but it must not be discarded just because the user tapped
        // first.
        if (current.phase != JoinerPhase.WAITING_ANSWER &&
            current.phase != JoinerPhase.CONFIRM_CODE &&
            current.phase != JoinerPhase.WAITING_GRANT
        ) {
            Log.d(TAG, "pairing answer ignored in phase=${current.phase}")
            return
        }
        val sas = joinerHandlePairingAnswer(answer, now)
        answerReceived = true
        Log.i(TAG, "pairing answer authenticated; sasReady=true confirmed=$joinerConfirmed")
        val grant = pendingGrant
        if (joinerConfirmed && grant != null) {
            // The grant may have arrived before the answer. Keep it until the transcript/root is
            // ready, then run the normal certificate and identity checks exactly once here.
            Log.i(TAG, "pairing answer completed after confirmation; accepting buffered grant")
            acceptGrant(context, grant, now)
        } else if (joinerConfirmed) {
            joinerMutable.value = current.copy(
                phase = JoinerPhase.WAITING_GRANT,
                sas = sas,
                message = null,
            )
        } else {
            joinerMutable.value = current.copy(
                phase = JoinerPhase.CONFIRM_CODE,
                sas = sas,
                message = null,
            )
        }
    }

    private fun onGrant(context: Context, grant: ByteArray, now: Long) {
        val current = joinerMutable.value ?: return
        if (current.phase != JoinerPhase.CONFIRM_CODE && current.phase != JoinerPhase.WAITING_GRANT) {
            Log.d(TAG, "pairing grant ignored in phase=${current.phase}")
            return
        }
        if (!answerReceived) {
            // The primary may confirm before the answer arrives. Buffer the authenticated-wire
            // packet and let onAnswer establish the root before accepting the certificate.
            pendingGrant = grant
            val phase = if (joinerConfirmed) JoinerPhase.WAITING_GRANT else JoinerPhase.CONFIRM_CODE
            Log.i(TAG, "pairing grant buffered; waiting for authenticated answer confirmed=$joinerConfirmed")
            joinerMutable.value = current.copy(phase = phase)
            return
        }
        if (!joinerConfirmed) {
            // The primary confirmed first. Keep the grant until this device confirms its code.
            pendingGrant = grant
            Log.i(TAG, "pairing grant buffered; waiting for local SAS confirmation")
            joinerMutable.value = current.copy(phase = JoinerPhase.CONFIRM_CODE)
            return
        }
        Log.i(TAG, "pairing grant received after both confirmations; accepting")
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
        Log.i(TAG, "pairing certificate authenticated and stored; synchronization bootstrap pending")
        joinerMutable.value = JoinerState(JoinerPhase.DONE)
        // The sync bootstrap may have arrived before the grant: open it now that
        // the pairing keys exist on this side too.
        pendingSync?.let { packet ->
            pendingSync = null
            onSync(context, packet)
        } ?: com.vladimir.messenger.data.mirror.MirrorHub.startInitialSync()
    }

    /** New device: start pairing from a scanned or rendezvous-delivered QR. */
    suspend fun startJoin(qrText: String): Boolean = startJoinInternal(qrText)

    private fun startJoinInternal(qrText: String): Boolean {
        val context = appContext ?: run {
            Log.w(TAG, "pairing offer rejected: application context unavailable")
            return false
        }
        val offer = DevicePairingWire.parseQr(qrText) ?: run {
            Log.w(TAG, "pairing offer rejected: malformed QR envelope")
            return false
        }
        val accountNode = RustBridge.nodeId() ?: run {
            Log.w(TAG, "pairing offer rejected: account node unavailable")
            return false
        }
        return synchronized(stateLock) {
            pendingManualCode = null
            val primaryDevice = pairingOfferPrimaryDeviceId(offer)
            // The QR carries the other phone's addresses: remember them as the direct route to
            // that exact device, so the request below does not wait for LAN discovery.
            runCatching { pairingOfferContactHints(offer) }
                .getOrDefault(emptyList())
                .forEach { hint -> runCatching { recordPairingEndpoint(primaryDevice, hint) } }
            val started = try {
                joinerStartPairing(
                    offer,
                    DeviceKeyStore.deviceId(context),
                    DeviceKeyStore.devicePublicKey(context),
                    System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "pairing offer rejected by identity/expiry validation: ${e.message ?: "unknown"}")
                return@synchronized false
            }
            joinerConfirmed = false
            answerReceived = false
            pendingGrant = null
            pendingSync = null
            joinerAccountNode = accountNode
            // A v2 offer already contained the whole handshake: the comparison code is known
            // right now, without waiting for the other phone's answer.
            val myShort = shortDeviceId(DeviceKeyStore.deviceId(context))
            joinerMutable.value = if (started.sas != null) {
                JoinerState(JoinerPhase.CONFIRM_CODE, sas = started.sas, deviceShort = myShort)
            } else {
                JoinerState(JoinerPhase.WAITING_ANSWER, deviceShort = myShort)
            }
            val requestPacket = DevicePairingWire.encode(DevicePairingWire.KIND_REQUEST, started.request)
            pendingRequestTarget = accountNode to primaryDevice
            pendingRequestPacket = requestPacket
            val sent = RustBridge.sendPairingPayload(
                accountNode,
                primaryDevice,
                requestPacket,
            )
            // A transient false means only that neither transport was ready at this instant.
            // Keep the code card alive; the retry schedule and the manual retry button continue.
            if (!sent) {
                Log.w(TAG, "pairing request queued for retry")
            } else {
                Log.i(TAG, "pairing request delivery accepted by transport")
            }
            scheduleRequestRetries()
            true
        }
    }

    /** Start from the six-digit rendezvous code shown beside the QR on the other phone. */
    fun startJoinByCode(code: String): Boolean {
        val normalized = DevicePairingWire.normalizePairingCode(code) ?: return false
        val context = appContext ?: return false
        Log.i(TAG, "pairing rendezvous code entered; waiting for retained offer")
        val listenerStarted = RustBridge.listenPairingCode(normalized)
        if (listenerStarted) {
            Log.i(TAG, "pairing rendezvous listener started")
        } else {
            Log.w(TAG, "pairing rendezvous listener could not start; persistent MQTT will be used")
        }
        return synchronized(stateLock) {
            runCatching { joinerCancelPairing() }
            pendingManualCode = normalized
            joinerConfirmed = false
            answerReceived = false
            pendingGrant = null
            pendingSync = null
            pendingRequestTarget = null
            pendingRequestPacket = null
            joinerAccountNode = null
            joinerMutable.value = JoinerState(
                phase = JoinerPhase.WAITING_CODE,
                deviceShort = shortDeviceId(DeviceKeyStore.deviceId(context)),
            )
            val cached = lastRendezvous
                ?.takeIf { it.code == normalized && System.currentTimeMillis() - lastRendezvousAtMs < 15 * 60_000L }
            if (cached != null) {
                pendingManualCode = null
                startJoinInternal(cached.qrText)
            } else {
                true
            }
        }
    }

    /** New device: the user says the codes match. */
    fun confirmJoinerCode(): Boolean = synchronized(stateLock) {
        val context = appContext ?: run {
            Log.w(TAG, "cannot confirm joiner code: application context is not attached")
            return@synchronized false
        }
        val current = joinerMutable.value ?: return@synchronized false
        if (current.phase != JoinerPhase.CONFIRM_CODE) return@synchronized false
        return@synchronized try {
            joinerConfirmed = true
            val grant = pendingGrant
            if (grant != null && answerReceived) {
                Log.i(TAG, "joiner SAS confirmed; accepting buffered grant")
                acceptGrant(context, grant, System.currentTimeMillis())
            } else {
                // A v2 QR lets the user confirm before the authenticated answer arrives. Do not
                // call the Rust grant opener until that answer has established the transcript.
                Log.i(TAG, "joiner SAS confirmed; waiting for answer=${!answerReceived}")
                joinerMutable.value = current.copy(phase = JoinerPhase.WAITING_GRANT, message = null)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "joiner confirmation failed", e)
            joinerMutable.value = current.copy(
                phase = JoinerPhase.FAILED,
                message = e.message ?: "confirmation failed",
            )
            false
        }
    }

    /** Primary: the user says the codes match. Issues the certificate and sends it. */
    fun confirmPrimary(): Boolean = synchronized(stateLock) {
        val request = primaryMutable.value ?: return@synchronized false
        val accountNode = RustBridge.nodeId()
        if (accountNode.isNullOrBlank()) {
            Log.w(TAG, "cannot confirm primary pairing: account node is unavailable")
            primaryMutable.value = request.copy(error = "account node unavailable")
            return@synchronized false
        }
        return@synchronized try {
            // Clear an earlier transient error before retrying the same request.
            primaryMutable.value = request.copy(error = null)
            val granted = primaryConfirmPairing(request.joinerDeviceId, System.currentTimeMillis())
            val grantPacket = DevicePairingWire.encode(DevicePairingWire.KIND_GRANT, granted.grant)
            val sent = RustBridge.sendPairingPayload(accountNode, request.joinerDeviceId, grantPacket)
            if (!sent) Log.w(TAG, "pairing grant was not delivered")
            val syncPacket = sendSyncBootstrap(accountNode, request.joinerDeviceId)
            // The primary also enters the same observable lifecycle. This is
            // not left to a later hello/heartbeat, so both screens show whether
            // the initial transfer actually ran.
            com.vladimir.messenger.data.mirror.MirrorHub.startInitialSync()
            if (syncPacket == null) {
                com.vladimir.messenger.data.mirror.MirrorHub.initialSyncFailed(
                    "Не удалось передать настройки канала начальной синхронизации",
                )
            }
            // The new device may miss the grant (same delivery risks as the request):
            // keep both packets and repeat them on a schedule and on demand.
            lastGrantTarget = accountNode to request.joinerDeviceId
            lastGrantPackets = listOfNotNull(grantPacket, syncPacket)
            retryScope.launch {
                // Answer first, then grant+sync. The joiner may have confirmed from the v2 QR
                // before it received the answer, so both stages remain independently retryable.
                for (waitMs in listOf(2_000L, 6_000L, 20_000L)) {
                    delay(waitMs)
                    if (!primaryDoneMutable.value) return@launch
                    resendAnswer()
                    resendGrant()
                }
            }
            primaryMutable.value = null
            primaryDoneMutable.value = true
            closeOffer()
            true
        } catch (e: Exception) {
            Log.w(TAG, "primary confirmation failed", e)
            primaryMutable.value = request.copy(error = e.message ?: "confirmation failed")
            false
        }
    }

    /**
     * After the certificate: hand the new device our mirror room token, sealed with the
     * pairing keys. With the same token both phones enter the same mirror room, and the
     * mirror channel then copies everything by itself: chat history (catch-up from the
     * partner's newest message), profile, contacts, drafts, pins, read marks and files.
     * No user action and no password are involved.
     */
    private fun sendSyncBootstrap(accountNode: String, joinerDeviceId: ByteArray): String? {
        val context = appContext ?: return null
        val token = com.vladimir.messenger.data.mirror.MirrorRoomToken.load(context)
            ?: com.vladimir.messenger.data.mirror.MirrorRoomToken.create(context)
            ?: return null
        // A paired phone has a different device/file-exchange key. Give both
        // mirror channels a dedicated symmetric frame key instead of copying
        // the primary device key or signing identity.
        val syncKey = com.vladimir.messenger.data.mirror.MirrorRoomToken.loadSyncKey(context)
            ?: com.vladimir.messenger.data.mirror.MirrorRoomToken.createSyncKey(context)
            ?: return null
        // Our own mirror channel may predate this token: make it adopt the same one.
        com.vladimir.messenger.data.mirror.MirrorHub.refreshRoomToken()
        val bootstrap = JSONObject()
            .put("v", 2)
            .put("room", token.value)
            .put("at", token.createdAtMs)
            .put("key", Base64.encodeToString(syncKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .toString()
        val sealed = primarySealPairingSync(joinerDeviceId, bootstrap)
        if (sealed.isEmpty()) return null
        val packet = DevicePairingWire.encode(DevicePairingWire.KIND_SYNC, sealed)
        val sent = RustBridge.sendPairingPayload(accountNode, joinerDeviceId, packet)
        if (!sent) Log.w(TAG, "pairing sync bootstrap was not delivered")
        return packet
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
        val bootstrap = runCatching { JSONObject(text) }.getOrNull()
        val value: String
        val at: Long
        val syncKey: ByteArray?
        if (bootstrap != null && bootstrap.optInt("v", 0) >= 2) {
            value = bootstrap.optString("room")
            at = bootstrap.optLong("at", 0L)
            syncKey = runCatching {
                Base64.decode(
                    bootstrap.optString("key"),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            }.getOrNull()
        } else {
            // Accept the original token-only bootstrap from an older primary;
            // those peers continue with their legacy MessageSealer channel.
            value = text.substringBefore(':')
            at = text.substringAfter(':', "").toLongOrNull() ?: System.currentTimeMillis()
            syncKey = null
        }
        if (!value.matches(Regex("^[0-9a-f]{32}$"))) {
            com.vladimir.messenger.data.mirror.MirrorHub.initialSyncFailed(
                "Синхронизационный пакет содержит неверный маркер комнаты",
            )
            return
        }
        if (bootstrap != null && bootstrap.optInt("v", 0) >= 2 &&
            (syncKey == null || syncKey.size != 32)
        ) {
            Log.w(TAG, "pairing sync bootstrap has no valid mirror frame key")
            com.vladimir.messenger.data.mirror.MirrorHub.initialSyncFailed(
                "Синхронизационный пакет не содержит общего ключа кадров",
            )
            return
        }
        val theirs = com.vladimir.messenger.data.mirror.MirrorRoomToken.RoomToken(
            value,
            at.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        // Пэйринг - прямое указание «мы теперь пара»: маркер показывающего телефона
        // главный без правила «старший побеждает». Иначе пустой телефон, только что
        // создавший себе свежий маркер, и полный телефон со старым маркером ушли бы
        // в разные комнаты и никогда не встретились - синхронизации бы не было.
        val roomSaved = com.vladimir.messenger.data.mirror.MirrorRoomToken.save(context, theirs, sync = true)
        val keySaved = syncKey == null ||
            com.vladimir.messenger.data.mirror.MirrorRoomToken.saveSyncKey(context, syncKey, sync = true)
        if (!roomSaved || !keySaved) {
            Log.w(TAG, "pairing sync bootstrap could not be persisted")
            return
        }
        // Same room from now on, and explicitly start the first catch-up now;
        // relying only on a later service heartbeat was the reason the UI could
        // say "connected" while no initial transfer had begun.
        com.vladimir.messenger.data.mirror.MirrorHub.refreshRoomToken()
        com.vladimir.messenger.data.mirror.MirrorHub.startInitialSync()
        Log.i(TAG, "pairing sync bootstrap adopted; mirror room and frame key shared; initial sync started")
    }

    fun rejectPrimary() {
        synchronized(stateLock) {
            primaryMutable.value = null
            closeOffer()
        }
    }

    /** Either side: stop everything and forget the pending state. */
    fun cancel() {
        val code = synchronized(stateLock) {
            val oldCode = offerCodeMutable.value
            offerBroadcastJob?.cancel()
            offerBroadcastJob = null
            runCatching { joinerCancelPairing() }
            runCatching { closePairingOffer() }
            joinerConfirmed = false
            answerReceived = false
            pendingRequestTarget = null
            pendingRequestPacket = null
            lastAnswerTarget = null
            lastAnswerPacket = null
            lastGrantTarget = null
            lastGrantPackets = emptyList()
            pendingGrant = null
            pendingSync = null
            joinerAccountNode = null
            pendingManualCode = null
            lastRendezvous = null
            lastRendezvousAtMs = 0L
            joinerMutable.value = null
            offerOpen.value = false
            offerCodeMutable.value = null
            primaryMutable.value = null
            primaryDoneMutable.value = false
            oldCode
        }
        if (code != null) retryScope.launch { RustBridge.publishPairingCode(code, "") }
    }

    /**
     * The request may die on the way (closed NAT address, broker hiccup). Re-send the
     * very same request packet a few times until the other phone's answer comes back;
     * duplicates are harmless because the granting side replaces its own session.
     */
    private fun scheduleRequestRetries() {
        retryScope.launch {
            // The first broker/direct discovery can take longer than the original 40 seconds.
            // Keep the same request for almost the whole 15-minute offer lifetime.
            for (waitMs in listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 180_000L, 240_000L)) {
                delay(waitMs)
                val shouldStop = synchronized(stateLock) { answerReceived }
                if (shouldStop) return@launch
                val request = synchronized(stateLock) {
                    if (joinerMutable.value == null) null
                    else pendingRequestTarget?.let { target ->
                        pendingRequestPacket?.let { packet -> target to packet }
                    }
                } ?: return@launch
                Log.i(TAG, "re-sending pairing request (no answer yet)")
                RustBridge.sendPairingPayload(request.first.first, request.first.second, request.second)
            }
        }
    }

    /** Scanner side, manual: send the request once more right now. */
    fun resendRequest() {
        val request = synchronized(stateLock) {
            pendingRequestTarget?.let { target ->
                pendingRequestPacket?.let { packet -> target to packet }
            }
        } ?: return
        retryScope.launch { RustBridge.sendPairingPayload(request.first.first, request.first.second, request.second) }
    }

    /** Granting side: re-send the authenticated answer before the grant retry. */
    private fun resendAnswer() {
        val answer = synchronized(stateLock) {
            lastAnswerTarget?.let { target ->
                lastAnswerPacket?.let { packet -> target to packet }
            }
        } ?: return
        Log.i(TAG, "re-sending pairing answer while final grant is pending")
        retryScope.launch {
            RustBridge.sendPairingPayload(answer.first.first, answer.first.second, answer.second)
        }
    }

    /** Granting side, manual: re-send the certificate and the sync bootstrap. */
    fun resendGrant() {
        val packets = synchronized(stateLock) {
            lastGrantTarget?.let { target ->
                if (lastGrantPackets.isEmpty()) null else target to lastGrantPackets.toList()
            }
        } ?: return
        retryScope.launch {
            packets.second.forEach { packet ->
                RustBridge.sendPairingPayload(packets.first.first, packets.first.second, packet)
            }
        }
    }

    /** "A1B2-C3D4" from the first bytes of a device id, for human comparison. */
    fun shortDeviceId(deviceId: ByteArray): String =
        DevicePairingWire.hex(deviceId).take(8).uppercase()
            .let { if (it.length == 8) it.substring(0, 4) + "-" + it.substring(4) else it }

    /** Joiner: the screen was closed after success. */
    fun dismissJoiner() {
        synchronized(stateLock) {
            if (joinerMutable.value?.phase == JoinerPhase.DONE || joinerMutable.value?.phase == JoinerPhase.FAILED) {
                joinerMutable.value = null
            }
        }
    }

}
