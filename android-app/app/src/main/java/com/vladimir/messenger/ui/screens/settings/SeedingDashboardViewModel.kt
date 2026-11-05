package com.vladimir.messenger.ui.screens.settings

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.R
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SeedingFileStatus(
    val transferId: String,
    val name: String,
    val scope: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedBytes: Long,
    val bytesPerSecond: Long,
    val activePeerIds: List<String>,
    val completedPeers: Int,
    val expiresAtMs: Long,
)

internal data class SeedingDashboardState(
    val files: List<SeedingFileStatus> = emptyList(),
    val totalSpeedBytesPerSecond: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val activePeers: Int = 0,
)

@HiltViewModel
class SeedingDashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferDao: FileTransferDao,
    private val groupDao: GroupDao,
    private val router: FileTransferRouter,
) : ViewModel() {
    private data class Sample(val bytes: Long, val atMs: Long)

    private val _state = MutableStateFlow(SeedingDashboardState())
    internal val state = _state.asStateFlow()
    private val previous = mutableMapOf<String, Sample>()
    private val scopeNames = mutableMapOf<String, String>()

    init {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            combine(transferDao.observeSeedingTransfers(MAX_SEEDING_ROWS), ticker) { rows, _ -> rows }
                .collect { rows ->
                    _state.value = withContext(Dispatchers.IO) { makeSnapshot(rows) }
                }
        }
    }

    private suspend fun makeSnapshot(rows: List<FileTransferEntity>): SeedingDashboardState {
        val activeIds = router.groupSeeder.activeTransferIds()
        val activeRows = if (activeIds.isEmpty()) emptyList() else transferDao.getTransfersByIds(activeIds)
        val visibleRows = (rows + activeRows).distinctBy { it.transferId }
        val now = SystemClock.elapsedRealtime()
        val files = visibleRows.map { row ->
            val traffic = router.groupSeeder.trafficSnapshot(row.transferId)
            val before = previous.put(row.transferId, Sample(traffic.uploadedBytes, now))
            val rate = if (before == null || now <= before.atMs) 0L else {
                ((traffic.uploadedBytes - before.bytes).coerceAtLeast(0L) * 1000L) / (now - before.atMs)
            }
            SeedingFileStatus(
                transferId = row.transferId,
                name = row.displayName,
                scope = scopeName(row.chatId),
                sizeBytes = row.totalBytes,
                sha256 = row.fileSha256,
                uploadedBytes = traffic.uploadedBytes,
                bytesPerSecond = rate,
                activePeerIds = traffic.activeRequesterIds,
                completedPeers = traffic.completedRequesterCount,
                expiresAtMs = row.expiresAtMs,
            )
        }
        previous.keys.retainAll(visibleRows.mapTo(mutableSetOf()) { it.transferId })
        return SeedingDashboardState(
            files = files,
            totalSpeedBytesPerSecond = files.sumOf { it.bytesPerSecond },
            totalUploadedBytes = files.sumOf { it.uploadedBytes },
            activePeers = files.flatMap { it.activePeerIds }.distinct().size,
        )
    }

    private suspend fun scopeName(chatId: String): String {
        scopeNames[chatId]?.let { return it }
        val name = when (chatId) {
            "apkseed" -> context.getString(R.string.seed_dashboard_scope_apk)
            "packseed" -> context.getString(R.string.seed_dashboard_scope_packs)
            else -> groupDao.getGroupById(chatId)?.title ?: chatId.takeLast(10)
        }
        scopeNames[chatId] = name
        return name
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val MAX_SEEDING_ROWS = 200
    }
}
