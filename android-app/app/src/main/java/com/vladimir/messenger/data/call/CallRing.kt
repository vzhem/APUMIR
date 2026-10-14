package com.vladimir.messenger.data.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import kotlin.math.sin

/**
 * Исходящие гудки — в трубку (ухо), громкая связь только по кнопке.
 * Входящий рингтон — в динамик, как у любого телефона.
 */
object CallRing {
    fun ringbackWhile(phase: CallStateMachine.Phase): Boolean =
        phase == CallStateMachine.Phase.OFFERING || phase == CallStateMachine.Phase.RINGING

    fun ringtoneWhile(phase: CallStateMachine.Phase): Boolean =
        phase == CallStateMachine.Phase.INCOMING

    /** Громкая связь доступна уже на дозвоне, не только в разговоре. */
    fun speakerButtonWhile(phase: CallStateMachine.Phase): Boolean =
        phase == CallStateMachine.Phase.OFFERING ||
            phase == CallStateMachine.Phase.RINGING ||
            phase == CallStateMachine.Phase.CONNECTING ||
            phase == CallStateMachine.Phase.ACTIVE
}

class CallRingPlayer(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ringtone: Ringtone? = null
    @Volatile private var speakerOn = false
    @Volatile private var ringbackRunning = false
    private var ringbackThread: Thread? = null
    private var ringbackTrack: AudioTrack? = null
    private var mode: Mode = Mode.IDLE
    private var savedVoiceVolume: Int? = null

    private enum class Mode { IDLE, RINGBACK, RINGTONE }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (mode == Mode.RINGBACK || mode == Mode.IDLE) applyVoiceRoute()
    }

    fun ensureRingback() {
        if (mode == Mode.RINGBACK && ringbackRunning) {
            applyVoiceRoute()
            return
        }
        stop(restoreRoute = false)
        applyVoiceRoute()
        boostEarpiece()
        startRingbackTrack()
        mode = Mode.RINGBACK
    }

    fun ensureRingtone() {
        if (mode == Mode.RINGTONE && ringtone?.isPlaying == true) return
        stop(restoreRoute = false)
        runCatching {
            audio.mode = AudioManager.MODE_RINGTONE
            audio.isSpeakerphoneOn = true
        }
        runCatching {
            val tone = RingtoneManager.getRingtone(
                app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            ) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build()
            if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
            tone.play()
            ringtone = tone
            mode = Mode.RINGTONE
        }.onFailure { Log.w(TAG, "ringtone: ${it.message}") }
    }

    fun stop(restoreRoute: Boolean = true) {
        ringbackRunning = false
        ringbackThread?.interrupt()
        ringbackThread = null
        runCatching { ringbackTrack?.pause() }
        runCatching { ringbackTrack?.stop() }
        runCatching { ringbackTrack?.release() }
        ringbackTrack = null
        runCatching { ringtone?.stop() }
        ringtone = null
        mode = Mode.IDLE
        restoreEarpiece()
        if (restoreRoute) {
            runCatching { audio.isSpeakerphoneOn = false }
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
        }
    }

    private fun boostEarpiece() {
        if (savedVoiceVolume != null) return
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val cur = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            savedVoiceVolume = cur
            val target = (max * 3 / 4).coerceAtLeast(cur).coerceAtMost(max)
            if (target > cur) audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        }
    }

    private fun restoreEarpiece() {
        val saved = savedVoiceVolume ?: return
        savedVoiceVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, saved, 0) }
    }

    private fun applyVoiceRoute() {
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = speakerOn
        }.onFailure { Log.w(TAG, "route: ${it.message}") }
    }

    /**
     * Гудок 425 Гц (как на АТС), 1 с тон / 4 с пауза, в STREAM_VOICE_CALL:
     * без кнопки «динамик» это трубка у уха.
     */
    private fun startRingbackTrack() {
        ringbackRunning = true
        val thread = Thread {
            val sr = SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(sr / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "ringback AudioTrack init failed")
                ringbackRunning = false
                return@Thread
            }
            ringbackTrack = track
            runCatching { track.play() }
            val chunk = ShortArray(CHUNK)
            var sample = 0L
            var inTone = true
            var phaseSamples = 0
            val toneSamples = SAMPLE_RATE * TONE_MS / 1000
            val gapSamples = SAMPLE_RATE * GAP_MS / 1000
            try {
                while (ringbackRunning && !Thread.currentThread().isInterrupted) {
                    val limit = if (inTone) toneSamples else gapSamples
                    val n = CHUNK.coerceAtMost(limit - phaseSamples)
                    if (n <= 0) {
                        inTone = !inTone
                        phaseSamples = 0
                        continue
                    }
                    for (i in 0 until n) {
                        val v = if (inTone) {
                            (sin(2.0 * Math.PI * TONE_HZ * (sample + i) / sr) * AMPLITUDE).toInt()
                        } else {
                            0
                        }
                        chunk[i] = v.toShort()
                    }
                    if (track.write(chunk, 0, n) < 0) break
                    sample += n
                    phaseSamples += n
                }
            } catch (_: InterruptedException) {
                // стоп
            } finally {
                runCatching { track.pause() }
                runCatching { track.stop() }
                runCatching { track.release() }
                if (ringbackTrack === track) ringbackTrack = null
            }
        }.apply {
            name = "call-ringback"
            isDaemon = true
            start()
        }
        ringbackThread = thread
    }

    companion object {
        private const val TAG = "CallRing"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = 320
        private const val TONE_HZ = 425.0
        private const val TONE_MS = 1_000
        private const val GAP_MS = 4_000
        /** ~0.7 полной шкалы: в трубке слышно, без хрипа. */
        private const val AMPLITUDE = 22_000
    }
}
