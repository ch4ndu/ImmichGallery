package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketGeometryEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicGeometryEntity
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto
import com.udnahc.immichgallery.domain.model.MosaicBandKind
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicAssignment
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplayBandKind
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplayBandRecord
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplaySection
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplayTileRecord
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineMosaicPrecomputeResult
import com.udnahc.immichgallery.domain.model.TimelineMosaicProgressChunk
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildMosaicAssignmentsWithProgress
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForHeight
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.mosaicAssignmentLayoutSpec
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.timelineDayMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMonthMosaicSectionKey
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import org.lighthousegames.logging.logging

class TimelineMosaicCacheRepository(
    private val timelineDao: TimelineDao,
    private val assetDao: AssetDao,
    private val serverConfigRepository: ServerConfigRepository
) {
    private val log = logging("TimelineMosaicCacheRepository")

    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    suspend fun getPersistedBucketGeometry(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<List<TimelineBucketGeometrySummary>> {
        if (timeBuckets.isEmpty()) return Result.success(emptyList())
        return try {
            val familiesKey = timelineMosaicFamiliesKey(families.normalizedMosaicFamilies())
            val widthKey = timelineMosaicGeometryWidthKey(geometryRequest.availableWidth)
            val maxRowHeightKey = timelineMosaicGeometryDimensionKey(geometryRequest.maxRowHeight)
            val spacingKey = timelineMosaicGeometryDimensionKey(geometryRequest.spacing)
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
                    .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
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
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        includeDisplayCache: Boolean = true
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
                    .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
                    .associateBy { it.timeBucket to it.sectionKey }
            }.orEmpty()
            val displayRowsByBucketSection = if (includeDisplayCache && geometryRequest != null) {
                val request = geometryRequest
                val widthKey = timelineMosaicGeometryWidthKey(request.availableWidth)
                val maxRowHeightKey = timelineMosaicGeometryDimensionKey(request.maxRowHeight)
                val spacingKey = timelineMosaicGeometryDimensionKey(request.spacing)
                withContext(Dispatchers.IO) {
                    timelineDao.getMosaicDisplayCache(
                        timeBuckets = timeBuckets.toList(),
                        groupMode = groupMode,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        availableWidthKey = widthKey,
                        displayVersion = TIMELINE_MOSAIC_DISPLAY_CACHE_VERSION
                    )
                }
                    .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
                    .associateBy { it.timeBucket to it.sectionKey }
            } else {
                emptyMap()
            }
            val validAssignments = mutableListOf<TimelineMosaicAssignment>()
            val validGeometry = mutableListOf<TimelineMosaicGeometrySummary>()
            val validDisplaySections = mutableListOf<TimelineMosaicDisplaySection>()
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
                            geometryRowsByBucketSection[timeBucket to section.sectionKey]
                                ?.takeIf { it.assetFingerprint == fingerprint }
                                ?.toDomain()
                                ?.let(validGeometry::add)
                            displayRowsByBucketSection[timeBucket to section.sectionKey]
                                ?.takeIf { it.assetFingerprint == fingerprint }
                                ?.toDomain()
                                ?.let(validDisplaySections::add)
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
                    geometrySummaries = validGeometry,
                    displaySections = validDisplaySections,
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

    suspend fun precomputeTimelineMosaic(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest? = null,
        cacheDisplayResults: Boolean = true,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): Result<TimelineMosaicPrecomputeResult> {
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
                "Timeline Mosaic precompute started buckets=${timeBuckets.size} " +
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
                        val bucketGeometry = semaphore.withPermit {
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
                                    cacheDisplayResults = cacheDisplayResults,
                                    onProgressChunk = onProgressChunk
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.e(e) { "Failed to precompute Timeline Mosaic for $timeBucket" }
                                null
                            }
                        }
                        progressMutex.withLock {
                            completed++
                            if (bucketGeometry != null) successful++ else failed++
                            log.d {
                                "Timeline Mosaic precompute progress $completed/${timeBuckets.size}: " +
                                    "bucket=$timeBucket success=${bucketGeometry != null} " +
                                    "successful=$successful failed=$failed"
                            }
                        }
                        timeBucket to bucketGeometry
                    }
                }.awaitAll()
            }
            Result.success(
                TimelineMosaicPrecomputeResult(
                    successfulBucketIds = results.filter { it.second != null }.map { it.first }.toSet(),
                    failedBucketIds = results.filter { it.second == null }.map { it.first }.toSet(),
                    bucketGeometrySummaries = results.mapNotNull { it.second?.toDomain() }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        withContext(Dispatchers.IO) {
            val ids = timeBuckets.toList()
            timelineDao.clearMosaicAssignmentsForBuckets(ids)
            timelineDao.clearMosaicDisplayCacheForBuckets(ids)
            timelineDao.clearMosaicGeometryForBuckets(ids)
            timelineDao.clearBucketGeometryForBuckets(ids)
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            timelineDao.clearAllTimelineMosaicAssignments()
            timelineDao.clearAllTimelineMosaicDisplayCache()
            timelineDao.clearAllTimelineMosaicGeometry()
            timelineDao.clearAllTimelineBucketGeometry()
        }
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
        cacheDisplayResults: Boolean,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): TimelineBucketGeometryEntity? {
        val entities = withContext(Dispatchers.IO) {
            assetDao.getTimelineAssets(timeBucket)
        }
        val expectedCount = if (entities.isEmpty()) {
            withContext(Dispatchers.IO) {
                timelineDao.getBucketAssetCount(timeBucket)
            }
        } else {
            null
        }
        if (shouldRejectUnsyncedEmptyTimelineBucket(entities.size, expectedCount)) {
            throw IllegalStateException(
                "Refusing Timeline Mosaic precompute for unsynced bucket=$timeBucket " +
                    "expectedCount=$expectedCount actualAssets=${entities.size}"
            )
        }
        return precomputeBucketMosaicFromEntities(
            timeBucket = timeBucket,
            groupSize = groupSize,
            groupMode = groupMode,
            columnCount = columnCount,
            families = families,
            familiesKey = familiesKey,
            assignmentLayoutSpec = assignmentLayoutSpec,
            geometryRequest = geometryRequest,
            cacheDisplayResults = cacheDisplayResults,
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
        cacheDisplayResults: Boolean,
        entities: List<AssetEntity>,
        onProgressChunk: suspend (TimelineMosaicProgressChunk) -> Unit = {}
    ): TimelineBucketGeometryEntity? {
        val fingerprint = orderedAssetsFingerprint(entities)
        val assets = entities.map { it.toDomain(baseUrl()) }
        val sections = timelineMosaicSections(timeBucket, groupSize, assets)
        val now = currentEpochMillis()
        val geometryRows = mutableListOf<TimelineMosaicGeometryEntity>()
        val displayCacheRows = mutableListOf<TimelineMosaicDisplayCacheEntity>()
        val bucketGeometryItems = mutableListOf<PhotoGridDisplayItem>()
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
                val displayItems = buildTimelineMosaicDisplayItemsForGeometry(
                    assets = section.assets,
                    assignments = assignments,
                    columnCount = columnCount,
                    request = request,
                    sectionLabel = section.label
                )
                val displayCache = if (cacheDisplayResults) computeTimelineMosaicDisplayCacheEntity(
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
                ) else null
                displayCache?.let(displayCacheRows::add)
                computeTimelineMosaicGeometryEntity(
                    timeBucket = timeBucket,
                    groupMode = groupMode,
                    sectionKey = section.sectionKey,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    assetFingerprint = fingerprint,
                    displayItems = displayItems,
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
                displayCache = displayCacheRows,
                geometry = geometryRows,
                bucketGeometry = bucketGeometry
            )
        }
        log.d {
            "Timeline Mosaic bucket precompute persisted bucket=$timeBucket assets=${assets.size} " +
                "sections=${sections.size} assignmentRows=${rows.size} displayRows=${displayCacheRows.size} " +
                "geometryRows=${geometryRows.size} bucketGeometry=${bucketGeometry != null}"
        }
        return bucketGeometry
    }
}

private data class TimelineMosaicSection(
    val sectionKey: String,
    val label: String,
    val assets: List<Asset>
)

internal fun buildTimelineMosaicDisplayItemsForGeometry(
    assets: List<Asset>,
    assignments: List<com.udnahc.immichgallery.domain.model.MosaicBandAssignment>,
    columnCount: Int,
    request: TimelineMosaicGeometryRequest,
    sectionLabel: String
): List<PhotoGridDisplayItem> {
    val displayLayoutSpec = mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount) ?: return emptyList()
    return buildPhotoGridItemsWithMosaic(
        assets = assets,
        assignments = assignments,
        bucketIndex = 0,
        sectionLabel = sectionLabel,
        layoutSpec = displayLayoutSpec,
        spacing = request.spacing,
        maxRowHeight = request.maxRowHeight,
        promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
        minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
    )
}

internal fun shouldRejectUnsyncedEmptyTimelineBucket(
    actualAssetCount: Int,
    expectedBucketCount: Int?
): Boolean = actualAssetCount == 0 && (expectedBucketCount ?: 0) > 0

private fun computeTimelineMosaicGeometryEntity(
    timeBucket: String,
    groupMode: String,
    sectionKey: String,
    columnCount: Int,
    familiesKey: String,
    assetFingerprint: String,
    displayItems: List<PhotoGridDisplayItem>,
    request: TimelineMosaicGeometryRequest,
    now: Long
): TimelineMosaicGeometryEntity? {
    if (mosaicLayoutSpecForColumnCount(request.availableWidth, columnCount) == null) return null
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

private fun computeTimelineMosaicDisplayCacheEntity(
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
): TimelineMosaicDisplayCacheEntity? {
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
    val bands = displayItems.filterIsInstance<MosaicBandItem>()
    if (bands.isEmpty()) return null
    val placeholderHeight = estimatePhotoGridDisplayItemsHeight(displayItems, request.spacing)
    if (placeholderHeight <= 0f) return null
    return TimelineMosaicDisplayCacheEntity(
        timeBucket = timeBucket,
        groupMode = groupMode,
        sectionKey = sectionKey,
        columnCount = columnCount,
        familiesKey = familiesKey,
        assetFingerprint = assetFingerprint,
        availableWidthKey = timelineMosaicGeometryWidthKey(request.availableWidth),
        displayVersion = TIMELINE_MOSAIC_DISPLAY_CACHE_VERSION,
        bandsJson = json.encodeToString(bands.map { it.toDisplayRecord() }),
        displayItemCount = bands.size,
        placeholderHeight = placeholderHeight,
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
    displayItems: List<PhotoGridDisplayItem>,
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

private fun TimelineMosaicDisplayCacheEntity.toDomain(): TimelineMosaicDisplaySection =
    TimelineMosaicDisplaySection(
        timeBucket = timeBucket,
        sectionKey = sectionKey,
        bands = json.decodeFromString(bandsJson)
    )

private fun MosaicBandItem.toDisplayRecord(): TimelineMosaicDisplayBandRecord =
    TimelineMosaicDisplayBandRecord(
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        bandHeight = bandHeight,
        kind = when (kind) {
            MosaicBandKind.REAL -> TimelineMosaicDisplayBandKind.REAL
            MosaicBandKind.FALLBACK -> TimelineMosaicDisplayBandKind.FALLBACK
        },
        tiles = tiles.map { tile ->
            TimelineMosaicDisplayTileRecord(
                assetId = tile.photo.asset.id,
                visualOrder = tile.visualOrder,
                x = tile.x,
                y = tile.y,
                width = tile.width,
                height = tile.height
            )
        }
    )

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

private fun currentEpochMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()

private val json = Json {
    ignoreUnknownKeys = true
}

private const val TIMELINE_MOSAIC_PRECOMPUTE_PARALLELISM = 4
private const val TIMELINE_MOSAIC_GEOMETRY_VERSION = 2
private const val TIMELINE_MOSAIC_DISPLAY_CACHE_VERSION = 1

internal fun timelineMosaicGeometryWidthKey(width: Float): Int =
    width.roundToInt()

internal fun timelineMosaicGeometryDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()
