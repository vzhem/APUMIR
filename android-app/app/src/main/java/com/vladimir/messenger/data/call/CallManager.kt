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
 * входящем, рингтон, и связка медиа (LAN-сокет → текстовый фолбэк QUIC/relay).
 *
 * Один звонок на телефон: второй входящий получает reject|busy. Сигналы идут по
 * двум путям сразу (durable relay + прямой QUIC-ускоритель) — дедупликация по
 * callId, повторы безвредны. Живой звонок, офлайн-устойчивость не нужна.
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
        /** Голос едет текстовым фолбэком (не LAN-сокет): «медленный канал». */
        val slowTransport: Boolean = false,
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
    private var ringtone: Ringtone? = null
    private var tickJob: Job? = null
    private var endedResetJob: Job? = null
    private var endTextOverride: String? = null

    /** Очередь исходящих голосовых кадров: поток микрофона не ждёт сеть. */
    private val frameOutQueue = java.util.concurrent.ArrayBlockingQueue<OutgoingFrame>(128)
    @Volatile private var framesPumpStarted = false
    private data class OutgoingFrame(val seq: Long, val ptsMs: Long, val cipher: ByteArray)

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
            is CallWire.Packet.Ring -> feedMachine { it.onRing(nowMs()) }
            is CallWire.Packet.Accept -> onAcceptPacket(senderId, packet)
            is CallWire.Packet.Reject -> feedMachine { it.onReject(packet.reason, nowMs()) }
            is CallWire.Packet.Bye -> feedMachine { it.onBye(packet.reason, nowMs()) }
            is CallWire.Packet.Audio -> onAudioPacket(senderId, packet)
        }
        return true
    }

    private suspend fun onOfferPacket(senderId: String, offer: CallWire.Packet.Offer) {
        val now = nowMs()
        // Живой звонок: просроченный offer из relay-очереди = пропущенный, не зажигаем.
        if (now - offer.tsMs > CallWire.OFFER_FRESH_MS) {
            Log.i(TAG, "stale offer ignored (missed call) from $senderId")
            return
        }
        val busy: Boolean
        synchronized(this) {
            busy = machine != null && machine!!.phase != CallStateMachine.Phase.IDLE &&
                machine!!.phase != CallStateMachine.Phase.ENDED
            if (busy) {
                scope.launch { sendSignal(senderId, CallWire.rejectMessageId(offer.callId), CallWire.buildReject(offer.callId, CallWire.REJECT_BUSY)) }
                Log.i(TAG, "busy: rejected incoming offer from $senderId")
                return
            }
            syncChannelIdentity()
            ensureCallServer()
            sendKey = randomKey()
            recvKey = CallWire.decodeBytes(offer.mediaKeyB64)
            remoteHost = offer.lanHost
            remotePort = offer.lanPort
            audioChannel.activeCallId = offer.callId
            audioChannel.onFrame = { seq, _, cipher -> onIncomingMedia(offer.callId, seq, cipher) }
            audioChannel.onClosed = { }
            val sm = CallStateMachine(offer.callId, senderId, outgoing = false, startedAtMs = now)
            machine = sm
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
            audioChannel.onFrame = { seq, _, cipher -> onIncomingMedia(accept.callId, seq, cipher) }
            current
        }
        val effects = sm.onAccept(nowMs())
        executeEffects(sm, effects)
        syncUi(sm)
    }

    private fun onAudioPacket(senderId: String, audio: CallWire.Packet.Audio) {
        val sm = machine ?: return
        if (sm.callId != audio.callId) return
        // Текстовый фолбэк активен только без живого LAN-сокета.
        if (!audioChannel.isOpen()) {
            audioEngine?.incomingCipher(audio.seq, audio.payload)
        }
        feedMachine { it.mediaFrame(nowMs()) }
    }

    private fun onIncomingMedia(callId: String, seq: Long, cipher: ByteArray) {
        if (machine?.callId != callId) return
        audioEngine?.incomingCipher(seq, cipher)
        feedMachine { it.mediaFrame(nowMs()) }
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
            sendKey = randomKey()
            recvKey = null
            remoteHost = null
            remotePort = 0
            endTextOverride = null
            val sm = CallStateMachine(callId, peerId, outgoing = true, startedAtMs = now)
            machine = sm
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
        val effects = sm.userAccept(nowMs())
        executeEffects(sm, effects)
        syncUi(sm)
    }

    /** «Отклонить» на входящем / «Отменить» на исходящем / «Завершить» в разговоре. */
    fun hangupOrReject() {
        val sm = synchronized(this) { machine } ?: return
        val now = nowMs()
        val effects = when {
            sm.outgoing && (sm.phase == CallStateMachine.Phase.OFFERING ||
                sm.phase == CallStateMachine.Phase.RINGING) -> sm.userCancel(now)
            !sm.outgoing && sm.phase == CallStateMachine.Phase.INCOMING -> sm.userReject(now)
            else -> sm.userHangup(now)
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
        val effects = when {
            sm.outgoing && (sm.phase == CallStateMachine.Phase.OFFERING ||
                sm.phase == CallStateMachine.Phase.RINGING) -> sm.userCancel(now)
            !sm.outgoing && sm.phase == CallStateMachine.Phase.INCOMING -> sm.userReject(now)
            sm.phase == CallStateMachine.Phase.CONNECTING ||
                sm.phase == CallStateMachine.Phase.ACTIVE -> sm.userHangup(now)
            else -> emptyList()
        }
        executeEffects(sm, effects)
        syncUi(sm)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Эффекты машины
    // ═════════════════════════════════════════════════════════════════════

    private fun executeEffects(sm: CallStateMachine, effects: List<CallStateMachine.Effect>) {
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
                }

                CallStateMachine.Effect.SendRing ->
                    sendSignal(sm.peerId, CallWire.ringMessageId(sm.callId), CallWire.buildRing(sm.callId))

                CallStateMachine.Effect.SendAccept -> {
                    val key = sendKey ?: continue
                    val host = audioChannel.lanEndpointHost()
                    val port = if (host != null) audioChannel.listenPort else 0
                    val text = CallWire.buildAccept(sm.callId, host, port, key)
                    // accept дублируем: его потеря = оборванный звонок.
                    sendSignal(sm.peerId, CallWire.acceptMessageId(sm.callId, 1), text)
                    sendSignal(sm.peerId, CallWire.acceptMessageId(sm.callId, 2), text)
                }

                is CallStateMachine.Effect.SendReject ->
                    sendSignal(sm.peerId, CallWire.rejectMessageId(sm.callId), CallWire.buildReject(sm.callId, effect.reason))

                is CallStateMachine.Effect.SendBye ->
                    sendSignal(sm.peerId, CallWire.byeMessageId(sm.callId, effect.attempt), CallWire.buildBye(sm.callId, effect.reason))

                CallStateMachine.Effect.StartMedia -> startMedia(sm)

                CallStateMachine.Effect.MarkMediaUp -> syncUi(sm)

                CallStateMachine.Effect.StopMedia -> stopMedia()

                CallStateMachine.Effect.NotifyIncoming -> notifyIncoming(sm)

                CallStateMachine.Effect.CancelIncoming -> cancelIncoming()
            }
        }
    }

    /** Сигналы едут двумя путями: durable relay (messageId детерминирован) + прямой QUIC. */
    private fun sendSignal(peerId: String, messageId: String, text: String) {
        scope.launch {
            runCatching { RustBridge.sendDirectPayload(peerId, text) }
            runCatching { RustBridge.sendMessage(messageId, "direct", peerId, text) }
        }
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
        engine.onOutgoingCipher = { seq, pts, cipher ->
            // Микрофонный поток не ждёт сеть: кадры в очередь, переполнение — выкидываем.
            if (!frameOutQueue.offer(OutgoingFrame(seq, pts, cipher))) {
                frameOutQueue.poll()
                frameOutQueue.offer(OutgoingFrame(seq, pts, cipher))
            }
        }
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

    /** Выбор транспорта голоса: LAN-сокет по endpoint из сигналов, иначе текстовый фолбэк. */
    private fun establishMediaChannel(sm: CallStateMachine) {
        val host = remoteHost
        val port = remotePort
        scope.launch {
            var lanOk = audioChannel.isOpen()
            // Звонящий стучится на endpoint принимающего из accept (3 попытки).
            if (!lanOk && sm.outgoing && host != null && port > 0) {
                var attempt = 0
                while (attempt < 3 && !lanOk && machine === sm &&
                    sm.phase == CallStateMachine.Phase.CONNECTING
                ) {
                    lanOk = audioChannel.awaitOpen(host, port)
                    if (!lanOk) delay(400)
                    attempt++
                }
            }
            // Принимающий ждёт входящее соединение звонящего (его видит сервер 42109).
            if (!lanOk && !sm.outgoing) {
                val deadline = nowMs() + CallStateMachine.CONNECT_TIMEOUT_MS - 1500
                while (!lanOk && nowMs() < deadline && machine === sm &&
                    sm.phase == CallStateMachine.Phase.CONNECTING
                ) {
                    delay(300)
                    lanOk = audioChannel.isOpen()
                }
            }
            if (machine !== sm || sm.phase != CallStateMachine.Phase.CONNECTING) return@launch
            audioViaLan = lanOk
            _uiState.value = _uiState.value.copy(slowTransport = !lanOk)
            Log.i(TAG, "media channel: ${if (lanOk) "LAN socket" else "text fallback (direct/relay)"}")
        }
    }

    /** Разносит кадры транспорту: живой сокет → сокет, иначе au-текст (QUIC, потом relay). */
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
                if (audioChannel.isOpen() && audioChannel.sendFrame(frame.seq, frame.ptsMs, frame.cipher)) {
                    continue
                }
                val text = CallWire.buildAudio(current.callId, frame.seq, frame.ptsMs, frame.cipher)
                val direct = runCatching { RustBridge.sendDirectPayload(current.peerId, text) }
                    .getOrDefault(false)
                if (!direct) {
                    runCatching {
                        RustBridge.sendMessage(
                            CallWire.audioMessageId(current.callId, frame.seq),
                            "direct",
                            current.peerId,
                            text,
                        )
                    }
                }
            }
        }
    }

    private fun failMedia(sm: CallStateMachine, why: String) {
        Log.w(TAG, "media failed: $why")
        if (endTextOverride == null) endTextOverride = "Не удалось соединить"
        if (machine !== sm) return
        executeEffects(sm, sm.userHangup(nowMs()).ifEmpty { listOf(CallStateMachine.Effect.StopMedia) })
        forceLocalEnd(CallWire.BYE_FAILED)
    }

    private var audioViaLan = false

    private fun stopMedia() {
        runCatching { audioEngine?.stop() }
        audioEngine = null
        runCatching { audioChannel.closeCall() }
        audioChannel.activeCallId = null
        audioChannel.onFrame = null
        runCatching { CallService.stop(appContext) }
        _uiState.value = _uiState.value.copy(muted = false, speaker = false, slowTransport = false)
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

    private fun feedMachine(handler: (CallStateMachine) -> List<CallStateMachine.Effect>) {
        val sm = synchronized(this) { machine } ?: return
        val effects = handler(sm)
        executeEffects(sm, effects)
        syncUi(sm)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val sm = synchronized(this@CallManager) { machine } ?: continue
                val effects = sm.tick(nowMs())
                executeEffects(sm, effects)
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
                if (machine?.phase == CallStateMachine.Phase.ENDED) {
                    machine = null
                    _uiState.value = CallUiState()
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
    }
}
