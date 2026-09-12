package com.vladimir.messenger.data.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log

/**
 * Аудио-движок звонка (CALLS_BOOTSTRAP.md, 8.5).
 *
 * PCM 16 кГц mono s16le кадрами по 20 мс (640 байт): микрофон в режиме
 * VOICE_COMMUNICATION + на сессию вешаются AcousticEchoCanceler /
 * NoiseSuppressor / AutomaticGainControl (кто доступен на железе), вывод —
 * AudioTrack MODE_STREAM с usage VOICE_COMMUNICATION. Кадры крышуются
 * CallMediaCrypto (AES-GCM, ключ на направление), наружу уходит шифртекст,
 * снаружи приходит шифртекст — движок не знает, по какому транспорту едет звук.
 *
 * Воспроизведение с джиттер-буфером: кадры по номеру seq, опоздавшие (пришли
 * после их очереди) роняются, при недоборе играется тишина — голос плывёт, но
 * не трещит. Глубина буфера зависит от пути: LAN — 2 кадра разгона и 8 предела,
 * интернет (мост через брокер) — 10 и 30: там кадры едут пачками и с рывками,
 * и без разгона каждая пачка оказывалась бы «опоздавшей». Тайминг вывода
 * задаёт блокирующий AudioTrack.write.
 *
 * Кодек кадра: PCM (LAN) или IMA ADPCM (AdpcmCodec, узкие пути) — кодирование
 * идёт ДО шифрования, декодирование после расшифровки; шифр и AAD не зависят
 * от кодека, поэтому провод и ключи общие.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO проверяет CallManager до start()
class CallAudioEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Зашифрованный кадр нашего микрофона (codec = чем закодирован до шифрования), уходит в транспорт. */
    @Volatile var onOutgoingCipher: ((seq: Long, ptsMs: Long, codec: Int, cipher: ByteArray) -> Unit)? = null

    /** Кодек исходящих кадров; менеджер переключает по пути (LAN = PCM, мост = ADPCM). */
    @Volatile var outgoingCodec: Int = CallWire.CODEC_PCM_16K

    private val adpcmEncoder = AdpcmCodec()
    private val adpcmDecoder = AdpcmCodec()

    @Volatile private var running = false
    @Volatile var muted: Boolean = false
        private set
    @Volatile var speakerOn: Boolean = false
        private set

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var focusRequest: AudioFocusRequest? = null

    private var sendCrypto: CallMediaCrypto? = null
    private var recvCrypto: CallMediaCrypto? = null
    private var seqOut = 0L

    // Джиттер-буфер воспроизведения: seq → PCM.
    private val playLock = Any()
    private val pending = HashMap<Long, ByteArray>()
    private var expectedSeq = -1L
    /** Разгон: играть начинаем, когда накопилось prefillFrames; после недобора — снова копим. */
    private var buffering = true
    @Volatile private var prefillFrames = LAN_PREFILL_FRAMES
    @Volatile private var maxPendingFrames = LAN_MAX_PENDING_FRAMES

    @Volatile private var framesIn = 0L
    @Volatile private var framesOut = 0L

    fun start(sendKey: ByteArray, recvKey: ByteArray) {
        if (running) return
        running = true
        sendCrypto = CallMediaCrypto(sendKey)
        recvCrypto = CallMediaCrypto(recvKey)
        synchronized(playLock) {
            pending.clear()
            expectedSeq = -1L
            buffering = true
        }
        seqOut = 0L
        framesIn = 0L
        framesOut = 0L
        adpcmEncoder.reset()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        takeAudioFocus()

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                ) * 2,
                FRAME_BYTES * 4,
            ),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        attachEffects(rec)
        record = rec

        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    ) * 2,
                    FRAME_BYTES * 4,
                ),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(tr.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
        track = tr
        applyRouting()

        rec.startRecording()
        tr.play()
        startCapture(rec)
        startPlayback(tr)
        Log.i(TAG, "audio engine started: aec=${echoCanceler != null} ns=${noiseSuppressor != null}")
    }

    // ── Приём ───────────────────────────────────────────────────────────────

    /** Шифртекст PCM-кадра собеседника (LAN-сокет). */
    fun incomingCipher(seq: Long, cipher: ByteArray): Boolean =
        incomingFrame(seq, CallWire.CODEC_PCM_16K, cipher)

    /**
     * Шифртекст кадра любого кодека: расшифровать, раскодировать, встать в очередь
     * воспроизведения. @return true, если кадр подлинный (расшифровался ключом
     * звонка) — даже если опоздал и выброшен; false = мусор или чужой.
     */
    fun incomingFrame(seq: Long, codec: Int, cipher: ByteArray): Boolean {
        val crypto = recvCrypto ?: return false
        val plain = crypto.decrypt(seq, cipher) ?: return false
        val pcm = when (codec) {
            CallWire.CODEC_PCM_16K -> plain
            CallWire.CODEC_ADPCM_16K -> adpcmDecoder.decodeFrame(plain) ?: return false
            else -> return false
        }
        if (pcm.size != FRAME_BYTES) return false
        synchronized(playLock) {
            if (expectedSeq >= 0 && seq < expectedSeq) return true // подлинный, но опоздал — выкидываем
            pending[seq] = pcm
            // Ограничение очереди: старейшие впереди текущего — мусор, догоняем живой край.
            while (pending.size > maxPendingFrames) {
                val dropSeq = pending.keys.minOrNull() ?: break
                pending.remove(dropSeq)
                expectedSeq = maxOf(expectedSeq, dropSeq + 1)
            }
        }
        return true
    }

    /** Глубина джиттер-буфера под путь: LAN — короткая, интернет (мост) — с разгоном. */
    fun configureJitter(viaLan: Boolean) {
        prefillFrames = if (viaLan) LAN_PREFILL_FRAMES else NET_PREFILL_FRAMES
        maxPendingFrames = if (viaLan) LAN_MAX_PENDING_FRAMES else NET_MAX_PENDING_FRAMES
    }

    /** Прямой UDP через интернет: между LAN и мостом — датаграммы по 40 мс, рывки меньше, чем у брокера. */
    fun configureJitterUdp() {
        prefillFrames = UDP_PREFILL_FRAMES
        maxPendingFrames = UDP_MAX_PENDING_FRAMES
    }

    private fun nextPlayFrame(): ByteArray? {
        synchronized(playLock) {
            if (pending.isEmpty()) {
                buffering = true
                return null
            }
            if (buffering) {
                if (pending.size < prefillFrames) return null
                buffering = false
                // Стартуем с самого старого из накопленного (оно не младше прежнего
                // края: опоздавшие отсеяны выше) — разрыв после паузы не растягиваем тишиной.
                expectedSeq = pending.keys.minOrNull() ?: return null
            }
            val want = expectedSeq
            val frame = pending.remove(want)
            expectedSeq = want + 1
            return frame
        }
    }

    // ── Потоки ──────────────────────────────────────────────────────────────

    private fun startCapture(rec: AudioRecord) {
        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(FRAME_BYTES)
            while (running) {
                val n = rec.read(buf, 0, FRAME_BYTES)
                if (n != FRAME_BYTES) continue
                val seq = seqOut++
                val ptsMs = seq * FRAME_MS
                val crypto = sendCrypto ?: break
                val plain = if (muted) ByteArray(FRAME_BYTES) else buf.copyOf()
                val codec = outgoingCodec
                val cipher = try {
                    val payload = if (codec == CallWire.CODEC_ADPCM_16K) adpcmEncoder.encodeFrame(plain) else plain
                    crypto.encrypt(seq, payload)
                } catch (e: Exception) {
                    Log.w(TAG, "encrypt failed: ${e.message}")
                    break
                }
                framesOut++
                onOutgoingCipher?.invoke(seq, ptsMs, codec, cipher)
            }
        }.apply {
            name = "call-capture"
            isDaemon = true
            start()
        }
    }

    private fun startPlayback(tr: AudioTrack) {
        playbackThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val silence = ByteArray(FRAME_BYTES)
            while (running) {
                val frame = nextPlayFrame()
                val out = frame ?: silence
                var written = 0
                while (written < FRAME_BYTES && running) {
                    val w = tr.write(out, written, FRAME_BYTES - written)
                    if (w <= 0) break
                    written += w
                }
                if (frame != null) framesIn++
            }
        }.apply {
            name = "call-playback"
            isDaemon = true
            start()
        }
    }

    // ── Кнопки ──────────────────────────────────────────────────────────────

    fun toggleMute(): Boolean {
        muted = !muted
        return muted
    }

    fun toggleSpeaker(): Boolean {
        speakerOn = !speakerOn
        applyRouting()
        return speakerOn
    }

    private fun applyRouting() {
        try {
            audioManager.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {
            Log.w(TAG, "speaker routing failed: ${e.message}")
        }
    }

    // ── Аксессуары системы ──────────────────────────────────────────────────

    private fun attachEffects(rec: AudioRecord) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)
                echoCanceler?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)
                noiseSuppressor?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(rec.audioSessionId)
                gainControl?.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio effects attach failed: ${e.message}")
        }
    }

    private fun takeAudioFocus() {
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(request)
            focusRequest = request
        } catch (e: Exception) {
            Log.w(TAG, "audio focus failed: ${e.message}")
        }
    }

    // ── Стоп ────────────────────────────────────────────────────────────────

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        onOutgoingCipher = null
        runCatching { captureThread?.join(300) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { playbackThread?.join(300) }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        runCatching {
            val request = focusRequest
            if (request != null) audioManager.abandonAudioFocusRequest(request)
        }
        focusRequest = null
        runCatching { audioManager.isSpeakerphoneOn = false }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        synchronized(playLock) {
            pending.clear()
            buffering = true
        }
        sendCrypto = null
        recvCrypto = null
        Log.i(TAG, "audio engine stopped (in=$framesIn out=$framesOut)")
    }

    companion object {
        private const val TAG = "CallAudioEngine"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320
        const val FRAME_BYTES = FRAME_SAMPLES * 2               // 640 (s16le mono)
        /** Джиттер-буфер (кадры по 20 мс): разгон и предел для LAN и для интернета. */
        const val LAN_PREFILL_FRAMES = 2
        const val LAN_MAX_PENDING_FRAMES = 8
        const val NET_PREFILL_FRAMES = 10
        const val NET_MAX_PENDING_FRAMES = 30
        const val UDP_PREFILL_FRAMES = 4
        const val UDP_MAX_PENDING_FRAMES = 15
    }
}
