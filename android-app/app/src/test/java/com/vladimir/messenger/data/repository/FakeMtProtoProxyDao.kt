package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory fake mirroring Room semantics (REPLACE insert, active flag, counters) for JVM tests. */
class FakeMtProtoProxyDao : MtProtoProxyDao {
    private val rows = LinkedHashMap<String, MtProtoProxyEntity>()
    private val tick = MutableStateFlow(0)

    override fun observeAll(): Flow<List<MtProtoProxyEntity>> =
        tick.map { rows.values.sortedByDescending { e -> e.isActive } }

    override suspend fun getAll(): List<MtProtoProxyEntity> = rows.values.toList()

    override suspend fun getActive(): MtProtoProxyEntity? = rows.values.firstOrNull { it.isActive }

    override suspend fun getById(id: String): MtProtoProxyEntity? = rows[id]

    override suspend fun insert(proxy: MtProtoProxyEntity) {
        rows[proxy.id] = proxy
        tick.value += 1
    }

    override suspend fun update(proxy: MtProtoProxyEntity) {
        rows[proxy.id] = proxy
        tick.value += 1
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        tick.value += 1
    }

    override suspend fun clearActive() {
        rows.replaceAll { _, e -> e.copy(isActive = false) }
    }

    override suspend fun setActive(id: String) {
        rows[id] = requireNotNull(rows[id]).copy(isActive = true)
    }

    override suspend fun incFailCount(id: String, ts: Long) {
        rows[id]?.let { rows[id] = it.copy(failCount = it.failCount + 1, lastCheck = ts) }
    }

    override suspend fun incSuccessCount(id: String, ts: Long) {
        rows[id]?.let { rows[id] = it.copy(successCount = it.successCount + 1, failCount = 0, lastCheck = ts) }
    }

    override suspend fun cleanupDead(maxAgeMs: Long, now: Long): Int {
        val doomed = rows.values
            .filter { it.failCount >= 3 && (now - it.lastCheck) > maxAgeMs && it.source != "MANUAL" }
        doomed.forEach { rows.remove(it.id) }
        return doomed.size
    }
}
