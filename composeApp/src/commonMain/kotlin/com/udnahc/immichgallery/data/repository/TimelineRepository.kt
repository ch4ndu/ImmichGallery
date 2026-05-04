package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineBucket
import com.udnahc.immichgallery.domain.model.TimelineAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketAssetSyncResult
import com.udnahc.immichgallery.domain.model.TimelineBucketSyncResult
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicAssignment
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.mosaicAssignmentLayoutSpec
import com.udnahc.immichgallery.domain.model.timelineDayMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMonthMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.toAssetEntity
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import com.udnahc.immichgallery.domain.model.toEntity
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
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

    suspend fun getPersistedMosaicCacheStatus(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
    ): Result<TimelineMosaicCacheStatus> {
        if (timeBuckets.isEmpty()) {
            return Result.success(TimelineMosaicCacheStatus(emptyList(), emptySet(), emptySet()))
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
            val validAssignments = mutableListOf<TimelineMosaicAssignment>()
            val completeBucketIds = mutableSetOf<String>()
            val missingBucketIds = mutableSetOf<String>()
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
                        }
                    }
                    if (complete) {
                        completeBucketIds.add(timeBucket)
                    } else {
                        missingBucketIds.add(timeBucket)
                    }
                }
            }
            Result.success(
                TimelineMosaicCacheStatus(
                    assignments = validAssignments,
                    completeBucketIds = completeBucketIds,
                    missingBucketIds = missingBucketIds
                )
            )
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
            val responses = apiService.getTimelineBuckets()
            val entities = responses.mapIndexed { index, response ->
                response.toEntity(index)
            }
            val oldEntities = withContext(Dispatchers.IO) {
                timelineDao.getBuckets()
            }
            val metadataChanges = timelineBucketMetadataChanges(oldEntities, entities)
            val staleBucketIds = metadataChanges.staleBucketIds
            val removedBucketIds = metadataChanges.removedBucketIds
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

    suspend fun syncBucketAssets(timeBucket: String): Result<TimelineBucketAssetSyncResult> {
        return try {
            // Compare the persisted bucket before and after the full write path,
            // including edit enrichment below. The ViewModel uses this changed
            // bit to decide whether row packing and Mosaic assignments for this
            // one bucket need a new revision.
            val before = withContext(Dispatchers.IO) {
                assetDao.getTimelineAssets(timeBucket)
            }
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
            val after = withContext(Dispatchers.IO) {
                assetDao.getTimelineAssets(timeBucket)
            }
            val changed = timelineBucketAssetsChanged(before, after)
            if (changed) {
                withContext(Dispatchers.IO) {
                    timelineDao.clearMosaicAssignmentsForBuckets(listOf(timeBucket))
                }
            }
            Result.success(TimelineBucketAssetSyncResult(
                timeBucket = timeBucket,
                changed = changed
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun precomputeTimelineMosaic(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>
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
            val semaphore = Semaphore(TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM)
            val results = coroutineScope {
                timeBuckets.map { timeBucket ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                precomputeBucketMosaic(
                                    timeBucket = timeBucket,
                                    groupSize = groupSize,
                                    groupMode = groupMode,
                                    columnCount = columnCount,
                                    families = normalizedFamilies,
                                    familiesKey = familiesKey,
                                    assignmentLayoutSpec = layoutSpec
                                )
                                timeBucket to true
                            } catch (e: Exception) {
                                log.e(e) { "Failed to precompute Timeline Mosaic for $timeBucket" }
                                timeBucket to false
                            }
                        }
                    }
                }.awaitAll()
            }
            Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = results.filter { it.second }.map { it.first }.toSet(),
                    failedBucketIds = results.filterNot { it.second }.map { it.first }.toSet()
                )
            )
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
            timelineDao.clearAllTimelineMosaicAssignments()
        }
    }

    private fun currentEpochMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val SYNC_SCOPE_TIMELINE_BUCKETS = "timeline_buckets"
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
        assignmentLayoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
    ) {
        val entities = withContext(Dispatchers.IO) {
            assetDao.getTimelineAssets(timeBucket)
        }
        val fingerprint = orderedAssetsFingerprint(entities)
        val assets = entities.map { it.toDomain(baseUrl()) }
        val sections = timelineMosaicSections(timeBucket, groupSize, assets)
        val now = currentEpochMillis()
        val rows = sections.map { section ->
            val assignments = buildMosaicAssignments(
                assets = section.assets,
                layoutSpec = assignmentLayoutSpec,
                spacing = com.udnahc.immichgallery.domain.model.GRID_SPACING_DP,
                enabledFamilies = families
            )
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
        withContext(Dispatchers.IO) {
            timelineDao.replaceMosaicAssignmentsForBucketConfig(
                timeBucket = timeBucket,
                groupMode = groupMode,
                columnCount = columnCount,
                familiesKey = familiesKey,
                assignments = rows
            )
        }
    }
}

private data class TimelineMosaicSection(
    val sectionKey: String,
    val assets: List<Asset>
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
                .ifEmpty { listOf(TimelineMosaicSection(timelineMonthMosaicSectionKey(timeBucket), assets)) }
        }
        else -> listOf(TimelineMosaicSection(timelineMonthMosaicSectionKey(timeBucket), assets))
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
                assets = dayAssets.sortedByDescending { it.createdAt }
            )
        }
}

private val json = Json {
    ignoreUnknownKeys = true
}

// Timeline sync may write the same rows on every app launch. Layout invalidation
// must be based on content that can affect the grid, not on write success. The
// input lists are already ordered by timeline_asset_refs.sortOrder.
internal fun timelineBucketAssetsChanged(
    before: List<AssetEntity>,
    after: List<AssetEntity>
): Boolean =
    orderedAssetsChanged(before, after)
