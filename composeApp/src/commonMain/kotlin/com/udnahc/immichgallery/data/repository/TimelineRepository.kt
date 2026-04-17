package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TimelineRepository(
    private val apiService: ImmichApiService,
    private val timelineDao: TimelineDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository
) {
    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    // --- Reactive Room reads (UI observes these) ---

    fun observeBuckets(): Flow<List<TimelineBucket>> =
        timelineDao.observeBuckets()
            .map { entities -> entities.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun observeBucketAssets(timeBucket: String): Flow<List<Asset>> =
        assetDao.observeTimelineAssets(timeBucket)
            .map { entities ->
                val base = baseUrl()
                entities.map { it.toDomain(base) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads (for imperative access) ---

    suspend fun getCachedBuckets(): List<TimelineBucket> {
        return timelineDao.getAllBuckets().map { it.toDomain() }
    }

    suspend fun getAssetsForBucket(timeBucket: String): List<Asset> {
        val base = baseUrl()
        return assetDao.getTimelineAssets(timeBucket).map { it.toDomain(base) }
    }

    suspend fun hasCachedBuckets(): Boolean =
        timelineDao.getBucketCount() > 0

    suspend fun getLoadedBucketIds(): Set<String> =
        assetDao.getLoadedTimelineBuckets().toSet()

    suspend fun isBucketLoaded(timeBucket: String): Boolean {
        return assetDao.getTimelineAssetCount(timeBucket) > 0
    }

    suspend fun getLastSyncedAt(): Long? {
        return syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_BUCKETS)
    }

    // --- Network sync (writes to Room, Flows auto-emit) ---

    suspend fun syncBuckets(): Result<List<TimelineBucket>> {
        return try {
            val responses = apiService.getTimelineBuckets()
            val entities = responses.mapIndexed { index, response ->
                response.toEntity(index)
            }
            withContext(Dispatchers.IO) {
                timelineDao.replaceBuckets(entities)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        SYNC_SCOPE_TIMELINE_BUCKETS,
                        currentEpochMillis()
                    )
                )
            }
            Result.success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncBucketAssets(timeBucket: String): Result<Unit> {
        return try {
            val allAssets = apiService.getTimelineBucket(timeBucket)
            val filtered = allAssets.filter { it.visibility != "hidden" }
            val assetEntities = filtered.map { it.toAssetEntity() }
            val crossRefs = filtered.mapIndexed { index, response ->
                TimelineAssetCrossRef(timeBucket, response.id, index)
            }
            withContext(Dispatchers.IO) {
                assetDao.upsertAssets(assetEntities)
                timelineDao.replaceBucketRefs(timeBucket, crossRefs)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        return response.originalFileName
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_TIMELINE_BUCKETS = "timeline_buckets"
    }
}
