package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketGeometryEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicGeometryEntity
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicAssignment
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForHeight
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildMosaicAssignmentsWithProgress
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.mosaicAssignmentLayoutSpec
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.timelineDayMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMonthMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import com.udnahc.immichgallery.domain.model.toEntity
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import kotlinx.coroutines.CancellationException
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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
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

    suspend fun hasCompletedColdTimelineSync(): Boolean =
        syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_COLD_COMPLETE) != null

    suspend fun markColdTimelineSyncComplete() {
        syncMetadataDao.upsert(
            SyncMetadataEntity(
                SYNC_SCOPE_TIMELINE_COLD_COMPLETE,
                currentEpochMillis()
            )
        )
    }

    suspend fun getLoadedBucketIds(): Set<String> =
        assetDao.getLoadedTimelineBuckets().toSet()

    suspend fun getPersistedBucketGeometry(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<List<TimelineBucketGeometrySummary>> {
        if (timeBuckets.isEmpty()) return Result.success(emptyList())
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            val widthKey = timelineMosaicGeometryWidthKey(geometryRequest.availableWidth)
            val maxRowHeightKey = timelineMosaicGeometryDimensionKey(geometryRequest.maxRowHeight)
            val spacingKey = timelineMosaicGeometryDimensionKey(geometryRequest.spacing)
            // Aggregate bucket geometry is intentionally trusted on warm launch:
            // validating fingerprints here would hydrate every cached bucket's
            // assets and undo the lazy materialization contract. Metadata
            // stale/removed handling and changed bucket sync clear stale rows.
            val rows = withContext(Dispatchers.IO) {
                timelineDao.getBucketGeometry(
                    timeBuckets = timeBuckets.toList(),
                    groupMode = groupSize.apiValue,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    availableWidthKey = widthKey,
                    geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION
                )
            }
            Result.success(
                rows
                    .filter {
                        it.maxRowHeightKey == maxRowHeightKey &&
                            it.spacingKey == spacingKey
                    }
                    .map { it.toDomain() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPersistedMosaicCacheStatus(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null
    ): Result<TimelineMosaicCacheStatus> {
        if (timeBuckets.isEmpty()) {
            return Result.success(
                TimelineMosaicCacheStatus(
                    assignments = emptyList(),
                    completeBucketIds = emptySet(),
                    missingBucketIds = emptySet()
                )
            )
        }
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            val groupMode = groupSize.apiValue
            val rows = withContext(Dispatchers.IO) {
                timelineDao.getMosaicAssignments(
                    timeBuckets = timeBuckets.toList(),
                    groupMode = groupMode,
                    columnCount = columnCount,
                    familiesKey = familiesKey
                )
            }
            val rowsByBucketSection = rows.associateBy { it.timeBucket to it.sectionKey }
            val geometryRowsByBucketSection = geometryRequest?.let { request ->
                val widthKey = timelineMosaicGeometryWidthKey(request.availableWidth)
                val maxRowHeightKey = timelineMosaicGeometryDimensionKey(request.maxRowHeight)
                val spacingKey = timelineMosaicGeometryDimensionKey(request.spacing)
                withContext(Dispatchers.IO) {
                    timelineDao.getMosaicGeometry(
                        timeBuckets = timeBuckets.toList(),
                        groupMode = groupMode,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        availableWidthKey = widthKey,
                        geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION
                    )
                }
                    .filter {
                        it.maxRowHeightKey == maxRowHeightKey &&
                            it.spacingKey == spacingKey
                    }
                    .associateBy { it.timeBucket to it.sectionKey }
            }.orEmpty()
            val validAssignments = mutableListOf<TimelineMosaicAssignment>()
            val validGeometry = mutableListOf<TimelineMosaicGeometrySummary>()
            val geometryBackfill = mutableListOf<TimelineMosaicGeometryEntity>()
            val completeBucketIds = mutableSetOf<String>()
            val missingBucketIds = mutableSetOf<String>()
            val now = currentEpochMillis()
            withContext(Dispatchers.IO) {
                for (timeBucket in timeBuckets) {
                    val entities = assetDao.getTimelineAssets(timeBucket)
                    val fingerprint = orderedAssetsFingerprint(entities)
                    val assets = entities.map { it.toDomain(baseUrl()) }
                    val expectedSections = timelineMosaicSections(timeBucket, groupSize, assets)
                    var complete = true
                    for (section in expectedSections) {
                        val row = rowsByBucketSection[timeBucket to section.sectionKey]
                        val assignments = row?.takeIf { it.assetFingerprint == fingerprint }
                            ?.decodeMosaicAssignments()
                        if (assignments == null) {
                            complete = false
                        } else {
                            validAssignments.add(TimelineMosaicAssignment(timeBucket, section.sectionKey, assignments))
                            val geometry = geometryRowsByBucketSection[timeBucket to section.sectionKey]
                                ?.takeIf { it.assetFingerprint == fingerprint }
                            if (geometry != null) {
                                validGeometry.add(geometry.toDomain())
                            } else if (geometryRequest != null) {
                                computeTimelineMosaicGeometryEntity(
                                    timeBucket = timeBucket,
                                    groupMode = groupMode,
                                    sectionKey = section.sectionKey,
                                    columnCount = columnCount,
                                    familiesKey = familiesKey,
                                    assetFingerprint = fingerprint,
                                    assets = section.assets,
                                    assignments = assignments,
                                    request = geometryRequest,
                                    now = now
                                )?.let { computed ->
                                    geometryBackfill.add(computed)
                                    validGeometry.add(computed.toDomain())
                                }
                            }
                        }
                    }
                    if (complete) {
                        completeBucketIds.add(timeBucket)
                    } else {
                        missingBucketIds.add(timeBucket)
                    }
                }
                if (geometryBackfill.isNotEmpty()) {
                    timelineDao.upsertMosaicGeometry(geometryBackfill)
                }
            }
            Result.success(
                TimelineMosaicCacheStatus(
                    assignments = validAssignments,
                    geometrySummaries = validGeometry,
                    completeBucketIds = completeBucketIds,
                    missingBucketIds = missingBucketIds
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLastSyncedAt(): Long? {
        return syncMetadataDao.getLastSyncedAt(SYNC_SCOPE_TIMELINE_BUCKETS)
    }

    // --- Network sync (writes to Room, Flows auto-emit) ---

    suspend fun syncBuckets(): Result<TimelineBucketSyncResult> {
        return try {
            log.d { "Timeline bucket metadata repository sync started" }
            val responses = apiService.getTimelineBuckets()
            log.d { "Timeline bucket metadata fetched responseCount=${responses.size}" }
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
                "Timeline bucket metadata changes stale=${staleBucketIds.size} " +
                    "removed=${removedBucketIds.size} previous=${oldEntities.size} next=${entities.size}"
            }
            withContext(Dispatchers.IO) {
                // Count-changed buckets keep their old refs so cached Timeline
                // rows remain visible until that bucket's asset refresh
                // succeeds. Removed buckets are the only metadata-sync case
                // that can safely clear relationship rows immediately.
                removedBucketIds.forEach { timeBucket ->
                    timelineDao.clearTimelineRefsForBucket(timeBucket)
                }
                if (removedBucketIds.isNotEmpty()) {
                    timelineDao.clearMosaicAssignmentsForBuckets(removedBucketIds.toList())
                    timelineDao.clearMosaicGeometryForBuckets(removedBucketIds.toList())
                    timelineDao.clearBucketGeometryForBuckets(removedBucketIds.toList())
                }
                timelineDao.replaceBuckets(entities)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        SYNC_SCOPE_TIMELINE_BUCKETS,
                        currentEpochMillis()
                    )
                )
            }
            log.d { "Timeline bucket metadata Room write completed buckets=${entities.size}" }
            Result.success(
                TimelineBucketSyncResult(
                    buckets = entities.map { it.toDomain() },
                    staleBucketIds = staleBucketIds,
                    removedBucketIds = removedBucketIds
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncBucketAssets(timeBucket: String): Result<TimelineBucketAssetSyncResult> {
        return try {
            log.d { "Timeline bucket asset sync started bucket=$timeBucket" }
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
                "Timeline bucket asset sync fetched bucket=$timeBucket fetched=${allAssets.size} " +
                    "visible=${filtered.size} hidden=${allAssets.size - filtered.size}"
            }
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
            val after = withContext(Dispatchers.IO) {
                assetDao.getTimelineAssets(timeBucket)
            }
            val changed = timelineBucketAssetsChanged(before, after)
            if (changed) {
                withContext(Dispatchers.IO) {
                    timelineDao.clearMosaicAssignmentsForBuckets(listOf(timeBucket))
                    timelineDao.clearMosaicGeometryForBuckets(listOf(timeBucket))
                    timelineDao.clearBucketGeometryForBuckets(listOf(timeBucket))
                }
            }
            log.d {
                "Timeline bucket asset sync completed bucket=$timeBucket previous=${before.size} " +
                    "current=${after.size} changed=$changed"
            }
            Result.success(TimelineBucketAssetSyncResult(
                timeBucket = timeBucket,
                changed = changed
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Timeline bucket asset sync failed bucket=$timeBucket" }
            Result.failure(e)
        }
    }

    suspend fun precomputeTimelineMosaic(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): Result<TimelineMosaicPrecomputeResult> {
        // Timeline Mosaic is persisted after sync so scrolling does not keep
        // CPU-heavy assignment work alive. The assignment rows are replaced for
        // this exact config; no-op syncs keep their existing rows because the
        // caller only invokes this for changed or missing loaded buckets.
        if (timeBuckets.isEmpty()) {
            return Result.success(TimelineMosaicPrecomputeResult(emptySet(), emptySet()))
        }
        val layoutSpec = mosaicAssignmentLayoutSpec(columnCount)
            ?: return Result.success(TimelineMosaicPrecomputeResult(emptySet(), timeBuckets))
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            val groupMode = groupSize.apiValue
            log.d {
                "Timeline Mosaic repository precompute started buckets=${timeBuckets.size} " +
                    "group=$groupSize columns=$columnCount families=$normalizedFamilies " +
                    "geometry=${geometryRequest != null}"
            }
            val semaphore = Semaphore(TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val results = coroutineScope {
                timeBuckets.map { timeBucket ->
                    async(Dispatchers.IO) {
                        val result = semaphore.withPermit {
                            try {
                                precomputeBucketMosaic(
                                    timeBucket = timeBucket,
                                    groupSize = groupSize,
                                    groupMode = groupMode,
                                    columnCount = columnCount,
                                    families = normalizedFamilies,
                                    familiesKey = familiesKey,
                                    assignmentLayoutSpec = layoutSpec,
                                    geometryRequest = geometryRequest,
                                    onProgressChunk = onProgressChunk
                                )
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.e(e) { "Failed to precompute Timeline Mosaic for $timeBucket" }
                                false
                            }
                        }
                        progressMutex.withLock {
                            completed++
                            if (result) successful++ else failed++
                            log.d {
                                "Timeline Mosaic precompute progress $completed/${timeBuckets.size}: " +
                                    "bucket=$timeBucket success=$result successful=$successful failed=$failed"
                            }
                        }
                        timeBucket to result
                    }
                }.awaitAll()
            }
            log.d {
                "Timeline Mosaic repository precompute completed buckets=${timeBuckets.size} " +
                    "successful=$successful failed=$failed"
            }
            Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = results.filter { it.second }.map { it.first }.toSet(),
                    failedBucketIds = results.filterNot { it.second }.map { it.first }.toSet()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncAllBucketAssets(
        buckets: List<TimelineBucket>,
        groupSize: TimelineGroupSize? = null,
        columnCount: Int? = null,
        families: Set<MosaicTemplateFamily>? = null,
        geometryRequest: TimelineMosaicGeometryRequest? = null
    ): Result<TimelineAssetSyncResult> {
        if (groupSize != null && columnCount != null && families != null && geometryRequest != null) {
            return syncAllBucketAssetsWithMosaic(
                buckets = buckets,
                groupSize = groupSize,
                columnCount = columnCount,
                families = families,
                geometryRequest = geometryRequest
            )
        }
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
                        val result = semaphore.withPermit {
                            syncBucketAssets(bucket.timeBucket)
                        }
                        val success = result.isSuccess
                        progressMutex.withLock {
                            completed++
                            if (success) successful++ else failed++
                            log.d {
                                "Timeline asset sync progress $completed/${buckets.size}: " +
                                    "${bucket.timeBucket} success=$success successful=$successful failed=$failed"
                            }
                        }
                        bucket.timeBucket to result.getOrNull()
                    }
                }.awaitAll()
            }
            log.d { "Finished timeline asset sync: successful=$successful failed=$failed total=${buckets.size}" }
            Result.success(
                TimelineAssetSyncResult(
                    successfulBucketIds = results.filter { it.second != null }.map { it.first }.toSet(),
                    failedBucketIds = results.filter { it.second == null }.map { it.first }.toSet(),
                    changedBucketIds = results.mapNotNull { it.second }
                        .filter { it.changed }
                        .map { it.timeBucket }
                        .toSet()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncAllBucketAssetsWithMosaic(
        buckets: List<TimelineBucket>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<TimelineAssetSyncResult> {
        val assignmentLayoutSpec = mosaicAssignmentLayoutSpec(columnCount)
            ?: return Result.success(
                TimelineAssetSyncResult(
                    successfulBucketIds = emptySet(),
                    failedBucketIds = buckets.map { it.timeBucket }.toSet()
                )
            )
        return try {
            val normalizedFamilies = families.normalizedMosaicFamilies()
            val familiesKey = timelineMosaicFamiliesKey(normalizedFamilies)
            val groupMode = groupSize.apiValue
            log.d {
                "Starting fused cold timeline sync for ${buckets.size} buckets " +
                    "group=$groupSize columns=$columnCount families=$normalizedFamilies"
            }
            val semaphore = Semaphore(FULL_SYNC_BUCKET_PARALLELISM)
            val progressMutex = Mutex()
            var completed = 0
            var successful = 0
            var failed = 0
            val results = coroutineScope {
                buckets.map { bucket ->
                    async(Dispatchers.IO) {
                        val result = semaphore.withPermit {
                            syncBucketAssetsAndMosaic(
                                bucket = bucket,
                                groupSize = groupSize,
                                groupMode = groupMode,
                                columnCount = columnCount,
                                families = normalizedFamilies,
                                familiesKey = familiesKey,
                                assignmentLayoutSpec = assignmentLayoutSpec,
                                geometryRequest = geometryRequest
                            )
                        }
                        progressMutex.withLock {
                            completed++
                            if (result != null) successful++ else failed++
                            log.d {
                                "Fused cold timeline sync progress $completed/${buckets.size}: " +
                                    "bucket=${bucket.timeBucket} success=${result != null} " +
                                    "successful=$successful failed=$failed"
                            }
                        }
                        bucket.timeBucket to result
                    }
                }.awaitAll()
            }
            log.d {
                "Finished fused cold timeline sync: successful=$successful failed=$failed total=${buckets.size}"
            }
            Result.success(
                TimelineAssetSyncResult(
                    successfulBucketIds = results.filter { it.second != null }.map { it.first }.toSet(),
                    failedBucketIds = results.filter { it.second == null }.map { it.first }.toSet(),
                    changedBucketIds = results.filter { it.second != null }.map { it.first }.toSet(),
                    bucketGeometrySummaries = results.mapNotNull { it.second?.toDomain() }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncBucketAssetsAndMosaic(
        bucket: TimelineBucket,
        groupSize: TimelineGroupSize,
        groupMode: String,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        familiesKey: String,
        assignmentLayoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest
    ): TimelineBucketGeometryEntity? {
        val timeBucket = bucket.timeBucket
        return try {
            log.d { "Fused cold bucket sync started bucket=$timeBucket" }
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
            editsEnricher.enrich(filtered)
            val finalEntities = withContext(Dispatchers.IO) {
                assetDao.getTimelineAssets(timeBucket)
            }
            val bucketGeometry = precomputeBucketMosaicFromEntities(
                timeBucket = timeBucket,
                groupSize = groupSize,
                groupMode = groupMode,
                columnCount = columnCount,
                families = families,
                familiesKey = familiesKey,
                assignmentLayoutSpec = assignmentLayoutSpec,
                geometryRequest = geometryRequest,
                entities = finalEntities
            )
            log.d {
                "Fused cold bucket sync completed bucket=$timeBucket fetched=${allAssets.size} " +
                    "visible=${filtered.size} refs=${crossRefs.size} bucketGeometry=${bucketGeometry != null}"
            }
            bucketGeometry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Fused cold bucket sync failed bucket=$timeBucket" }
            null
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
            timelineDao.clearAllTimelineMosaicAssignments()
            timelineDao.clearAllTimelineMosaicGeometry()
            timelineDao.clearAllTimelineBucketGeometry()
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_TIMELINE_BUCKETS = "timeline_buckets"
        const val SYNC_SCOPE_TIMELINE_COLD_COMPLETE = "timeline_cold_complete"
        private const val FULL_SYNC_BUCKET_PARALLELISM = 4
        private const val TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM = 4
    }

    private suspend fun precomputeBucketMosaic(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        groupMode: String,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        familiesKey: String,
        assignmentLayoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest?,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ) {
        val entities = withContext(Dispatchers.IO) {
            assetDao.getTimelineAssets(timeBucket)
        }
        precomputeBucketMosaicFromEntities(
            timeBucket = timeBucket,
            groupSize = groupSize,
            groupMode = groupMode,
            columnCount = columnCount,
            families = families,
            familiesKey = familiesKey,
            assignmentLayoutSpec = assignmentLayoutSpec,
            geometryRequest = geometryRequest,
            entities = entities,
            onProgressChunk = onProgressChunk
        )
    }

    private suspend fun precomputeBucketMosaicFromEntities(
        timeBucket: String,
        groupSize: TimelineGroupSize,
        groupMode: String,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        familiesKey: String,
        assignmentLayoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        geometryRequest: TimelineMosaicGeometryRequest?,
        entities: List<AssetEntity>,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): TimelineBucketGeometryEntity? {
        val fingerprint = orderedAssetsFingerprint(entities)
        val assets = entities.map { it.toDomain(baseUrl()) }
        val sections = timelineMosaicSections(timeBucket, groupSize, assets)
        val now = currentEpochMillis()
        val geometryRows = mutableListOf<TimelineMosaicGeometryEntity>()
        val bucketGeometryItems = mutableListOf<com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem>()
        val rows = sections.map { section ->
            val assignments = withContext(Dispatchers.Default) {
                if (geometryRequest != null) {
                    buildMosaicAssignmentsWithProgress(
                        assets = section.assets,
                        layoutSpec = assignmentLayoutSpec,
                        spacing = GRID_SPACING_DP,
                        enabledFamilies = families,
                        maxRowHeight = geometryRequest.maxRowHeight,
                        onProgressChunk = { chunk ->
                            onProgressChunk(
                                TimelineMosaicProgressChunk(
                                    timeBucket = timeBucket,
                                    sectionKey = section.sectionKey,
                                    sectionLabel = section.label,
                                    sourceStartIndex = chunk.sourceStartIndex,
                                    sourceEndExclusive = chunk.sourceEndExclusive,
                                    assignments = chunk.assignments
                                )
                            )
                        }
                    )
                } else {
                    buildMosaicAssignments(
                        assets = section.assets,
                        layoutSpec = assignmentLayoutSpec,
                        spacing = GRID_SPACING_DP,
                        enabledFamilies = families
                    )
                }
            }
            geometryRequest?.let { request ->
                computeTimelineMosaicGeometryEntity(
                    timeBucket = timeBucket,
                    groupMode = groupMode,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    assetFingerprint = fingerprint,
                    assets = section.assets,
                    assignments = assignments,
                    request = request,
                    now = now
                )?.let { geometry ->
                    geometryRows.add(geometry)
                    if (groupSize == TimelineGroupSize.DAY) {
                        bucketGeometryItems.add(HeaderItem(
                            gridKey = "geometry_day_${section.sectionKey}",
                            bucketIndex = 0,
                            sectionLabel = section.label,
                            label = section.label
                        ))
                    }
                    bucketGeometryItems.addAll(
                        buildPhotoGridPlaceholderItemsForHeight(
                            bucketIndex = 0,
                            sectionLabel = section.label,
                            estimatedHeight = geometry.placeholderHeight,
                            externalSpacing = GRID_SPACING_DP
                        )
                    )
                }
            }
            TimelineMosaicAssignmentEntity(
                timeBucket = timeBucket,
                groupMode = groupMode,
                sectionKey = section.sectionKey,
                columnCount = columnCount,
                familiesKey = familiesKey,
                assetFingerprint = fingerprint,
                assignmentsJson = json.encodeToString(assignments.map { it.toDto() }),
                updatedAt = now
            )
        }
        val bucketGeometry = geometryRequest?.let { request ->
            computeTimelineBucketGeometryEntity(
                timeBucket = timeBucket,
                groupMode = groupMode,
                columnCount = columnCount,
                familiesKey = familiesKey,
                assetFingerprint = fingerprint,
                displayItems = if (groupSize == TimelineGroupSize.DAY) {
                    bucketGeometryItems
                } else {
                    geometryRows.firstOrNull()?.let { geometry ->
                        buildPhotoGridPlaceholderItemsForHeight(
                            bucketIndex = 0,
                            sectionLabel = timeBucket,
                            estimatedHeight = geometry.placeholderHeight,
                            externalSpacing = GRID_SPACING_DP
                        )
                    }.orEmpty()
                },
                request = request,
                now = now
            )
        }
        withContext(Dispatchers.IO) {
            timelineDao.replaceMosaicAssignmentsForBucketConfig(
                timeBucket = timeBucket,
                groupMode = groupMode,
                columnCount = columnCount,
                familiesKey = familiesKey,
                assignments = rows,
                geometry = geometryRows,
                bucketGeometry = bucketGeometry
            )
        }
        log.d {
            "Timeline Mosaic bucket precompute persisted bucket=$timeBucket assets=${assets.size} " +
                "sections=${sections.size} assignmentRows=${rows.size} geometryRows=${geometryRows.size} " +
                "bucketGeometry=${bucketGeometry != null}"
        }
        return bucketGeometry
    }
}

private data class TimelineMosaicSection(
    val sectionKey: String,
    val label: String,
    val assets: List<Asset>
)

private fun computeTimelineMosaicGeometryEntity(
    timeBucket: String,
    groupMode: String,
    sectionKey: String,
    columnCount: Int,
    familiesKey: String,
    assetFingerprint: String,
    assets: List<Asset>,
    assignments: List<com.udnahc.immichgallery.domain.model.MosaicBandAssignment>,
    request: TimelineMosaicGeometryRequest,
    now: Long
): TimelineMosaicGeometryEntity? {
    val layoutSpec = mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount) ?: return null
    val displayItems = buildPhotoGridItemsWithMosaic(
        assets = assets,
        assignments = assignments,
        bucketIndex = 0,
        sectionLabel = sectionKey,
        layoutSpec = layoutSpec,
        spacing = request.spacing,
        maxRowHeight = request.maxRowHeight
    )
    val placeholderHeight = estimatePhotoGridDisplayItemsHeight(displayItems, request.spacing)
    if (placeholderHeight <= 0f) return null
    return TimelineMosaicGeometryEntity(
        timeBucket = timeBucket,
        groupMode = groupMode,
        sectionKey = sectionKey,
        columnCount = columnCount,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = timelineMosaicGeometryWidthKey(request.availableWidth),
        geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItems.size,
        maxRowHeightKey = timelineMosaicGeometryDimensionKey(request.maxRowHeight),
        spacingKey = timelineMosaicGeometryDimensionKey(request.spacing),
        updatedAt = now
    )
}

private fun computeTimelineBucketGeometryEntity(
    timeBucket: String,
    groupMode: String,
    columnCount: Int,
    familiesKey: String,
    assetFingerprint: String,
    displayItems: List<com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem>,
    request: TimelineMosaicGeometryRequest,
    now: Long
): TimelineBucketGeometryEntity? {
    if (mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount) == null) return null
    val placeholderHeight = estimatePhotoGridDisplayItemsHeight(displayItems, request.spacing)
    if (placeholderHeight < 0f) return null
    return TimelineBucketGeometryEntity(
        timeBucket = timeBucket,
        groupMode = groupMode,
        columnCount = columnCount,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = timelineMosaicGeometryWidthKey(request.availableWidth),
        geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItems.size,
        maxRowHeightKey = timelineMosaicGeometryDimensionKey(request.maxRowHeight),
        spacingKey = timelineMosaicGeometryDimensionKey(request.spacing),
        updatedAt = now
    )
}

private fun TimelineMosaicGeometryEntity.toDomain(): TimelineMosaicGeometrySummary =
    TimelineMosaicGeometrySummary(
        timeBucket = timeBucket,
        sectionKey = sectionKey,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount
    )

private fun TimelineBucketGeometryEntity.toDomain(): TimelineBucketGeometrySummary =
    TimelineBucketGeometrySummary(
        timeBucket = timeBucket,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount
    )

internal data class TimelineBucketMetadataChanges(
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
        staleBucketIds = staleBucketIds,
        removedBucketIds = oldById.keys - newIds
    )
}

private fun TimelineMosaicAssignmentEntity.decodeMosaicAssignments(): List<com.udnahc.immichgallery.domain.model.MosaicBandAssignment>? =
    try {
        json.decodeFromString<List<MosaicBandAssignmentDto>>(assignmentsJson)
            .map { it.toDomain() }
    } catch (_: Exception) {
        null
    }

private fun timelineMosaicSections(
    timeBucket: String,
    groupSize: TimelineGroupSize,
    assets: List<Asset>
): List<TimelineMosaicSection> =
    when {
        assets.isEmpty() -> emptyList()
        groupSize == TimelineGroupSize.DAY -> {
            timelineDaySections(timeBucket, assets)
                .ifEmpty {
                    listOf(TimelineMosaicSection(
                        sectionKey = timelineMonthMosaicSectionKey(timeBucket),
                        label = timeBucket,
                        assets = assets
                    ))
                }
        }
        else -> listOf(TimelineMosaicSection(
            sectionKey = timelineMonthMosaicSectionKey(timeBucket),
            label = timeBucket,
            assets = assets
        ))
    }

private fun timelineDaySections(timeBucket: String, assets: List<Asset>): List<TimelineMosaicSection> {
    val tz = TimeZone.currentSystemDefault()
    val grouped = assets.groupBy { asset ->
        try {
            Instant.parse(asset.createdAt).toLocalDateTime(tz).date
        } catch (_: Exception) {
            try {
                LocalDate.parse(asset.createdAt.take(10))
            } catch (_: Exception) {
                null
            }
        }
    }
    return grouped
        .filterKeys { it != null }
        .entries
        .sortedByDescending { it.key }
        .mapNotNull { (date, dayAssets) ->
            val d = date ?: return@mapNotNull null
            val monthName = d.month.name.lowercase()
            val label = "${d.dayOfMonth} $monthName ${d.year}"
            TimelineMosaicSection(
                sectionKey = timelineDayMosaicSectionKey(timeBucket, label),
                label = label,
                assets = dayAssets.sortedByDescending { it.createdAt }
            )
        }
}

private val json = Json {
    ignoreUnknownKeys = true
}

private const val TIMELINE_MOSAIC_GEOMETRY_VERSION = 1

internal fun timelineMosaicGeometryWidthKey(width: Float): Int =
    width.roundToInt()

internal fun timelineMosaicGeometryDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()

// Timeline sync may write the same rows on every app launch. Layout invalidation
// must be based on content that can affect the grid, not on write success. The
// input lists are already ordered by timeline_asset_refs.sortOrder.
internal fun timelineBucketAssetsChanged(
    before: List<AssetEntity>,
    after: List<AssetEntity>
): Boolean =
    orderedAssetsChanged(before, after)
