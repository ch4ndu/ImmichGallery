package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

class TimelineRepository(
    private val apiService: ImmichApiService,
    private val timelineDao: TimelineDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val editsEnricher: AssetEditsEnricher
) {
    private val log = logging("TimelineRepository")

    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    // --- Reactive Room reads (UI observes these) ---

    fun observeBuckets(): Flow<List<TimelineBucket>> =
        timelineDao.observeBuckets()
            .map { entities -> entities.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- Suspend reads (for imperative access) ---

    suspend fun getAssetsForBucket(timeBucket: String): List<Asset> {
        val base = baseUrl()
        return assetDao.getTimelineAssets(timeBucket).map { it.toDomain(base) }
    }

    suspend fun hasCachedBuckets(): Boolean =
        timelineDao.getBucketCount() > 0

    suspend fun getLoadedBucketIds(): Set<String> =
        assetDao.getLoadedTimelineBuckets().toSet()

    suspend fun getLastSyncedAt(): Long? {
        return syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_BUCKETS)
    }

    // --- Network sync (writes to Room, Flows auto-emit) ---

    suspend fun syncBuckets(): Result<TimelineBucketSyncResult> {
        return try {
            val responses = apiService.getTimelineBuckets()
            val entities = responses.mapIndexed { index, response ->
                response.toEntity(index)
            }
            val oldEntities = withContext(Dispatchers.IO) {
                timelineDao.getBuckets()
            }
            val oldById = oldEntities.associateBy { it.timeBucket }
            val newIds = entities.map { it.timeBucket }.toSet()
            val staleBucketIds = entities
                .filter { entity ->
                    val old = oldById[entity.timeBucket]
                    old != null && old.count != entity.count
                }
                .map { it.timeBucket }
                .toSet()
            val removedBucketIds = oldById.keys - newIds
            withContext(Dispatchers.IO) {
                (staleBucketIds + removedBucketIds).forEach { timeBucket ->
                    timelineDao.clearTimelineRefsForBucket(timeBucket)
                }
                timelineDao.replaceBuckets(entities)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        SYNC_SCOPE_TIMELINE_BUCKETS,
                        currentEpochMillis()
                    )
                )
            }
            Result.success(
                TimelineBucketSyncResult(
                    buckets = entities.map { it.toDomain() },
                    staleBucketIds = staleBucketIds,
                    removedBucketIds = removedBucketIds
                )
            )
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
            // Timeline bucket responses currently come from Immich's columnar
            // endpoint, which does not include edit metadata or original
            // dimensions. This hook enriches when that data is available and
            // otherwise no-ops; keep it here so lazy mosaic builds can use
            // edited aspect ratios when the endpoint/model can provide them.
            editsEnricher.enrich(filtered)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncAllBucketAssets(
        buckets: List<TimelineBucket>
    ): Result<TimelineAssetSyncResult> {
        return try {
            log.d { "Starting timeline asset sync for ${buckets.size} buckets with parallelism=$FULL_SYNC_BUCKET_PARALLELISM" }
            val semaphore = Semaphore(FULL_SYNC_BUCKET_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val results = coroutineScope {
                buckets.map { bucket ->
                    async(Dispatchers.IO) {
                        val success = semaphore.withPermit {
                            syncBucketAssets(bucket.timeBucket).isSuccess
                        }
                        progressMutex.withLock {
                            completed++
                            if (success) successful++ else failed++
                            log.d {
                                "Timeline asset sync progress $completed/${buckets.size}: " +
                                    "${bucket.timeBucket} success=$success successful=$successful failed=$failed"
                            }
                        }
                        bucket.timeBucket to success
                    }
                }.awaitAll()
            }
            log.d { "Finished timeline asset sync: successful=$successful failed=$failed total=${buckets.size}" }
            Result.success(
                TimelineAssetSyncResult(
                    successfulBucketIds = results.filter { it.second }.map { it.first }.toSet(),
                    failedBucketIds = results.filterNot { it.second }.map { it.first }.toSet()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        return response.originalFileName
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            timelineDao.clearBuckets()
            timelineDao.clearAllTimelineRefs()
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_TIMELINE_BUCKETS = "timeline_buckets"
        private const val FULL_SYNC_BUCKET_PARALLELISM = 4
    }
}
