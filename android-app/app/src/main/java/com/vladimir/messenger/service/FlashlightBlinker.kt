package com.vladimir.messenger.service

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.vladimir.messenger.data.notification.ActiveBlinkKind
import com.vladimir.messenger.data.notification.ActiveBlinkState
import com.vladimir.messenger.data.notification.BlinkDecision
import com.vladimir.messenger.data.notification.FlashlightAlertPolicy
import com.vladimir.messenger.data.notification.FlashlightAlertStore
import com.vladimir.messenger.data.notification.FlashlightMessageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управление вспышкой фонарика при входящей тревоге SOS и новых сообщениях.
 *
 * Особенности работы на реальном телефоне:
 * - CameraManager.setTorchMode работает с API 23 без открытия камеры и не
 *   требует диалога разрешения CAMERA; если камера сейчас занята записью
 *   кружка/истории или звонком, исключение ловится и фонарик просто пропускает
 *   мигание без падения приложения;
 * - на время короткой серии берётся ограниченный по времени PARTIAL_WAKE_LOCK,
 *   чтобы спящий телефон не заморозил паузу с горящим светодиодом;
 * - если человек уже сам включил фонарик в темноте, серия инвертируется и
 *   в блоке finally возвращает фонарик во включённое состояние.
 */
@Singleton
class FlashlightBlinker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val blinkMutex = Mutex()
    private val blinkGeneration = AtomicInteger(0)
    private val activeBlinkGen = AtomicInteger(0)

    private var activeState: ActiveBlinkState? = null
    private var activeJob: Job? = null

    @Volatile
    private var externalTorchOn: Boolean = false

    @Volatile
    private var observedCameraId: String? = null

    private val callbackHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Входящий сигнал SOS из группы. */
    fun blinkForSos(isGroup: Boolean) {
        val config = FlashlightAlertStore.current(context)
        val nowMs = SystemClock.elapsedRealtime()
        val myState: ActiveBlinkState
        val previousJob: Job?
        synchronized(stateLock) {
            val decision = FlashlightAlertPolicy.decideSos(
                config = config,
                isGroup = isGroup,
                active = activeState,
                nowMs = nowMs,
            )
            if (decision != BlinkDecision.START_SOS) return
            myState = ActiveBlinkState(ActiveBlinkKind.SOS, nowMs)
            activeState = myState
            previousJob = activeJob
            activeJob = scope.launch {
                previousJob?.cancel()
                runPattern(
                    patternMs = FlashlightAlertPolicy.SOS_PATTERN_MS,
                    wakeLockMs = FlashlightAlertPolicy.SOS_WAKE_LOCK_MS,
                    state = myState,
                )
            }
        }
    }

    /** Обычное входящее сообщение (личный чат, группа или канал). */
    fun blinkForMessage(
        kind: FlashlightMessageKind,
        silent: Boolean,
        muted: Boolean,
    ) {
        val config = FlashlightAlertStore.current(context)
        val nowMs = SystemClock.elapsedRealtime()
        val myState: ActiveBlinkState
        synchronized(stateLock) {
            val decision = FlashlightAlertPolicy.decideMessage(
                config = config,
                kind = kind,
                silent = silent,
                muted = muted,
                active = activeState,
                nowMs = nowMs,
            )
            if (decision != BlinkDecision.START_MESSAGE) return
            myState = ActiveBlinkState(ActiveBlinkKind.MESSAGE, nowMs)
            activeState = myState
            activeJob = scope.launch {
                runPattern(
                    patternMs = FlashlightAlertPolicy.MESSAGE_PATTERN_MS,
                    wakeLockMs = FlashlightAlertPolicy.MESSAGE_WAKE_LOCK_MS,
                    state = myState,
                )
            }
        }
    }

    private suspend fun runPattern(
        patternMs: LongArray,
        wakeLockMs: Long,
        state: ActiveBlinkState,
    ) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            clearActiveIfSame(state)
            return
        }
        val cameraId = findTorchCameraId(cameraManager)
        if (cameraId == null) {
            clearActiveIfSame(state)
            return
        }
        ensureTorchCallback(cameraManager, cameraId)

        blinkMutex.withLock {
            val wakeLock = acquireBoundedWakeLock(wakeLockMs)
            val restoreTorchOn = externalTorchOn
            val activePulseState = !restoreTorchOn
            val pausePulseState = restoreTorchOn
            val myGen = blinkGeneration.incrementAndGet()
            activeBlinkGen.set(myGen)
            try {
                for (index in patternMs.indices) {
                    val targetOn = if (index % 2 == 0) activePulseState else pausePulseState
                    if (!setTorchSafe(cameraManager, cameraId, targetOn)) {
                        clearActiveIfSame(state)
                        return@withLock
                    }
                    delay(patternMs[index])
                }
            } finally {
                setTorchSafe(cameraManager, cameraId, restoreTorchOn)
                val posted = try {
                    callbackHandler.post { activeBlinkGen.compareAndSet(myGen, 0) }
                } catch (e: Exception) {
                    Log.w(TAG, "Handler post для сброса метки вспышки не удался: ${e.message}")
                    false
                }
                if (!posted) {
                    activeBlinkGen.compareAndSet(myGen, 0)
                }
                releaseWakeLockSafe(wakeLock)
            }
        }
    }

    private fun clearActiveIfSame(state: ActiveBlinkState) {
        synchronized(stateLock) {
            if (activeState === state) {
                activeState = null
            }
        }
    }

    private fun ensureTorchCallback(cameraManager: CameraManager, cameraId: String) {
        if (observedCameraId == cameraId) return
        synchronized(stateLock) {
            if (observedCameraId == cameraId) return
            try {
                cameraManager.registerTorchCallback(
                    object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(id: String, enabled: Boolean) {
                            if (id == cameraId && activeBlinkGen.get() == 0) {
                                externalTorchOn = enabled
                            }
                        }
                    },
                    callbackHandler,
                )
                observedCameraId = cameraId
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось зарегистрировать TorchCallback: ${e.message}")
            }
        }
    }

    private fun setTorchSafe(
        cameraManager: CameraManager,
        cameraId: String,
        enabled: Boolean,
    ): Boolean = try {
        cameraManager.setTorchMode(cameraId, enabled)
        true
    } catch (e: CameraAccessException) {
        Log.w(TAG, "Фонарик недоступен (камера занята или отключена): ${e.message}")
        false
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Камера фонарика больше не действительна: ${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Система отклонила переключение фонарика: ${e.message}")
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "Ошибка переключения фонарика: ${e.message}")
        false
    }

    private fun acquireBoundedWakeLock(timeoutMs: Long): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось взять WakeLock для вспышки: ${e.message}")
            null
        }
    }

    private fun releaseWakeLockSafe(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось освободить WakeLock вспышки: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FlashlightBlinker"
        private const val WAKE_LOCK_TAG = "APU:FlashlightAlert"

        /** Есть ли на устройстве камера со вспышкой (для подсказки в настройках). */
        fun hasFlash(context: Context): Boolean {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return false
            return findTorchCameraId(manager) != null
        }

        /** Предпочитаем заднюю камеру со вспышкой, иначе любую доступную со вспышкой. */
        fun findTorchCameraId(cameraManager: CameraManager): String? {
            return try {
                var fallbackId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (!flashAvailable) continue
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return id
                    }
                    if (fallbackId == null) {
                        fallbackId = id
                    }
                }
                fallbackId
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Не удалось прочитать список камер для фонарика: ${e.message}")
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "Ошибка опроса камер для фонарика: ${e.message}")
                null
            }
        }
    }
}
