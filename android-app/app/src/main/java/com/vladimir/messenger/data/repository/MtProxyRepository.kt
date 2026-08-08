package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.MtProtoProxyDao
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import com.vladimir.messenger.domain.model.MtProtoProxy
import com.vladimir.messenger.domain.model.ProxySource
import com.vladimir.messenger.domain.model.ProxyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MtProxyRepository @Inject constructor(
    private val dao: MtProtoProxyDao,
) {
    companion object {
        private const val TAG = "MtProxyRepository"
        const val MAX_FAIL_COUNT = 3
        const val MAX_AGE_DAYS = 7
        const val MIN_POOL_SIZE = 10
    }

    fun observeAll(): Flow<List<MtProtoProxy>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<MtProtoProxy> =
        dao.getAll().map { it.toDomain() }

    suspend fun getActive(): MtProtoProxy? =
        dao.getActive()?.toDomain()

    suspend fun addFromString(input: String, source: String = "MANUAL"): String? {
        val entity = MtProxyParser.parse(input, source) ?: return null

        val existing = dao.getById(entity.id)
        if (existing != null) {
            Log.d(TAG, "Proxy already exists: ${entity.host}:${entity.port}")
            return entity.id
        }

        dao.insert(entity)
        Log.i(TAG, "Added proxy: ${entity.host}:${entity.port} (source=$source)")
        return entity.id
    }

    suspend fun importMultiple(text: String, source: String = "IMPORT"): Int {
        val entities = MtProxyParser.parseMultiple(text, source)
        var added = 0

        for (entity in entities) {
            val existing = dao.getById(entity.id)
            if (existing == null) {
                dao.insert(entity)
                added++
                Log.d(TAG, "Imported: ${entity.host}:${entity.port}")
            }
        }

        Log.i(TAG, "Imported $added proxies (total ${entities.size} parsed)")
        return added
    }

    suspend fun setActive(id: String) {
        dao.clearActive()
        dao.setActive(id)
        Log.i(TAG, "Set active proxy: $id")
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        Log.i(TAG, "Deleted proxy: $id")
    }

    suspend fun markFailed(id: String) {
        dao.incFailCount(id, System.currentTimeMillis())
        Log.w(TAG, "Proxy marked as failed: $id")
    }

    suspend fun markSuccess(id: String) {
        dao.incSuccessCount(id, System.currentTimeMillis())
        Log.d(TAG, "Proxy marked as success: $id")
    }


    /**
     * Вставить entity напрямую (для Collector).
     */
    suspend fun insertEntity(entity: MtProtoProxyEntity) {
        dao.insert(entity)
    }

    suspend fun getById(id: String): MtProtoProxy? =
        dao.getById(id)?.toDomain()


    /**
     * Удалить прокси которые не проверялись > 7 дней и не имеют успешных проверок.
     */
    suspend fun cleanupStale(maxAgeDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        val all = getAll()
        var deleted = 0
        for (proxy in all) {
            if (proxy.successCount == 0 && proxy.addedAt < cutoff) {
                delete(proxy.id)
                deleted++
            }
        }
        Log.i("MtProxyRepository", "Cleanup stale: removed $deleted proxies older than ${maxAgeDays} days")
        return deleted
    }

    /**
     * Ограничить размер пула: удалить самые старые если больше limit.
     */
    suspend fun enforcePoolLimit(limit: Int = 500): Int {
        val all = getAll().sortedByDescending { 
            // Приоритет: активный > рабочие > свежие
            (if (it.isActive) 1000000L else 0L) + it.successCount * 1000L + it.addedAt / 1000L
        }
        var deleted = 0
        if (all.size > limit) {
            val toDelete = all.drop(limit)
            for (proxy in toDelete) {
                delete(proxy.id)
                deleted++
            }
            Log.i("MtProxyRepository", "Enforce limit: removed $deleted excess proxies (pool now $limit)")
        }
        return deleted
    }

    suspend fun cleanupDead(): Int {
        val maxAgeMs = MAX_AGE_DAYS * 24 * 3600 * 1000L
        val deleted = dao.cleanupDead(maxAgeMs)
        if (deleted > 0) {
            Log.i(TAG, "Cleaned up $deleted dead proxies")
        }
        return deleted
    }

    suspend fun needsMoreProxies(): Boolean {
        val count = dao.getAll().size
        return count < MIN_POOL_SIZE
    }

    private fun MtProtoProxyEntity.toDomain() = MtProtoProxy(
        id = id,
        host = host,
        port = port,
        secret = secret,
        username = username,
        password = password,
        type = try { ProxyType.valueOf(type) } catch (e: Exception) { ProxyType.MTProto },
        source = try { ProxySource.valueOf(source) } catch (e: Exception) { ProxySource.MANUAL },
        addedAt = addedAt,
        lastCheck = lastCheck,
        failCount = failCount,
        successCount = successCount,
        isActive = isActive,
    )
}
