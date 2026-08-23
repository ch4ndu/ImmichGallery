package com.udnahc.immichgallery.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.remote.TimelineApiCompatibilityException
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncCompatibilityException
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketSnapshot
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

class TimelineRepository(
    private val database: AppDatabase,
    private val apiService: ImmichApiService,
    private val timelineDao: TimelineDao,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val editsEnricher: AssetEditsEnricher,
    private val sessionStateRepository: SessionStateRepository
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
        return withContext(Dispatchers.IO) {
            assetDao.getTimelineAssets(timeBucket).map { it.toDomain(base) }
        }
    }

    suspend fun getBucketSnapshot(timeBucket: String): TimelineBucketSnapshot {
        val base = baseUrl()
        return withContext(Dispatchers.IO) {
            val assets = assetDao.getTimelineAssets(timeBucket).map { it.toDomain(base) }
            TimelineBucketSnapshot(
                timeBucket = timeBucket,
                assets = assets,
                expectedCount = timelineDao.getBucketAssetCount(timeBucket)
            )
        }
    }

    suspend fun hasCachedBuckets(): Boolean =
        withContext(Dispatchers.IO) {
            timelineDao.getBucketCount() > 0
        }

    suspend fun hasCompletedColdTimelineSync(): Boolean =
        withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_COLD_COMPLETE) != null
        }

    suspend fun markColdTimelineSyncComplete() {
        sessionStateRepository.withOperation { token ->
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            SYNC_SCOPE_TIMELINE_COLD_COMPLETE,
                            currentEpochMillis()
                        )
                    )
                }
            }
        }
    }

    suspend fun getLoadedBucketIds(): Set<String> =
        withContext(Dispatchers.IO) {
            assetDao.getLoadedTimelineBuckets().toSet()
        }

    suspend fun getLastSyncedAt(): Long? {
        return withContext(Dispatchers.IO) {
            syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_BUCKETS)
        }
    }

    // --- Network sync (writes to Room, Flows auto-emit) ---

    suspend fun syncBuckets(): Result<TimelineBucketSyncResult> {
        return sessionStateRepository.withOperation { token -> try {
            log.d { "Timeline bucket metadata repository sync started" }
            val responses = apiService.getTimelineBuckets()
            log.d { "Timeline bucket metadata fetched responseCount=<redacted>" }
            val entities = responses.mapIndexed { index, response ->
                response.toEntity(index)
            }
            val oldEntities = withContext(Dispatchers.IO) {
                timelineDao.getBuckets()
            }
            val metadataChanges = timelineBucketMetadataChanges(oldEntities, entities)
            val staleBucketIds = metadataChanges.staleBucketIds
            val removedBucketIds = metadataChanges.removedBucketIds
            log.d {
                "Timeline bucket metadata changes stale=<redacted> " +
                        "removed=<redacted> changed=<redacted> " +
                        "previous=<redacted> next=<redacted>"
            }
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                        if (metadataChanges.metadataChanged) {
                            // Count-changed buckets keep their old refs so cached Timeline
                            // rows remain visible until that bucket's asset refresh
                            // succeeds. Removed buckets are the only metadata-sync case
                            // that can safely clear relationship rows immediately.
                            removedBucketIds.forEach { timeBucket ->
                                timelineDao.clearTimelineRefsForBucket(timeBucket)
                            }
                            timelineDao.replaceBuckets(entities)
                        }
                        syncMetadataDao.upsert(
                            SyncMetadataEntity(
                                SYNC_SCOPE_TIMELINE_BUCKETS,
                                currentEpochMillis()
                            )
                        )
                        }
                    }
                }
            }
            log.d {
                "Timeline bucket metadata sync metadata write completed " +
                        "buckets=<redacted> changed=<redacted>"
            }
            Result.success(
                TimelineBucketSyncResult(
                    buckets = entities.map { it.toDomain() },
                    staleBucketIds = staleBucketIds,
                    removedBucketIds = removedBucketIds,
                    metadataChanged = metadataChanges.metadataChanged
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } }
    }

    suspend fun syncBucketAssets(timeBucket: String): Result<TimelineBucketAssetSyncResult> {
        return sessionStateRepository.withOperation { token -> try {
            log.d { "Timeline bucket asset sync started bucket=<redacted>" }
            // Compare the persisted bucket before and after the full write path,
            // including edit enrichment below. The ViewModel uses this changed
            // bit to decide whether row packing and Mosaic assignments for this
            // one bucket need a new revision.
            val before = withContext(Dispatchers.IO) {
                assetDao.getTimelineAssets(timeBucket)
            }
            val allAssets = apiService.getTimelineBucket(timeBucket)
            val filtered = allAssets.filter { it.visibility != "hidden" }
            log.d {
                "Timeline bucket asset sync fetched bucket=<redacted> fetched=<redacted> " +
                        "visible=<redacted> hidden=<redacted>"
            }
            val initialAssetEntities = filtered.map { it.toAssetEntity() }
            val assetEntities = resolveTimelineEditAspects(
                responses = filtered,
                candidateAssets = initialAssetEntities,
                cachedAssets = before,
                editsEnricher = editsEnricher
            )
            val crossRefs = filtered.mapIndexed { index, response ->
                TimelineAssetCrossRef(timeBucket, response.id, index)
            }
            val editedAssetIdsWithDimensions = filtered
                .filter { it.isEdited && it.width != null && it.height != null }
                .map { it.id }
                .toSet()
            if (shouldSkipTimelineBucketAssetWrite(
                    before = before,
                    candidateAssets = assetEntities,
                    editedAssetIdsWithDimensions = editedAssetIdsWithDimensions
                )
            ) {
                log.d {
                    "Timeline bucket asset sync skipped unchanged write bucket=<redacted> assets=<redacted>"
                }
                return@withOperation Result.success(
                    TimelineBucketAssetSyncResult(
                        timeBucket = timeBucket,
                        changed = false
                    )
                )
            }
            val changed = timelineBucketAssetsChanged(before, assetEntities)
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                            assetDao.upsertAssets(assetEntities)
                            timelineDao.replaceBucketRefs(timeBucket, crossRefs)
                            if (changed) {
                                clearTimelineMosaicArtifactsForBucket(timeBucket)
                            }
                        }
                    }
                }
            }
            log.d {
                "Timeline bucket asset sync completed bucket=<redacted> previous=<redacted> " +
                        "current=<redacted> changed=<redacted>"
            }
            Result.success(
                TimelineBucketAssetSyncResult(
                    timeBucket = timeBucket,
                    changed = changed
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "Timeline bucket asset sync failed bucket=<redacted>" }
            Result.failure(e)
        } }
    }

    private suspend fun clearTimelineMosaicArtifactsForBucket(timeBucket: String) {
        val bucketIds = listOf(timeBucket)
        timelineDao.clearMosaicAssignmentsForBuckets(bucketIds)
        timelineDao.clearMosaicGeometryForBuckets(bucketIds)
        timelineDao.clearMosaicDisplayCacheForBuckets(bucketIds)
        timelineDao.clearBucketGeometryForBuckets(bucketIds)
    }

    suspend fun syncAllBucketAssets(
        buckets: List<TimelineBucket>
    ): Result<TimelineAssetSyncResult> {
        return sessionStateRepository.withOperation { try {
            log.d { "Starting timeline asset sync for <redacted> buckets with parallelism=<redacted>" }
            val semaphore = Semaphore(FULL_SYNC_BUCKET_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val syncResult = syncTimelineBucketAssetsConcurrently(
                buckets = buckets,
                syncBucketAssets = { bucket ->
                    semaphore.withPermit {
                        syncBucketAssets(bucket.timeBucket)
                    }
                },
                onProgress = { success ->
                    progressMutex.withLock {
                        completed++
                        if (success) successful++ else failed++
                        log.d {
                            "Timeline asset sync progress <redacted>/<redacted>: " +
                                    "<redacted> success=<redacted> successful=<redacted> failed=<redacted>"
                        }
                    }
                }
            )
            syncResult.exceptionOrNull()?.let { error ->
                return@withOperation Result.failure(error)
            }
            val assetResult = syncResult.getOrThrow()
            log.d { "Finished timeline asset sync: successful=<redacted> failed=<redacted> total=<redacted>" }
            Result.success(assetResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } }
    }

    suspend fun getAssetFileName(id: String): String {
        val response = apiService.getAssetInfo(id)
        return response.originalFileName
    }

    internal suspend fun clearCacheForSessionMutation() {
        withContext(Dispatchers.IO) {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    timelineDao.clearBuckets()
                    timelineDao.clearAllTimelineRefs()
                }
            }
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_TIMELINE_BUCKETS = "timeline_buckets"
        const val SYNC_SCOPE_TIMELINE_COLD_COMPLETE = "timeline_cold_complete"
        private const val FULL_SYNC_BUCKET_PARALLELISM = 4
    }
}

internal suspend fun syncTimelineBucketAssetsConcurrently(
    buckets: List<TimelineBucket>,
    syncBucketAssets: suspend (TimelineBucket) -> Result<TimelineBucketAssetSyncResult>,
    onProgress: suspend (Boolean) -> Unit = {}
): Result<TimelineAssetSyncResult> {
    data class BucketSyncOutcome(
        val timeBucket: String,
        val result: TimelineBucketAssetSyncResult?,
        val compatibilityError: TimelineApiCompatibilityException?
    )

    val outcomes = coroutineScope {
        buckets.map { bucket ->
            async(Dispatchers.IO) {
                try {
                    val result = syncBucketAssets(bucket)
                    val compatibilityError = result.exceptionOrNull() as? TimelineApiCompatibilityException
                    if (compatibilityError != null) {
                        onProgress(false)
                        return@async BucketSyncOutcome(bucket.timeBucket, null, compatibilityError)
                    }
                    val bucketResult = result.getOrNull()
                    onProgress(bucketResult != null)
                    BucketSyncOutcome(bucket.timeBucket, bucketResult, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onProgress(false)
                    val compatibilityError = e as? TimelineApiCompatibilityException
                    BucketSyncOutcome(bucket.timeBucket, null, compatibilityError)
                }
            }
        }.awaitAll()
    }
    val partialResult = TimelineAssetSyncResult(
        successfulBucketIds = outcomes.filter { it.result != null }.map { it.timeBucket }.toSet(),
        failedBucketIds = outcomes.filter { it.result == null }.map { it.timeBucket }.toSet(),
        changedBucketIds = outcomes.mapNotNull { it.result }
            .filter { it.changed }
            .map { it.timeBucket }
            .toSet()
    )
    outcomes.mapNotNull { it.compatibilityError }.firstOrNull()?.let { error ->
        return Result.failure(TimelineAssetSyncCompatibilityException(partialResult, error))
    }
    return Result.success(partialResult)
}

private suspend fun resolveTimelineEditAspects(
    responses: List<com.udnahc.immichgallery.data.model.AssetResponse>,
    candidateAssets: List<AssetEntity>,
    cachedAssets: List<AssetEntity>,
    editsEnricher: AssetEditsEnricher
): List<AssetEntity> {
    val cachedById = cachedAssets.associateBy { it.id }
    val candidatesByResponse = responses.zip(candidateAssets)
    val aspectsById = editsEnricher.resolve(
        candidatesByResponse
            .filterNot { (_, candidate) -> canReuseResolvedTimelineEdit(candidate, cachedById[candidate.id]) }
            .map { (response) -> response }
    )
    return candidatesByResponse.map { (_, candidate) ->
        val cached = cachedById[candidate.id]
        val reusableAspect = cached
            ?.takeIf { canReuseResolvedTimelineEdit(candidate, it) }
            ?.aspectRatio
        when {
            reusableAspect != null -> candidate.copy(
                aspectRatio = reusableAspect,
                editsResolved = true
            )

            aspectsById[candidate.id] != null -> candidate.copy(
                aspectRatio = aspectsById.getValue(candidate.id),
                editsResolved = true
            )

            else -> candidate
        }
    }
}

private fun canReuseResolvedTimelineEdit(
    candidate: AssetEntity,
    cached: AssetEntity?
): Boolean =
    candidate.isEdited &&
        cached?.isEdited == true &&
        cached.editsResolved &&
        candidate.id == cached.id &&
        candidate.type == cached.type &&
        candidate.fileName == cached.fileName &&
        candidate.createdAt == cached.createdAt &&
        candidate.isFavorite == cached.isFavorite &&
        candidate.stackCount == cached.stackCount

internal data class TimelineBucketMetadataChanges(
    val metadataChanged: Boolean,
    val staleBucketIds: Set<String>,
    val removedBucketIds: Set<String>
)

internal fun timelineBucketMetadataChanges(
    oldEntities: List<TimelineBucketEntity>,
    newEntities: List<TimelineBucketEntity>
): TimelineBucketMetadataChanges {
    val oldById = oldEntities.associateBy { it.timeBucket }
    val newIds = newEntities.map { it.timeBucket }.toSet()
    val staleBucketIds = newEntities
        .filter { entity ->
            val old = oldById[entity.timeBucket]
            old != null && old.count != entity.count
        }
        .map { it.timeBucket }
        .toSet()
    return TimelineBucketMetadataChanges(
        metadataChanged = oldEntities != newEntities,
        staleBucketIds = staleBucketIds,
        removedBucketIds = oldById.keys - newIds
    )
}

// Timeline sync may write the same rows on every app launch. Layout invalidation
// must be based on content that can affect the grid, not on write success. The
// input lists are already ordered by timeline_asset_refs.sortOrder.
internal fun timelineBucketAssetsChanged(
    before: List<com.udnahc.immichgallery.data.local.entity.AssetEntity>,
    after: List<com.udnahc.immichgallery.data.local.entity.AssetEntity>
): Boolean =
    orderedAssetsChanged(before, after)

internal fun shouldSkipTimelineBucketAssetWrite(
    before: List<AssetEntity>,
    candidateAssets: List<AssetEntity>,
    editedAssetIdsWithDimensions: Set<String>
): Boolean {
    val orderedSame = !orderedAssetsChanged(before, candidateAssets)
    val refsSame = before.map { it.id } == candidateAssets.map { it.id }
    if (!orderedSame || !refsSame) return false
    val cachedById = before.associateBy { it.id }
    val needsEditEnrichment = editedAssetIdsWithDimensions.any { assetId ->
        cachedById[assetId]?.editsResolved != true
    }
    return !needsEditEnrichment
}
