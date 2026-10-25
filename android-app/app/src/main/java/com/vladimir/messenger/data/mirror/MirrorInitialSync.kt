package com.vladimir.messenger.data.mirror

/** The user-visible lifecycle of the first synchronization after pairing. */
enum class MirrorInitialSyncPhase {
    IDLE,
    STARTING,
    CONNECTING,
    TRANSFERRING,
    COMPLETE,
    NO_DATA,
    ERROR,
}

data class MirrorInitialSyncState(
    val phase: MirrorInitialSyncPhase = MirrorInitialSyncPhase.IDLE,
    val detail: String = "",
    val startedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

/**
 * Small, platform-independent state machine used by the channel and its tests.
 * A successful pairing must enter STARTING before the UI can ever say that the
 * device is merely connected.
 */
class MirrorInitialSyncTracker {
    private var state = MirrorInitialSyncState()
    private val pendingParts = linkedSetOf<String>()
    private var receivedData = false

    @Synchronized
    fun begin(nowMs: Long = System.currentTimeMillis()): MirrorInitialSyncState {
        pendingParts.clear()
        pendingParts += setOf("messages", "contacts", "profile", "files")
        receivedData = false
        state = MirrorInitialSyncState()
        return update(MirrorInitialSyncPhase.STARTING, "Синхронизация запускается", nowMs)
    }

    @Synchronized
    fun connected(nowMs: Long = System.currentTimeMillis()): MirrorInitialSyncState =
        update(MirrorInitialSyncPhase.CONNECTING, "Устанавливаем канал синхронизации", nowMs)

    @Synchronized
    fun transferring(nowMs: Long = System.currentTimeMillis()): MirrorInitialSyncState =
        update(MirrorInitialSyncPhase.TRANSFERRING, "Передаём чаты, профиль, контакты и файлы", nowMs)

    @Synchronized
    fun part(name: String, hasData: Boolean, nowMs: Long = System.currentTimeMillis()): MirrorInitialSyncState {
        if (pendingParts.isEmpty()) return state
        pendingParts.remove(name)
        receivedData = receivedData || hasData
        if (pendingParts.isNotEmpty()) {
            return update(MirrorInitialSyncPhase.TRANSFERRING, state.detail, nowMs)
        }
        return update(
            if (receivedData) MirrorInitialSyncPhase.COMPLETE else MirrorInitialSyncPhase.NO_DATA,
            if (receivedData) "Начальная синхронизация завершена" else "На втором устройстве пока нет данных для передачи",
            nowMs,
        )
    }

    @Synchronized
    fun fail(message: String, nowMs: Long = System.currentTimeMillis()): MirrorInitialSyncState =
        update(MirrorInitialSyncPhase.ERROR, message.ifBlank { "Начальная синхронизация завершилась ошибкой" }, nowMs)

    @Synchronized
    fun snapshot(): MirrorInitialSyncState = state

    private fun update(
        phase: MirrorInitialSyncPhase,
        detail: String,
        nowMs: Long,
    ): MirrorInitialSyncState {
        state = state.copy(
            phase = phase,
            detail = detail,
            startedAtMs = state.startedAtMs.takeIf { it > 0L } ?: nowMs,
            updatedAtMs = nowMs,
        )
        return state
    }
}
