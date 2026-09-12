package com.vladimir.messenger.data.call

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.R
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.service.CallService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Оркестратор звонков (CALLS_BOOTSTRAP.md, раздел 8): маршрутизатор APUCALL1-пакетов
 * из общего потока CoreServerService, держатель машины состояний, уведомление о
 * входящем, рингтон, и связка медиа: LAN-сокет (одна Wi-Fi) → прямой UDP с
 * пробиванием NAT (CallUdpChannel: интернет, ADPCM) → мост через брокер
 * (CallBrokerLink: любая сеть, ADPCM) → текстовый фолбэк QUIC/relay (старые сборки).
 *
 * Внешние ресурсы (STUN-серверы, публичные брокеры) — вспомогательные: любой из
 * них может быть заблокирован надолго, и звонок обязан идти дальше следующим
 * путём слабее, но идти.
 *
 * Один звонок на телефон: второй входящий получает reject|busy. Сигналы идут по
 * трём путям сразу (durable relay + прямой QUIC-ускоритель + мост, когда он
 * открыт) — дедупликация по callId, повторы безвредны. Живой звонок,
 * офлайн-устойчивость не нужна.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val contactRepository: ContactRepository,
) {

    data class CallUiState(
        val phase: CallStateMachine.Phase = CallStateMachine.Phase.IDLE,
        val peerId: String = "",
        val peerName: String = "",
        val callId: String = "",
        val outgoing: Boolean = false,
        /** Момент перехода в ACTIVE (таймер разговора считает отсюда). */
        val connectedAtMs: Long = 0L,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        /** 5+ секунд без кадров: «восстановление соединения…». */
        val recovering: Boolean = false,
        /** Голос едет не LAN-сокетом (мост через брокер или текстовый фолбэк). */
        val slowTransport: Boolean = false,
        /** Голос едет мостом через брокер (постоянное соединение, ADPCM): «через интернет». */
        val viaBroker: Boolean = false,
        /** Голос едет прямым UDP через интернет (NAT пробит): лучший путь вне Wi-Fi. */
        val viaUdp: Boolean = false,
        /** Причина конца по-русски («Завершён», «Занято», …) — показываем и сворачиваемся. */
        val endText: String = "",
    )

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(CallUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<CallUiState> = _uiState

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val random = SecureRandom()
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

    private val audioChannel = CallAudioChannel.get()

    private var machine: CallStateMachine? = null
    private var sendKey: ByteArray? = null   // поток «мы → собеседник»
    private var recvKey: ByteArray? = null   // поток «собеседник → мы»
    private var remoteHost: String? = null   // endpoint сокета звонка собеседника
    private var remotePort: Int = 0
    private var audioEngine: CallAudioEngine? = null

    /** Мост через брокер: поднимается на offer/accept, живёт до конца звонка. */
    @Volatile private var brokerLink: CallBrokerLink? = null
    private var linkCrypto: CallMediaCrypto? = null
    private var linkCallerKey: ByteArray? = null
    private var linkControlSeq = 0L
    private var linkReopens = 0
    private var lastLinkReopenAtMs = 0L
    private var lastGreetAtMs = 0L
    /** Собеседник ответил по мосту (пришёл его cap) — значит, мост у него тоже открыт. */
    @Volatile private var peerLinkAlive = false
    /** Кодеки собеседника из cap (по любому пути); пусто = старая сборка, только PCM. */
    @Volatile private var peerCodecs: Set<Int> = emptySet()

    /** Прямой UDP через интернет: сокет на звонок, кандидаты свои и собеседника. */
    @Volatile private var udpChannel: CallUdpChannel? = null
    @Volatile private var myCandidates: List<Pair<String, Int>> = emptyList()
    @Volatile private var peerCandidates: List<Pair<String, Int>> = emptyList()
    @Volatile private var audioViaUdp = false
    private var candidatesLinkSends = 0
    /** Самый большой seq пробы собеседника: повтор старой пробы с другого адреса не перехватит канал. */
    private var lastPeerProbeSeq = -1L
    private var ringtone: Ringtone? = null
    private var tickJob: Job? = null
    private var endedResetJob: Job? = null
    private var endTextOverride: String? = null

    /** Недавно завершённые звонки: поздний offer-дубль не должен воскрешать их на экране. */
    private val recentlyEnded = LinkedHashMap<String, Long>()

    /** cap, пришедший раньше своего offer (пути не упорядочены): подождёт машину. */
    private val earlyCaps = LinkedHashMap<String, Set<Int>>()

    /** cand, пришедший раньше своего offer: то же самое. */
    private val earlyCands = LinkedHashMap<String, List<Pair<String, Int>>>()

    /** Очередь исходящих голосовых кадров: поток микрофона не ждёт сеть. */
    private val frameOutQueue = java.util.concurrent.ArrayBlockingQueue<OutgoingFrame>(128)
    @Volatile private var framesPumpStarted = false
    private data class OutgoingFrame(val seq: Long, val ptsMs: Long, val codec: Int, val cipher: ByteArray)

    // ═════════════════════════════════════════════════════════════════════
    // Маршрутизатор входящих пакетов (вызывает CoreServerService до сохранения в чат)
    // ═════════════════════════════════════════════════════════════════════

    suspend fun routeIncoming(senderId: String, chatId: String, messageId: String, text: String): Boolean {
        if (!CallWire.isCallPacket(text)) return false
        val packet = CallWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "malformed call packet from $senderId, dropped")
            return true
        }
        when (packet) {
            is CallWire.Packet.Offer -> onOfferPacket(senderId, packet)
            // Страж по callId: ring/reject/bye применяются ТОЛЬКО к текущему звонку —
            // бродячие пакеты соседнего/просроченного звонка машину не трогают.
            is CallWire.Packet.Ring -> if (matchesCall(packet.callId)) feedMachine { it.onRing(nowMs()) }
            is CallWire.Packet.Accept -> onAcceptPacket(senderId, packet) // сам сверяет callId
            is CallWire.Packet.Reject -> if (matchesCall(packet.callId)) feedMachine { it.onReject(packet.reason, nowMs()) }
            is CallWire.Packet.Bye -> if (matchesCall(packet.callId)) feedMachine { it.onBye(packet.reason, nowMs()) }
            is CallWire.Packet.Audio -> onAudioPacket(senderId, packet, CallWire.CODEC_PCM_16K)
            is CallWire.Packet.AudioBatch ->
                packet.frames.forEach { onAudioPacket(senderId, it, packet.codec) }
            is CallWire.Packet.Capabilities -> synchronized(this) {
                if (matchesCall(packet.callId)) {
                    applyPeerCodecs(packet.codecs)
                } else if (!recentlyEnded.containsKey(packet.callId)) {
                    earlyCaps[packet.callId] = packet.codecs
                    trimEarly(earlyCaps)
                }
            }
            is CallWire.Packet.Candidates -> synchronized(this) {
                if (matchesCall(packet.callId)) {
                    applyPeerCandidates(packet.endpoints)
                } else if (!recentlyEnded.containsKey(packet.callId)) {
                    earlyCands[packet.callId] = packet.endpoints
                    trimEarly(earlyCands)
                }
            }
            // Пробы ходят только по UDP; с durable-пути — мусор.
            is CallWire.Packet.Probe -> Unit
        }
        return true
    }

    private fun trimEarly(map: LinkedHashMap<String, *>) {
        while (map.size > 8) {
            val eldest = map.keys.iterator()
            if (eldest.hasNext()) { eldest.next(); eldest.remove() } else break
        }
    }

    private fun matchesCall(callId: String): Boolean =
        machine?.callId == callId

    private suspend fun onOfferPacket(senderId: String, offer: CallWire.Packet.Offer) {
        val now = nowMs()
        // Живой звонок: просроченный offer из relay-очереди = пропущенный, не зажигаем.
        if (now - offer.tsMs > CallWire.OFFER_FRESH_MS) {
            Log.i(TAG, "stale offer ignored (missed call) from $senderId")
            return
        }
        var ringResend = false
        synchronized(this) {
            val current = machine
            // ДУБЛЬ offer ТЕКУЩЕГО звонка (ретрай звонящего каждые 3 с или повтор
            // из relay): никогда не «занято» — это ОН и есть. Если ещё звоним —
            // шлём ring повторно, в остальных фазах молча глотаем.
            if (current != null && current.callId == offer.callId) {
                if (!current.outgoing && current.phase == CallStateMachine.Phase.INCOMING) {
                    ringResend = true
                } else {
                    Log.d(TAG, "duplicate offer for own active call ${offer.callId.take(8)} ignored")
                    return
                }
            } else if (recentlyEnded.containsKey(offer.callId)) {
                // Offer для звонка, который У НАС только что завершился (поздний
                // повтор): не воскрешать звонок на экране.
                Log.i(TAG, "offer for recently-ended call ${offer.callId.take(8)} ignored")
                return
            }
            val busy = !ringResend && current != null &&
                current.phase != CallStateMachine.Phase.IDLE &&
                current.phase != CallStateMachine.Phase.ENDED
            if (ringResend) {
                val ring = CallWire.buildRing(offer.callId)
                sendLinkControl(ring)
                scope.launch {
                    sendSignal(senderId, CallWire.ringMessageId(offer.callId), ring)
                }
                Log.i(TAG, "duplicate offer for ringing call ${offer.callId.take(8)}: ring re-sent")
                return
            }
            if (busy) {
                scope.launch { sendSignal(senderId, CallWire.rejectMessageId(offer.callId), CallWire.buildReject(offer.callId, CallWire.REJECT_BUSY)) }
                Log.i(TAG, "busy: rejected incoming offer from $senderId")
                return
            }
            syncChannelIdentity()
            ensureCallServer()
            sendKey = randomKey()
            val callerKey = CallWire.decodeBytes(offer.mediaKeyB64)
            recvKey = callerKey
            remoteHost = offer.lanHost
            remotePort = offer.lanPort
            audioChannel.activeCallId = offer.callId
            audioChannel.onFrame = { seq, _, codec, cipher -> onIncomingMedia(offer.callId, seq, codec, cipher) }
            audioChannel.onClosed = { onLanClosed(offer.callId) }
            val sm = CallStateMachine(offer.callId, senderId, outgoing = false, startedAtMs = now)
            machine = sm
            peerCodecs = earlyCaps.remove(offer.callId) ?: emptySet()
            peerCandidates = earlyCands.remove(offer.callId) ?: emptyList()
            resetLinkCounters(outgoing = false)
            // Мост поднимаем сразу на offer: пока телефон звонит, соединение с
            // брокером уже стоит, и accept с голосом пойдут без задержки.
            if (callerKey != null && callerKey.size == 16) {
                openBrokerLink(sm, callerKey)
                openUdpChannel(sm)
            }
            _uiState.value = CallUiState(
                phase = sm.phase,
                peerId = senderId,
                peerName = offer.callerName,
                callId = offer.callId,
                outgoing = false,
            )
            startTicker()
            executeEffects(sm, listOf(CallStateMachine.Effect.SendRing))
        }
        executeEffects(machine!!, listOf(CallStateMachine.Effect.NotifyIncoming))
        // Имя лучше из контактов, чем самоназвание звонящего.
        scope.launch {
            val known = runCatching { contactRepository.getContactById(senderId)?.displayName }
                .getOrNull()
            if (!known.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(peerName = known)
            }
        }
    }

    private fun onAcceptPacket(senderId: String, accept: CallWire.Packet.Accept) {
        val sm = synchronized(this) {
            val current = machine
            if (current == null || current.callId != accept.callId) return
            recvKey = CallWire.decodeBytes(accept.mediaKeyB64)
            remoteHost = accept.lanHost
            remotePort = accept.lanPort
            audioChannel.activeCallId = accept.callId
            audioChannel.onFrame = { seq, _, codec, cipher -> onIncomingMedia(accept.callId, seq, codec, cipher) }
            audioChannel.onClosed = { onLanClosed(accept.callId) }
            current
        }
        val effects = synchronized(this) { sm.onAccept(nowMs()) }
        executeEffects(sm, effects)
        syncUi(sm)
    }

    private fun onAudioPacket(senderId: String, audio: CallWire.Packet.Audio, codec: Int) {
        val sm = machine ?: return
        if (sm.callId != audio.callId) return
        // Текстовый фолбэк активен только без живого LAN-сокета (собеседник шлёт
        // одним путём за раз; повтор seq движок отбросит сам).
        if (!audioChannel.isOpen()) {
            audioEngine?.incomingFrame(audio.seq, codec, audio.payload)
        }
        feedMachine { it.mediaFrame(nowMs()) }
    }

    private fun onIncomingMedia(callId: String, seq: Long, codec: Int, cipher: ByteArray) {
        if (machine?.callId != callId) return
        audioEngine?.incomingFrame(seq, codec, cipher)
        feedMachine { it.mediaFrame(nowMs()) }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Мост через брокер (CallBrokerLink): сигналы + голос вне Wi-Fi
    // ═════════════════════════════════════════════════════════════════════

    /** Кодеки собеседника узнали (любым путём): если голос уже идёт не по LAN — переключаем кодек. */
    private fun applyPeerCodecs(codecs: Set<Int>) {
        peerCodecs = codecs
        val engine = audioEngine ?: return
        if (!audioViaLan) engine.outgoingCodec = preferredCodec()
    }

    /** Поднять мост для звонка (идемпотентно). Ключ моста — производная от медиа-ключа звонящего. */
    private fun openBrokerLink(sm: CallStateMachine, callerMediaKey: ByteArray) {
        if (brokerLink != null) return
        val myId = audioChannel.myNodeId.takeIf { it.startsWith("pk_") } ?: RustBridge.nodeId() ?: return
        linkCallerKey = callerMediaKey
        linkCrypto = CallMediaCrypto(CallLinkWire.deriveLinkKey(callerMediaKey))
        // Счётчик nonce задаётся один раз на звонок (resetLinkCounters) и при
        // переоткрытии моста НЕ сбрасывается: ключ тот же на весь звонок.
        peerLinkAlive = false
        lastGreetAtMs = 0L
        val link = CallBrokerLink(
            callId = sm.callId,
            myNodeId = myId,
            peerNodeId = sm.peerId,
            onPacket = { bytes -> onLinkPacket(sm.callId, bytes) },
            onState = { state -> onLinkState(sm.callId, state) },
        )
        brokerLink = link
        link.start()
    }

    private fun onLinkState(callId: String, state: CallBrokerLink.LinkState) {
        if (machine?.callId != callId) return
        when (state) {
            CallBrokerLink.LinkState.OPEN -> {
                // Приветствие по мосту: наши кодеки. Любой пакет собеседника в ответ =
                // мост жив в обе стороны (он шлёт только после своей подписки).
                lastGreetAtMs = nowMs()
                sendLinkControl(CallWire.buildCapabilities(callId, CallWire.LOCAL_CODECS))
                Log.i(TAG, "broker link open (${brokerLink?.brokerHost}) for ${callId.take(8)}")
            }
            CallBrokerLink.LinkState.CLOSED -> {
                peerLinkAlive = false
                Log.i(TAG, "broker link closed for ${callId.take(8)}")
            }
            CallBrokerLink.LinkState.CONNECTING -> Unit
        }
    }

    /**
     * Мост умер посреди живого звонка (брокер разорвал, сеть сменилась):
     * переоткрываем, не больше LINK_REOPEN_MAX раз за звонок, с паузой.
     */
    private fun reopenBrokerLinkIfDead(sm: CallStateMachine, now: Long) {
        val link = brokerLink ?: return
        if (link.isOpen() || link.isConnecting()) return
        if (sm.phase == CallStateMachine.Phase.ENDED) return
        val key = linkCallerKey ?: return
        if (linkReopens >= LINK_REOPEN_MAX || now - lastLinkReopenAtMs < LINK_REOPEN_MS) return
        linkReopens++
        lastLinkReopenAtMs = now
        brokerLink = null
        Log.i(TAG, "broker link reopen #$linkReopens for ${sm.callId.take(8)}")
        openBrokerLink(sm, key)
    }

    /** Control-пакет провода: шифруем ключом моста, seq из своего диапазона (общий счётчик для моста и UDP). */
    private fun buildLinkControl(text: String): ByteArray? {
        val crypto = linkCrypto ?: return null
        val seq = synchronized(this) { linkControlSeq++ }
        val cipher = runCatching { crypto.encrypt(seq, text.toByteArray(Charsets.UTF_8)) }
            .getOrNull() ?: return null
        return CallLinkWire.buildControl(seq, cipher)
    }

    /** Сигнал по мосту (и по прямому UDP, если он пробит): доезжает за доли секунды. */
    private fun sendLinkControl(text: String): Boolean {
        val link = brokerLink
        val udp = udpChannel
        val viaLink = link != null && link.isOpen()
        val viaUdp = udp != null && udp.isOpen()
        if (!viaLink && !viaUdp) return false
        val packet = buildLinkControl(text) ?: return false
        var sent = false
        if (viaUdp) sent = udp!!.send(packet)
        if (viaLink) sent = link!!.send(packet) || sent
        return sent
    }

    private fun markPeerLinkAlive(callId: String) {
        if (peerLinkAlive) return
        peerLinkAlive = true
        Log.i(TAG, "broker link alive both ways for ${callId.take(8)} (${brokerLink?.brokerHost})")
        sendCandidatesViaLink(callId)
        val sm = machine ?: return
        if (sm.phase == CallStateMachine.Phase.CONNECTING || sm.phase == CallStateMachine.Phase.ACTIVE) {
            syncUi(sm)
        }
    }

    /** Пакет провода CallLinkWire: from == null — с моста через брокер, иначе — с прямого UDP. */
    private fun onLinkPacket(callId: String, bytes: ByteArray, from: java.net.InetSocketAddress? = null) {
        val sm = machine ?: return
        if (sm.callId != callId) return
        when (val packet = CallLinkWire.parse(bytes)) {
            is CallLinkWire.Packet.Control -> {
                val crypto = linkCrypto ?: return
                val plain = crypto.decrypt(packet.seq, packet.cipher) ?: return
                val text = String(plain, Charsets.UTF_8)
                val signal = CallWire.parse(text) ?: return
                // Расшифровалось ключом моста = это собеседник; по мосту — значит, он уже подписан.
                if (from == null) markPeerLinkAlive(callId) else if (signal !is CallWire.Packet.Probe) udpChannel?.onPeerPacket(from)
                // Только сигналы ЭТОГО звонка: ключ моста и так привязан к нему, но проверяем.
                if (signal !is CallWire.Packet.Probe && signalCallId(signal) != callId) return
                when (signal) {
                    is CallWire.Packet.Capabilities -> {
                        applyPeerCodecs(signal.codecs)
                        // На приветствие отвечаем ack (каждый раз: QoS0 теряет), на ack — молчим.
                        if (!signal.ack) {
                            sendLinkControl(CallWire.buildCapabilities(callId, CallWire.LOCAL_CODECS, ack = true))
                        }
                    }
                    is CallWire.Packet.Candidates -> synchronized(this) { applyPeerCandidates(signal.endpoints) }
                    is CallWire.Packet.Probe -> if (from != null && signal.callId == callId) {
                        val fresh = synchronized(this) {
                            if (packet.seq > lastPeerProbeSeq) { lastPeerProbeSeq = packet.seq; true } else false
                        }
                        if (fresh) udpChannel?.onPeerProbe(from, signal.seen)
                    }
                    is CallWire.Packet.Ring -> feedMachine { it.onRing(nowMs()) }
                    is CallWire.Packet.Accept -> onAcceptPacket(sm.peerId, signal)
                    is CallWire.Packet.Reject -> feedMachine { it.onReject(signal.reason, nowMs()) }
                    is CallWire.Packet.Bye -> feedMachine { it.onBye(signal.reason, nowMs()) }
                    else -> Unit
                }
            }
            is CallLinkWire.Packet.Media -> {
                val engine = audioEngine ?: return
                // Живость и адрес засчитываем только по кадрам, которые расшифровались
                // медиа-ключом: чужая датаграмма не должна ни держать звонок, ни
                // перехватить адрес. Дубли по двум путям движок отсеет по seq.
                var authentic = 0
                packet.frames.forEach { f -> if (engine.incomingFrame(f.seq, packet.codec, f.cipher)) authentic++ }
                if (authentic == 0) return
                if (from == null) markPeerLinkAlive(callId) else udpChannel?.onPeerPacket(from)
                feedMachine { it.mediaFrames(nowMs(), authentic) }
            }
            null -> Unit
        }
    }

    private fun signalCallId(signal: CallWire.Packet): String = when (signal) {
        is CallWire.Packet.Offer -> signal.callId
        is CallWire.Packet.Ring -> signal.callId
        is CallWire.Packet.Accept -> signal.callId
        is CallWire.Packet.Reject -> signal.callId
        is CallWire.Packet.Bye -> signal.callId
        is CallWire.Packet.Audio -> signal.callId
        is CallWire.Packet.AudioBatch -> signal.callId
        is CallWire.Packet.Capabilities -> signal.callId
        is CallWire.Packet.Candidates -> signal.callId
        is CallWire.Packet.Probe -> signal.callId
    }

    // ═════════════════════════════════════════════════════════════════════
    // Прямой UDP через интернет (CallUdpChannel): STUN-кандидаты + пробивание NAT
    // ═════════════════════════════════════════════════════════════════════

    /** Открыть UDP-сокет звонка и в фоне собрать кандидатов (STUN ждём ≤ 1,5 с). Идемпотентно. */
    private fun openUdpChannel(sm: CallStateMachine) {
        if (udpChannel != null) return
        val udp = CallUdpChannel(
            callId = sm.callId,
            onDatagram = { bytes, _, from -> onLinkPacket(sm.callId, bytes, from) },
            onState = { state -> onUdpState(sm.callId, state) },
        )
        if (!udp.open()) return
        udpChannel = udp
        myCandidates = emptyList()
        candidatesLinkSends = 0
        scope.launch(Dispatchers.IO) {
            val candidates = runCatching { udp.gatherCandidates() }.getOrDefault(emptyList())
            if (machine !== sm || udpChannel !== udp) return@launch
            if (candidates.isEmpty()) {
                Log.i(TAG, "udp: no candidates for ${sm.callId.take(8)} — direct path skipped")
                return@launch
            }
            myCandidates = candidates
            val text = runCatching { CallWire.buildCandidates(sm.callId, candidates) }.getOrNull() ?: return@launch
            // Durable-путь (relay/QUIC) — страховка; мост — быстрый путь, когда он уже жив.
            // messageId без коллизий между сторонами (как у cap): звонящий 1, принимающий 0.
            sendSignal(sm.peerId, CallWire.candidatesMessageId(sm.callId, if (sm.outgoing) 1 else 0), text)
            if (peerLinkAlive) sendCandidatesViaLink(sm.callId)
            // Кандидаты собеседника могли прийти раньше наших: пробивать можно сразу.
            synchronized(this@CallManager) { startProbingIfReady(sm) }
        }
    }

    /** Наши кандидаты по мосту (≤ 3 раз за звонок: на «мост жив» и в ответ на cand собеседника). */
    private fun sendCandidatesViaLink(callId: String) {
        val candidates = myCandidates
        if (candidates.isEmpty() || machine?.callId != callId) return
        synchronized(this) {
            if (candidatesLinkSends >= 3) return
            candidatesLinkSends++
        }
        val text = runCatching { CallWire.buildCandidates(callId, candidates) }.getOrNull() ?: return
        sendLinkControl(text)
    }

    /** Кандидаты собеседника (любым путём): запоминаем, отвечаем своими по мосту, начинаем пробивать. */
    private fun applyPeerCandidates(endpoints: List<Pair<String, Int>>) {
        val sm = machine ?: return
        val fresh = endpoints != peerCandidates
        peerCandidates = endpoints
        if (fresh) sendCandidatesViaLink(sm.callId)
        startProbingIfReady(sm)
    }

    /** Под замком. Пробы стартуют, когда есть сокет, ключ моста и адреса собеседника. */
    private fun startProbingIfReady(sm: CallStateMachine) {
        val udp = udpChannel ?: return
        if (udp.isClosed() || linkCrypto == null) return
        val targets = peerCandidates
        if (targets.isEmpty()) return
        if (sm.phase == CallStateMachine.Phase.ENDED) return
        udp.startProbing(targets) { seen ->
            buildLinkControl(CallWire.buildProbe(sm.callId, seen))
        }
    }

    private fun onUdpState(callId: String, state: CallUdpChannel.UdpState) {
        val sm = machine ?: return
        if (sm.callId != callId) return
        when (state) {
            CallUdpChannel.UdpState.OPEN -> {
                Log.i(TAG, "udp direct path open for ${callId.take(8)}: ${udpChannel?.lockedPeer()}")
                if (!audioViaLan) {
                    audioViaUdp = true
                    audioEngine?.let { engine ->
                        engine.configureJitterUdp()
                        engine.outgoingCodec = preferredCodec()
                    }
                    _uiState.value = _uiState.value.copy(slowTransport = true, viaUdp = true, viaBroker = false)
                }
            }
            CallUdpChannel.UdpState.CLOSED -> {
                if (audioViaUdp) {
                    // Прямой путь умер (сеть сменилась): голос обратно на мост, буфер шире.
                    audioViaUdp = false
                    audioEngine?.configureJitter(viaLan = false)
                    _uiState.value = _uiState.value.copy(viaUdp = false, viaBroker = peerLinkAlive)
                    Log.i(TAG, "udp direct path closed mid-call ${callId.take(8)}: back to broker link")
                }
            }
            CallUdpChannel.UdpState.PROBING -> Unit
        }
    }


    // ═════════════════════════════════════════════════════════════════════
    // Команды от UI
    // ═════════════════════════════════════════════════════════════════════

    /** Исходящий звонок (кнопка в чате). Безопасно при повторном нажатии. */
    fun startOutgoing(peerId: String, peerName: String) {
        val now = nowMs()
        synchronized(this) {
            val current = machine
            if (current != null && current.phase != CallStateMachine.Phase.IDLE &&
                current.phase != CallStateMachine.Phase.ENDED
            ) {
                return
            }
            val myId = RustBridge.nodeId()
            if (myId == null || !RustBridge.isRunning()) {
                _uiState.value = CallUiState(
                    phase = CallStateMachine.Phase.ENDED,
                    peerId = peerId,
                    peerName = peerName,
                    outgoing = true,
                    endText = "Нет подключения к сети",
                )
                scheduleIdleReset()
                Log.w(TAG, "startOutgoing rejected: engine not running")
                return
            }
            syncChannelIdentity()
            ensureCallServer()
            val callId = newCallId()
            val key = randomKey()
            sendKey = key
            recvKey = null
            remoteHost = null
            remotePort = 0
            endTextOverride = null
            peerCodecs = emptySet()
            resetLinkCounters(outgoing = true)
            val sm = CallStateMachine(callId, peerId, outgoing = true, startedAtMs = now)
            machine = sm
            openBrokerLink(sm, key)
            openUdpChannel(sm)
            _uiState.value = CallUiState(
                phase = sm.phase,
                peerId = peerId,
                peerName = peerName,
                callId = callId,
                outgoing = true,
            )
            startTicker()
            executeEffects(sm, sm.tick(now)) // offer #1 уходит сразу
        }
    }

    /** Принять входящий (кнопка в UI после предоставления RECORD_AUDIO). */
    fun accept() {
        val sm = synchronized(this) { machine } ?: return
        val effects = synchronized(this) { sm.userAccept(nowMs()) }
        executeEffects(sm, effects)
        syncUi(sm)
    }

    /** «Отклонить» на входящем / «Отменить» на исходящем / «Завершить» в разговоре. */
    fun hangupOrReject() {
        val sm = synchronized(this) { machine } ?: return
        val now = nowMs()
        val effects = synchronized(this) {
            when {
                sm.outgoing && (sm.phase == CallStateMachine.Phase.OFFERING ||
                    sm.phase == CallStateMachine.Phase.RINGING) -> sm.userCancel(now)
                !sm.outgoing && sm.phase == CallStateMachine.Phase.INCOMING -> sm.userReject(now)
                else -> sm.userHangup(now)
            }
        }
        if (effects.isEmpty()) {
            forceLocalEnd(CallWire.BYE_CANCEL)
        } else {
            executeEffects(sm, effects)
            syncUi(sm)
        }
    }

    fun toggleMute(): Boolean {
        val engine = audioEngine ?: return false
        val muted = engine.toggleMute()
        _uiState.value = _uiState.value.copy(muted = muted)
        return muted
    }

    fun toggleSpeaker(): Boolean {
        val engine = audioEngine ?: return false
        val on = engine.toggleSpeaker()
        _uiState.value = _uiState.value.copy(speaker = on)
        return on
    }

    /** UI сообщает результат запроса RECORD_AUDIO: без микрофона звонок бессмысленен. */
    fun onMicPermissionDenied() {
        val sm = synchronized(this) { machine } ?: return
        endTextOverride = "Нужен доступ к микрофону"
        val now = nowMs()
        val effects = synchronized(this) {
            when {
                sm.outgoing && (sm.phase == CallStateMachine.Phase.OFFERING ||
                    sm.phase == CallStateMachine.Phase.RINGING) -> sm.userCancel(now)
                !sm.outgoing && sm.phase == CallStateMachine.Phase.INCOMING -> sm.userReject(now)
                sm.phase == CallStateMachine.Phase.CONNECTING ||
                    sm.phase == CallStateMachine.Phase.ACTIVE -> sm.userHangup(now)
                else -> emptyList()
            }
        }
        executeEffects(sm, effects)
        syncUi(sm)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Эффекты машины
    // ═════════════════════════════════════════════════════════════════════

    private fun executeEffects(sm: CallStateMachine, effects: List<CallStateMachine.Effect>) {
        var mediaStopped = false
        for (effect in effects) {
            when (effect) {
                is CallStateMachine.Effect.SendOffer -> {
                    val key = sendKey ?: continue
                    val host = audioChannel.lanEndpointHost()
                    val port = if (host != null) audioChannel.listenPort else 0
                    val myName = appContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                        .getString("display_name", null) ?: "Без имени"
                    val text = CallWire.buildOffer(
                        callId = sm.callId,
                        callerName = myName,
                        tsMs = nowMs(),
                        lanHost = host,
                        lanPort = port,
                        mediaKey = key,
                    )
                    sendSignal(sm.peerId, CallWire.offerMessageId(sm.callId, effect.attempt), text)
                    // Кодеки — отдельным пакетом следом: формат offer не трогаем (старые сборки
                    // разбирают его строго по числу полей), а cap они молча отбросят.
                    sendSignal(
                        sm.peerId,
                        CallWire.capabilitiesMessageId(sm.callId, effect.attempt),
                        CallWire.buildCapabilities(sm.callId, CallWire.LOCAL_CODECS),
                    )
                }

                CallStateMachine.Effect.SendRing -> {
                    val text = CallWire.buildRing(sm.callId)
                    sendLinkControl(text)
                    sendSignal(sm.peerId, CallWire.ringMessageId(sm.callId), text)
                }

                CallStateMachine.Effect.SendAccept -> {
                    val key = sendKey ?: continue
                    val host = audioChannel.lanEndpointHost()
                    val port = if (host != null) audioChannel.listenPort else 0
                    val text = CallWire.buildAccept(sm.callId, host, port, key)
                    // По мосту accept доезжает за доли секунды; durable-путь — страховка.
                    sendLinkControl(text)
                    // accept дублируем: его потеря = оборванный звонок.
                    sendSignal(sm.peerId, CallWire.acceptMessageId(sm.callId, 1), text)
                    sendSignal(sm.peerId, CallWire.acceptMessageId(sm.callId, 2), text)
                    sendSignal(
                        sm.peerId,
                        CallWire.capabilitiesMessageId(sm.callId, 0),
                        CallWire.buildCapabilities(sm.callId, CallWire.LOCAL_CODECS),
                    )
                    scheduleAcceptResends(sm.callId, sm.peerId, text)
                }

                is CallStateMachine.Effect.SendReject -> {
                    val text = CallWire.buildReject(sm.callId, effect.reason)
                    sendLinkControl(text)
                    sendSignal(sm.peerId, CallWire.rejectMessageId(sm.callId), text)
                }

                is CallStateMachine.Effect.SendBye -> {
                    val text = CallWire.buildBye(sm.callId, effect.reason)
                    sendLinkControl(text)
                    sendSignal(sm.peerId, CallWire.byeMessageId(sm.callId, effect.attempt), text)
                }

                CallStateMachine.Effect.StartMedia -> startMedia(sm)

                CallStateMachine.Effect.MarkMediaUp -> syncUi(sm)

                // Мост закрываем ПОСЛЕ всего списка: bye идёт в нём следом за StopMedia
                // и должен успеть уйти по мосту.
                CallStateMachine.Effect.StopMedia -> {
                    stopMedia(keepLink = true)
                    mediaStopped = true
                }

                CallStateMachine.Effect.NotifyIncoming -> notifyIncoming(sm)

                CallStateMachine.Effect.CancelIncoming -> cancelIncoming()
            }
        }
        if (mediaStopped) closeBrokerLink()
    }

    /** Сигналы едут двумя путями: durable relay (messageId детерминирован) + прямой QUIC. */
    private fun sendSignal(peerId: String, messageId: String, text: String) {
        scope.launch {
            sendQuic(peerId, text)
            runCatching { RustBridge.sendMessage(messageId, "direct", peerId, text) }
        }
    }

    /**
     * Прямой QUIC с рубильником: пока он мёртв, каждая попытка стоит до 5 с
     * блокировки потока (приёмка 2026-09-01 22:23: весь вечер QUIC не поднялся,
     * сигналы плелись 8-25 с). После 2 промахов подряд — 45 с шлём только по
     * брокерному крюку, затем пробуем QUIC снова.
     */
    private var quicFailStreak = 0
    @Volatile private var quicOpenUntilMs = 0L

    private fun sendQuic(peerId: String, payload: String): Boolean {
        if (nowMs() < quicOpenUntilMs) return false
        val ok = runCatching { RustBridge.sendDirectPayload(peerId, payload) }.getOrDefault(false)
        if (ok) {
            quicFailStreak = 0
        } else {
            quicFailStreak++
            if (quicFailStreak >= 2) {
                quicFailsToLog()
                quicFailStreak = 0
            }
        }
        return ok
    }

    private fun quicFailsToLog() {
        quicOpenUntilMs = nowMs() + QUIC_BREAKER_MS
        Log.i(TAG, "QUIC breaker open ${QUIC_BREAKER_MS / 1000}s: сигналы/голос — только брокер")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Медиа
    // ═════════════════════════════════════════════════════════════════════

    private fun startMedia(sm: CallStateMachine) {
        val sk = sendKey
        val rk = recvKey
        if (sk == null || rk == null) {
            failMedia(sm, "внутренняя ошибка ключей")
            return
        }
        val micGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            endTextOverride = "Нужен доступ к микрофону"
            failMedia(sm, "no mic permission")
            return
        }
        runCatching { CallService.start(appContext) }
            .onFailure { Log.w(TAG, "CallService start failed: ${it.message}") }

        val engine = CallAudioEngine(appContext)
        engine.onOutgoingCipher = { seq, pts, codec, cipher ->
            // Микрофонный поток не ждёт сеть: кадры в очередь, переполнение — выкидываем.
            if (!frameOutQueue.offer(OutgoingFrame(seq, pts, codec, cipher))) {
                frameOutQueue.poll()
                frameOutQueue.offer(OutgoingFrame(seq, pts, codec, cipher))
            }
        }
        // До выбора пути — сжатый кодек, если собеседник его умеет: новый LAN его
        // тоже понимает, а на мосту/фолбэке он в четыре раза легче. Старой сборке — PCM.
        audioViaLan = audioChannel.isOpen()
        engine.outgoingCodec = if (audioViaLan) CallWire.CODEC_PCM_16K else preferredCodec()
        engine.configureJitter(viaLan = audioViaLan)
        try {
            engine.start(sk, rk)
        } catch (e: Throwable) {
            Log.e(TAG, "audio engine start failed", e)
            runCatching { engine.stop() }
            failMedia(sm, "audio init failed")
            return
        }
        audioEngine = engine
        startFramesPump(sm)
        establishMediaChannel(sm)
    }

    /**
     * Принимающий: accept утонул в очереди = звонящий висит в «звонок» мимо
     * факта. Пока звонок в CONNECTING и голос не пошёл, досылаем accept каждые
     * 2.5 с (до 5 раз). Звонящий смашиной дубли глотает (он уже в CONNECTING).
     */
    private fun scheduleAcceptResends(callId: String, peerId: String, acceptText: String) {
        scope.launch {
            var attempt = 3
            while (attempt <= 7) {
                delay(ACCEPT_RESEND_MS)
                val m = synchronized(this@CallManager) { machine }
                if (m == null || m.callId != callId || m.outgoing ||
                    m.phase != CallStateMachine.Phase.CONNECTING
                ) {
                    return@launch
                }
                Log.i(TAG, "accept resend #$attempt for ${callId.take(8)}")
                sendLinkControl(acceptText)
                sendSignal(peerId, CallWire.acceptMessageId(callId, attempt), acceptText)
                attempt++
            }
        }
    }

    /** Выбор транспорта голоса: LAN-сокет по endpoint из сигналов, иначе текстовый фолбэк. */
    private fun establishMediaChannel(sm: CallStateMachine) {
        val host = remoteHost
        val port = remotePort
        scope.launch {
            var lanOk = audioChannel.isOpen()
            // Звонящий стучится на endpoint принимающего из accept (3 попытки; если
            // мост уже жив — одна: чужой LAN-адрес за NAT недостижим, а голос ждать не может).
            if (!lanOk && sm.outgoing && host != null && port > 0) {
                var attempt = 0
                val maxAttempts = if (peerLinkAlive || udpChannel?.isOpen() == true) 1 else 3
                while (attempt < maxAttempts && !lanOk && machine === sm &&
                    sm.phase == CallStateMachine.Phase.CONNECTING
                ) {
                    lanOk = audioChannel.awaitOpen(host, port)
                    if (!lanOk) delay(400)
                    attempt++
                }
            }
            // Принимающий ждёт входящее соединение звонящего (его видит сервер 42109) —
            // только если сам объявил LAN-адрес в accept; без Wi-Fi ждать нечего.
            // Если мост уже жив, ждём коротко: звонящий, скорее всего, в другой сети.
            if (!lanOk && !sm.outgoing && audioChannel.lanEndpointHost() != null) {
                val internetAlive = peerLinkAlive || udpChannel?.isOpen() == true
                val deadline = nowMs() + if (internetAlive) LAN_WAIT_LINK_MS else LAN_WAIT_MS
                while (!lanOk && nowMs() < deadline && machine === sm &&
                    sm.phase == CallStateMachine.Phase.CONNECTING
                ) {
                    delay(300)
                    lanOk = audioChannel.isOpen()
                }
            }
            if (machine !== sm || sm.phase != CallStateMachine.Phase.CONNECTING) return@launch
            audioViaLan = lanOk
            val udpOk = !lanOk && udpChannel?.isOpen() == true
            audioViaUdp = udpOk
            audioEngine?.let { engine ->
                when {
                    lanOk -> engine.configureJitter(viaLan = true)
                    udpOk -> engine.configureJitterUdp()
                    else -> engine.configureJitter(viaLan = false)
                }
                engine.outgoingCodec = if (lanOk) CallWire.CODEC_PCM_16K else preferredCodec()
            }
            _uiState.value = _uiState.value.copy(
                slowTransport = !lanOk,
                viaUdp = udpOk,
                viaBroker = !lanOk && !udpOk && peerLinkAlive,
            )
            Log.i(
                TAG,
                "media channel: " + when {
                    lanOk -> "LAN socket"
                    udpOk -> "direct UDP (${udpChannel?.lockedPeer()})"
                    peerLinkAlive -> "broker link (${brokerLink?.brokerHost})"
                    else -> "text fallback (direct/relay)"
                },
            )
        }
    }

    /** LAN-сокет умер посреди разговора: голос дальше едет мостом/фолбэком — сжатым и с разгоном. */
    private fun onLanClosed(callId: String) {
        val sm = machine ?: return
        if (sm.callId != callId || !audioViaLan) return
        if (sm.phase != CallStateMachine.Phase.CONNECTING && sm.phase != CallStateMachine.Phase.ACTIVE) return
        audioViaLan = false
        val udpOk = udpChannel?.isOpen() == true
        audioViaUdp = udpOk
        audioEngine?.let { engine ->
            if (udpOk) engine.configureJitterUdp() else engine.configureJitter(viaLan = false)
            engine.outgoingCodec = preferredCodec()
        }
        _uiState.value = _uiState.value.copy(slowTransport = true, viaUdp = udpOk, viaBroker = !udpOk && peerLinkAlive)
        Log.i(TAG, "LAN socket closed mid-call ${callId.take(8)}: falling back to ${if (udpOk) "direct UDP" else "broker link"}")
    }

    /** ADPCM, если собеседник объявил его в cap; иначе PCM (старая сборка). */
    private fun preferredCodec(): Int =
        if (CallWire.CODEC_ADPCM_16K in peerCodecs) CallWire.CODEC_ADPCM_16K else CallWire.CODEC_PCM_16K

    /**
     * Разносит кадры транспорту, пояса по скорости:
     * живой LAN-сокет → прямой UDP (NAT пробит, 2 кадра в датаграмме) → МОСТ
     * через брокер (постоянное соединение, пачка из 4 кадров = 80 мс, двоичный
     * провод) → прямой QUIC одиночным кадром →
     * текстовая ab/ac-строка через ядро (только для сборок без моста). Кадры,
     * которые не ушли, просто теряем — копить их в durable-очередь значит
     * вывалить на собеседника простыню из прошлого; дыру добьёт сторож темпа.
     */
    private fun startFramesPump(sm: CallStateMachine) {
        if (framesPumpStarted) return
        framesPumpStarted = true
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = frameOutQueue.take()
                val current = machine ?: continue
                if (current.phase != CallStateMachine.Phase.CONNECTING &&
                    current.phase != CallStateMachine.Phase.ACTIVE
                ) {
                    continue
                }
                if (audioChannel.isOpen() &&
                    audioChannel.sendFrame(frame.seq, frame.ptsMs, frame.cipher, frame.codec)
                ) {
                    continue
                }
                val udp = udpChannel
                if (udp != null && udp.isOpen()) {
                    // Прямой UDP: 2 кадра (40 мс) в датаграмме — 25 пакетов/с, в MTU влезает.
                    val frames = ArrayList<CallLinkWire.MediaFrame>(UDP_BATCH_FRAMES)
                    frames += CallLinkWire.MediaFrame(frame.seq, frame.cipher)
                    val codec = frame.codec
                    val next = frameOutQueue.poll(25, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (next != null) {
                        if (next.codec == codec) frames += CallLinkWire.MediaFrame(next.seq, next.cipher) else frameOutQueue.offer(next)
                    }
                    val packet = runCatching { CallLinkWire.buildMedia(codec, frames) }.getOrNull()
                    if (packet != null && udp.send(packet)) continue
                }
                val link = brokerLink
                if (link != null && link.isOpen() && peerLinkAlive) {
                    // Пачка: 4 кадра (80 мс) или что накопилось за 60 мс — компромисс
                    // между числом публикаций и задержкой.
                    val frames = ArrayList<CallLinkWire.MediaFrame>(LINK_BATCH_FRAMES)
                    frames += CallLinkWire.MediaFrame(frame.seq, frame.cipher)
                    val codec = frame.codec
                    val deadline = android.os.SystemClock.uptimeMillis() + 60
                    while (frames.size < LINK_BATCH_FRAMES &&
                        android.os.SystemClock.uptimeMillis() < deadline
                    ) {
                        val next = frameOutQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?: break
                        if (next.codec != codec) {
                            // Кодек переключился на границе пачки: отправляем что есть, кадр — в следующую.
                            frameOutQueue.offer(next)
                            break
                        }
                        frames += CallLinkWire.MediaFrame(next.seq, next.cipher)
                    }
                    val packet = runCatching { CallLinkWire.buildMedia(codec, frames) }.getOrNull()
                    if (packet != null && link.send(packet)) continue
                }
                // Одиночный au по проводу — только PCM (старые сборки другого не знают);
                // сжатый кадр едет ac-бандажом из одного кадра.
                val single = runCatching {
                    if (frame.codec == CallWire.CODEC_PCM_16K) {
                        CallWire.buildAudio(current.callId, frame.seq, frame.ptsMs, frame.cipher)
                    } else {
                        CallWire.buildAudioBatch(
                            current.callId,
                            listOf(CallWire.Packet.Audio(current.callId, frame.seq, frame.ptsMs, frame.cipher)),
                            frame.codec,
                        )
                    }
                }.getOrNull()
                if (single != null && sendQuic(current.peerId, single)) continue
                // Бандаж: дотягиваем до 8 кадров (или 120 мс), чтобы не душить брокер.
                val batch = ArrayList<CallWire.Packet.Audio>(CallWire.AUDIO_BATCH_MAX_FRAMES)
                batch += CallWire.Packet.Audio(current.callId, frame.seq, frame.ptsMs, frame.cipher)
                val codec = frame.codec
                val deadline = android.os.SystemClock.uptimeMillis() + 120
                while (batch.size < CallWire.AUDIO_BATCH_MAX_FRAMES &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    val next = frameOutQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: break
                    if (next.codec != codec) {
                        frameOutQueue.offer(next)
                        break
                    }
                    batch += CallWire.Packet.Audio(current.callId, next.seq, next.ptsMs, next.cipher)
                }
                val text = runCatching { CallWire.buildAudioBatch(current.callId, batch, codec) }
                    .getOrNull() ?: continue
                runCatching { RustBridge.sendMessageMqtt(current.peerId, text) }
            }
        }
    }

    private fun failMedia(sm: CallStateMachine, why: String) {
        Log.w(TAG, "media failed: $why")
        if (endTextOverride == null) endTextOverride = "Не удалось соединить"
        if (machine !== sm) return
        val effects = synchronized(this) { sm.userHangup(nowMs()) }
        executeEffects(sm, effects.ifEmpty { listOf(CallStateMachine.Effect.StopMedia) })
        forceLocalEnd(CallWire.BYE_FAILED)
    }

    private var audioViaLan = false

    private fun stopMedia(keepLink: Boolean = false) {
        runCatching { audioEngine?.stop() }
        audioEngine = null
        runCatching { audioChannel.closeCall() }
        audioChannel.activeCallId = null
        audioChannel.onFrame = null
        if (!keepLink) closeBrokerLink()
        runCatching { CallService.stop(appContext) }
        _uiState.value = _uiState.value.copy(
            muted = false, speaker = false, slowTransport = false, viaBroker = false, viaUdp = false,
        )
    }

    /** Мост и UDP закрываем чуть позже конца звонка: последний bye по ним ещё должен уйти. */
    private fun closeBrokerLink() {
        val udp = udpChannel
        udpChannel = null
        audioViaUdp = false
        myCandidates = emptyList()
        peerCandidates = emptyList()
        val link = brokerLink
        brokerLink = null
        linkCrypto = null
        linkCallerKey = null
        peerLinkAlive = false
        if (link == null && udp == null) return
        scope.launch {
            delay(LINK_LINGER_MS)
            runCatching { link?.close() }
            runCatching { udp?.close() }
        }
    }

    /** Зовётся при создании машины звонка, ДО openBrokerLink. */
    private fun resetLinkCounters(outgoing: Boolean) {
        linkControlSeq = if (outgoing) 0L else CallLinkWire.CONTROL_SEQ_CALLEE_BASE
        linkReopens = 0
        lastLinkReopenAtMs = 0L
        lastGreetAtMs = 0L
        lastPeerProbeSeq = -1L
        candidatesLinkSends = 0
        audioViaUdp = false
    }

    // ═════════════════════════════════════════════════════════════════════
    // Входящий звонок: уведомление, рингтон, вибрация
    // ═════════════════════════════════════════════════════════════════════

    private fun notifyIncoming(sm: CallStateMachine) {
        ensureCallChannel()
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CALL, sm.callId)
        }
        val pending = PendingIntent.getActivity(
            appContext, CALL_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Входящий звонок")
            .setContentText(_uiState.value.peerName.ifBlank { sm.peerId.takeLast(8) })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
        notificationManager.notify(CALL_NOTIFICATION_ID, notification)

        runCatching {
            val tone = RingtoneManager.getRingtone(
                appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )
            ringtone = tone
            tone?.isLooping = true
            tone?.play()
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 700, 900), 0)
            }
        }
        Log.i(TAG, "incoming call notification shown: ${sm.callId.take(8)}")
    }

    private fun cancelIncoming() {
        runCatching { notificationManager.cancel(CALL_NOTIFICATION_ID) }
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }

    private fun ensureCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CALL_CHANNEL_ID, "Звонки APU", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Входящие звонки"
                setSound(null, null)      // звук играет наш Ringtone (управляем сами)
                enableVibration(false)    // вибрация наша
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Тики, синхронизация UI, завершение
    // ═════════════════════════════════════════════════════════════════════

    /** Машину двигаем под замком: события приходят с трёх потоков (ядро, LAN-сокет, мост). */
    private fun feedMachine(handler: (CallStateMachine) -> List<CallStateMachine.Effect>) {
        val fed = synchronized(this) {
            val sm = machine ?: return
            sm to handler(sm)
        }
        executeEffects(fed.first, fed.second)
        syncUi(fed.first)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = nowMs()
                val ticked = synchronized(this@CallManager) {
                    val sm = machine ?: return@synchronized null
                    // Приветствие по мосту (QoS0, может потеряться; собеседник мог
                    // подписаться позже нас): повторяем, пока он не ответил.
                    val link = brokerLink
                    if (link != null && link.isOpen() && !peerLinkAlive && now - lastGreetAtMs >= LINK_GREET_MS &&
                        sm.phase != CallStateMachine.Phase.ENDED
                    ) {
                        lastGreetAtMs = now
                        sendLinkControl(CallWire.buildCapabilities(sm.callId, CallWire.LOCAL_CODECS))
                    }
                    reopenBrokerLinkIfDead(sm, now)
                    sm to sm.tick(now)
                } ?: continue
                val sm = ticked.first
                executeEffects(sm, ticked.second)
                syncUi(sm)
                if (sm.phase == CallStateMachine.Phase.ENDED) {
                    scheduleIdleReset()
                    break
                }
            }
        }
    }

    /** ENDED держим на экране пару секунд (видно «Завершён»/«Занято»), потом чистый IDLE. */
    private fun scheduleIdleReset() {
        endedResetJob?.cancel()
        endedResetJob = scope.launch {
            delay(ENDED_VISIBLE_MS)
            synchronized(this@CallManager) {
                val finished = machine
                if (finished?.phase == CallStateMachine.Phase.ENDED) {
                    machine = null
                    closeBrokerLink() // звонок мог кончиться до медиа (отклонён, не ответили)
                    _uiState.value = CallUiState()
                    recentlyEnded[finished.callId] = nowMs()
                    while (recentlyEnded.size > 32) {
                        val eldest = recentlyEnded.keys.iterator()
                        if (eldest.hasNext()) { eldest.next(); eldest.remove() } else break
                    }
                }
            }
        }
    }

    private fun syncUi(sm: CallStateMachine) {
        val current = _uiState.value
        if (current.callId != sm.callId) return
        val endText = if (sm.phase == CallStateMachine.Phase.ENDED) {
            endTextOverride ?: endTextFor(sm.endReason)
        } else {
            ""
        }
        _uiState.value = current.copy(
            phase = sm.phase,
            connectedAtMs = sm.connectedAtMs,
            recovering = sm.recovering,
            viaUdp = current.slowTransport && audioViaUdp,
            viaBroker = current.slowTransport && !audioViaUdp && peerLinkAlive,
            endText = endText,
        )
        if (sm.phase == CallStateMachine.Phase.ENDED) scheduleIdleReset()
    }

    private fun forceLocalEnd(reason: String) {
        stopMedia()
        cancelIncoming()
        val sm = machine
        if (sm != null) {
            syncUi(sm)
        }
        _uiState.value = _uiState.value.copy(
            phase = CallStateMachine.Phase.ENDED,
            endText = endTextOverride ?: endTextFor(reason),
        )
        scheduleIdleReset()
    }

    private fun endTextFor(reason: String?): String = when (reason) {
        null, "", "missed" -> "Пропущенный"
        CallWire.BYE_END -> "Завершён"
        CallWire.BYE_CANCEL -> "Отменён"
        CallWire.BYE_TIMEOUT -> "Нет ответа"
        CallWire.BYE_FAILED -> "Не удалось соединить"
        CallWire.REJECT_DECLINE -> "Отклонено"
        CallWire.REJECT_BUSY -> "Занято"
        else -> "Завершён"
    }

    // ═════════════════════════════════════════════════════════════════════
    // Утилиты
    // ═════════════════════════════════════════════════════════════════════

    private fun syncChannelIdentity() {
        val nodeId = RustBridge.nodeId() ?: return
        if (nodeId.startsWith("pk_")) audioChannel.myNodeId = nodeId
    }

    @Synchronized
    private fun ensureCallServer() {
        runCatching { audioChannel.startServer() }
            .onFailure { Log.w(TAG, "call server start failed: ${it.message}") }
    }

    private fun randomKey(): ByteArray {
        val key = ByteArray(16)
        random.nextBytes(key)
        return key
    }

    private fun newCallId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "CallManager"
        const val EXTRA_OPEN_CALL = "apu_open_call"
        private const val CALL_CHANNEL_ID = "apu_calls"
        private const val CALL_NOTIFICATION_ID = 7001
        private const val CALL_REQUEST_CODE = 77
        private const val TICK_MS = 500L
        private const val ENDED_VISIBLE_MS = 2500L
        /** Пауза между досылками accept и длинна отключения мёртвого QUIC. */
        private const val ACCEPT_RESEND_MS = 2_500L
        private const val QUIC_BREAKER_MS = 45_000L
        /** Кадров в одной публикации моста (по 20 мс) и сколько мост живёт после конца звонка. */
        private const val LINK_BATCH_FRAMES = 4
        private const val UDP_BATCH_FRAMES = 2
        private const val LINK_LINGER_MS = 1_500L
        /** Сколько принимающий ждёт LAN-сокет звонящего, прежде чем считать путь «интернет». */
        private const val LAN_WAIT_MS = 8_000L
        private const val LAN_WAIT_LINK_MS = 3_000L
        /** Пока собеседник не ответил по мосту, приветствие cap повторяем с этим шагом. */
        private const val LINK_GREET_MS = 1_000L
        /** Переоткрытие умершего моста: не чаще и не больше, чем указано. */
        private const val LINK_REOPEN_MS = 4_000L
        private const val LINK_REOPEN_MAX = 3
    }
}
