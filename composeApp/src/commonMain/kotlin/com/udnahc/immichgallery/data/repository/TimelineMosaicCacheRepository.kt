package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketGeometryEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicAssignmentEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicDisplayCacheEntity
import com.udnahc.immichgallery.data.local.entity.TimelineMosaicGeometryEntity
import com.udnahc.immichgallery.domain.model.MosaicBandAssignmentDto
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicAssignment
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketArtifacts
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketGeometryArtifact
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplayItemRecord
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplaySection
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySnapshot
import com.udnahc.immichgallery.domain.model.displayRecordsCoverOrderedAssets
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.timelineMosaicSections
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import com.udnahc.immichgallery.domain.model.toPhotoGridDisplayItems
import com.udnahc.immichgallery.domain.model.validateMosaicAssignments
import com.udnahc.immichgallery.domain.model.validateMosaicSectionGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.lighthousegames.logging.logging
import kotlin.math.roundToInt
import kotlin.math.abs

class TimelineMosaicCacheRepository(
    private val timelineDao: TimelineDao,
    private val assetDao: AssetDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    private val log = logging("TimelineMosaicCacheRepository")

    private fun baseUrl(): String = serverConfigRepository.getServerUrl().trimEnd('/')

    suspend fun getPersistedGeometrySnapshot(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<TimelineMosaicGeometrySnapshot> {
        if (timeBuckets.isEmpty()) {
            return Result.success(TimelineMosaicGeometrySnapshot(emptyList(), emptyList()))
        }
        return try {
            val familiesKey = timelineMosaicFamiliesKey(families.normalizedMosaicFamilies())
            val widthKey = timelineMosaicGeometryWidthKey(geometryRequest.availableWidth)
            val maxRowHeightKey = timelineMosaicGeometryDimensionKey(geometryRequest.maxRowHeight)
            val spacingKey = timelineMosaicGeometryDimensionKey(geometryRequest.spacing)
            val geometryData: Triple<
                    List<TimelineMosaicGeometryEntity>,
                    List<TimelineBucketGeometryEntity>,
                    Map<String, List<AssetEntity>>
                    > = withContext(Dispatchers.IO) {
                Triple(
                    timelineDao.getMosaicGeometry(
                        timeBuckets.toList(),
                        groupSize.apiValue,
                        columnCount,
                        familiesKey,
                        widthKey,
                        TIMELINE_MOSAIC_GEOMETRY_VERSION
                    ),
                    timelineDao.getBucketGeometry(
                        timeBuckets.toList(),
                        groupSize.apiValue,
                        columnCount,
                        familiesKey,
                        widthKey,
                        TIMELINE_MOSAIC_GEOMETRY_VERSION
                    ),
                    timeBuckets.associateWith { timeBucket ->
                        assetDao.getTimelineAssets(timeBucket)
                    }
                )
            }
            val (sectionRows, bucketRows, assetsByBucket) = geometryData
            val sectionSummaries = validatedTimelineMosaicGeometrySummaries(
                rows = sectionRows,
                assetsByBucket = assetsByBucket,
                requestedTimeBuckets = timeBuckets,
                groupSize = groupSize,
                maxRowHeightKey = maxRowHeightKey,
                spacingKey = spacingKey,
                baseUrl = baseUrl()
            )
            val summariesByBucket = sectionSummaries.groupBy { it.timeBucket }
            val rowsByBucket = bucketRows
                .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
                .associateBy { it.timeBucket }
            val bucketSummaries = timeBuckets.mapNotNull { timeBucket ->
                val entities = assetsByBucket[timeBucket].orEmpty()
                val fingerprint = orderedAssetsFingerprint(entities)
                val assets = entities.map { it.toDomain(baseUrl()) }
                val expectedSections = timelineMosaicSections(timeBucket, groupSize, assets)
                val sections = summariesByBucket[timeBucket].orEmpty()
                if (sections.map { it.sectionKey }.toSet() != expectedSections.map { it.sectionKey }.toSet()) {
                    return@mapNotNull null
                }
                val expectedHeight = timelineGeometrySnapshotBucketHeight(
                    groupSize,
                    sections,
                    geometryRequest.spacing
                )
                rowsByBucket[timeBucket]
                    ?.takeIf { it.assetFingerprint == fingerprint }
                    ?.takeIf { it.placeholderHeight >= 0f && abs(it.placeholderHeight - expectedHeight) <= 0.5f }
                    ?.toDomain()
            }
            Result.success(TimelineMosaicGeometrySnapshot(bucketSummaries, sectionSummaries))
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
            val bucketGeometryRowsByBucket = geometryRequest?.let { request ->
                val widthKey = timelineMosaicGeometryWidthKey(request.availableWidth)
                val maxRowHeightKey = timelineMosaicGeometryDimensionKey(request.maxRowHeight)
                val spacingKey = timelineMosaicGeometryDimensionKey(request.spacing)
                withContext(Dispatchers.IO) {
                    timelineDao.getBucketGeometry(
                        timeBuckets = timeBuckets.toList(),
                        groupMode = groupMode,
                        columnCount = columnCount,
                        familiesKey = familiesKey,
                        availableWidthKey = widthKey,
                        geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION
                    )
                }
                    .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
                    .associateBy { it.timeBucket }
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
            val assetsByBucket = withContext(Dispatchers.IO) {
                timeBuckets.associateWith { timeBucket -> assetDao.getTimelineAssets(timeBucket) }
            }
            Result.success(
                buildTimelineMosaicCacheStatus(
                    timeBuckets = timeBuckets,
                    groupSize = groupSize,
                    assetsByBucket = assetsByBucket,
                    rowsByBucketSection = rowsByBucketSection,
                    geometryRowsByBucketSection = geometryRowsByBucketSection,
                    bucketGeometryRowsByBucket = bucketGeometryRowsByBucket,
                    displayRowsByBucketSection = displayRowsByBucketSection,
                    geometryRequired = geometryRequest != null,
                    includeDisplayCache = includeDisplayCache,
                    enabledFamilies = normalizedFamilies,
                    geometrySpacing = geometryRequest?.spacing ?: 0f,
                    baseUrl = baseUrl()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun replaceTimelineMosaicArtifactsForBucketConfig(
        artifacts: TimelineMosaicBucketArtifacts
    ): Result<TimelineBucketGeometrySummary> =
        sessionStateRepository.withOperation { token -> try {
            val bucketGeometry = artifacts.bucketGeometry ?: throw IllegalArgumentException(
                "Timeline Mosaic artifacts are missing bucket geometry"
            )
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    timelineDao.replaceMosaicAssignmentsForBucketConfig(
                    timeBucket = artifacts.timeBucket,
                    groupMode = artifacts.groupMode,
                    columnCount = artifacts.columnCount,
                    familiesKey = artifacts.familiesKey,
                    assignments = artifacts.assignments.map { artifact ->
                        TimelineMosaicAssignmentEntity(
                            timeBucket = artifact.timeBucket,
                            groupMode = artifact.groupMode,
                            sectionKey = artifact.sectionKey,
                            columnCount = artifact.columnCount,
                            familiesKey = artifact.familiesKey,
                            assetFingerprint = artifact.assetFingerprint,
                            assignmentsJson = json.encodeToString(artifact.assignments.map { it.toDto() }),
                            updatedAt = artifact.updatedAt
                        )
                    },
                    displayCache = artifacts.displayCache.map { artifact ->
                        TimelineMosaicDisplayCacheEntity(
                            timeBucket = artifact.timeBucket,
                            groupMode = artifact.groupMode,
                            sectionKey = artifact.sectionKey,
                            columnCount = artifact.columnCount,
                            familiesKey = artifact.familiesKey,
                            assetFingerprint = artifact.assetFingerprint,
                            availableWidthKey = timelineMosaicGeometryWidthKey(artifact.geometryRequest.availableWidth),
                            displayVersion = TIMELINE_MOSAIC_DISPLAY_CACHE_VERSION,
                            itemsJson = json.encodeToString(artifact.displayRecords),
                            displayItemCount = artifact.displayItemCount,
                            placeholderHeight = artifact.placeholderHeight,
                            maxRowHeightKey = timelineMosaicGeometryDimensionKey(artifact.geometryRequest.maxRowHeight),
                            spacingKey = timelineMosaicGeometryDimensionKey(artifact.geometryRequest.spacing),
                            updatedAt = artifact.updatedAt
                        )
                    },
                    geometry = artifacts.sectionGeometry.map { artifact ->
                        TimelineMosaicGeometryEntity(
                            timeBucket = artifact.timeBucket,
                            groupMode = artifact.groupMode,
                            sectionKey = artifact.sectionKey,
                            columnCount = artifact.columnCount,
                            familiesKey = artifact.familiesKey,
                            assetFingerprint = artifact.assetFingerprint,
                            availableWidthKey = timelineMosaicGeometryWidthKey(artifact.geometryRequest.availableWidth),
                            geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION,
                            placeholderHeight = artifact.placeholderHeight,
                            displayItemCount = artifact.displayItemCount,
                            maxRowHeightKey = timelineMosaicGeometryDimensionKey(artifact.geometryRequest.maxRowHeight),
                            spacingKey = timelineMosaicGeometryDimensionKey(artifact.geometryRequest.spacing),
                            geometryRangesJson = json.encodeToString(artifact.ranges),
                            updatedAt = artifact.updatedAt
                        )
                    },
                    bucketGeometry = TimelineBucketGeometryEntity(
                        timeBucket = bucketGeometry.timeBucket,
                        groupMode = bucketGeometry.groupMode,
                        columnCount = bucketGeometry.columnCount,
                        familiesKey = bucketGeometry.familiesKey,
                        assetFingerprint = bucketGeometry.assetFingerprint,
                        availableWidthKey = timelineMosaicGeometryWidthKey(bucketGeometry.geometryRequest.availableWidth),
                        geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION,
                        placeholderHeight = bucketGeometry.placeholderHeight,
                        displayItemCount = bucketGeometry.displayItemCount,
                        maxRowHeightKey = timelineMosaicGeometryDimensionKey(bucketGeometry.geometryRequest.maxRowHeight),
                        spacingKey = timelineMosaicGeometryDimensionKey(bucketGeometry.geometryRequest.spacing),
                        updatedAt = bucketGeometry.updatedAt
                    )
                    )
                }
            }
            log.d {
                "Timeline Mosaic artifacts persisted bucket=<redacted> " +
                        "assignments=<redacted> display=<redacted> " +
                        "geometry=<redacted> bucketGeometry=true"
            }
            Result.success(bucketGeometry.toDomainSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } }

    suspend fun clearBuckets(timeBuckets: Set<String>) {
        if (timeBuckets.isEmpty()) return
        sessionStateRepository.withOperation { token ->
            sessionStateRepository.withCommit(token) {
                withContext(Dispatchers.IO) {
                    val ids = timeBuckets.toList()
                    timelineDao.clearMosaicAssignmentsForBuckets(ids)
                    timelineDao.clearMosaicDisplayCacheForBuckets(ids)
                    timelineDao.clearMosaicGeometryForBuckets(ids)
                    timelineDao.clearBucketGeometryForBuckets(ids)
                }
            }
        }
    }

    suspend fun clearAll() {
        sessionStateRepository.withOperation { token ->
            sessionStateRepository.withCommit(token) {
                clearAllForSessionMutation()
            }
        }
    }

    internal suspend fun clearAllForSessionMutation() {
        withContext(Dispatchers.IO) {
            timelineDao.clearAllTimelineMosaicAssignments()
            timelineDao.clearAllTimelineMosaicDisplayCache()
            timelineDao.clearAllTimelineMosaicGeometry()
            timelineDao.clearAllTimelineBucketGeometry()
        }
    }
}

internal fun buildTimelineMosaicCacheStatus(
    timeBuckets: Set<String>,
    groupSize: TimelineGroupSize,
    assetsByBucket: Map<String, List<AssetEntity>>,
    rowsByBucketSection: Map<Pair<String, String>, TimelineMosaicAssignmentEntity>,
    geometryRowsByBucketSection: Map<Pair<String, String>, TimelineMosaicGeometryEntity>,
    bucketGeometryRowsByBucket: Map<String, TimelineBucketGeometryEntity>,
    displayRowsByBucketSection: Map<Pair<String, String>, TimelineMosaicDisplayCacheEntity>,
    geometryRequired: Boolean,
    includeDisplayCache: Boolean,
    enabledFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet(),
    geometrySpacing: Float = 0f,
    baseUrl: String
): TimelineMosaicCacheStatus {
    val validAssignments = mutableListOf<TimelineMosaicAssignment>()
    val validGeometry = mutableListOf<TimelineMosaicGeometrySummary>()
    val validDisplaySections = mutableListOf<TimelineMosaicDisplaySection>()
    val completeBucketIds = mutableSetOf<String>()
    val missingBucketIds = mutableSetOf<String>()
    val assignmentGeometryReadyBucketIds = mutableSetOf<String>()
    val displayCacheReadyBucketIds = mutableSetOf<String>()
    val missingSectionKeysByBucket = mutableMapOf<String, MutableSet<String>>()
    for (timeBucket in timeBuckets) {
        val entities = assetsByBucket[timeBucket].orEmpty()
        val fingerprint = orderedAssetsFingerprint(entities)
        val assets = entities.map { it.toDomain(baseUrl) }
        val expectedSections = timelineMosaicSections(timeBucket, groupSize, assets)
        var assignmentGeometryReady = geometryRequired
        var displayCacheReady = includeDisplayCache && geometryRequired
        val validatedBucketSections = mutableListOf<TimelineMosaicGeometrySummary>()
        if (geometryRequired &&
            bucketGeometryRowsByBucket[timeBucket]?.assetFingerprint != fingerprint
        ) {
            assignmentGeometryReady = false
            displayCacheReady = false
        }
        for (section in expectedSections) {
            val row = rowsByBucketSection[timeBucket to section.sectionKey]
            val assignments = row?.takeIf { it.assetFingerprint == fingerprint }
                ?.decodeMosaicAssignments()
                ?.takeIf { validateMosaicAssignments(it, section.assets, enabledFamilies) }
            if (assignments == null) {
                assignmentGeometryReady = false
                displayCacheReady = false
                missingSectionKeysByBucket.getOrPut(timeBucket) { mutableSetOf() }
                    .add(section.sectionKey)
            } else {
                validAssignments.add(
                    TimelineMosaicAssignment(
                        timeBucket,
                        section.sectionKey,
                        assignments
                    )
                )
                val geometry = geometryRowsByBucketSection[timeBucket to section.sectionKey]
                    ?.takeIf { it.assetFingerprint == fingerprint }
                    ?.toDomain()
                    ?.takeIf {
                        validateMosaicSectionGeometry(
                            ranges = it.ranges,
                            assetCount = section.assets.size,
                            placeholderHeight = it.placeholderHeight,
                            spacing = geometrySpacing
                        )
                    }
                if (geometryRequired && geometry == null) {
                    assignmentGeometryReady = false
                    displayCacheReady = false
                    missingSectionKeysByBucket.getOrPut(timeBucket) { mutableSetOf() }
                        .add(section.sectionKey)
                }
                geometry?.let {
                    validGeometry.add(it)
                    validatedBucketSections.add(it)
                }
                val displayRow = displayRowsByBucketSection[timeBucket to section.sectionKey]
                    ?.takeIf { it.assetFingerprint == fingerprint }
                val display = displayRow
                    ?.toDomainOrNull()
                    ?.takeIf { candidate ->
                        candidate.displayRecords.displayRecordsCoverOrderedAssets(section.assets) &&
                                geometry != null &&
                                abs(displayRow.placeholderHeight - geometry.placeholderHeight) <= 0.5f &&
                                abs(
                                    estimatePhotoGridDisplayItemsHeight(
                                        candidate.displayRecords.toPhotoGridDisplayItems(
                                            section.assets,
                                            0,
                                            section.sectionKey
                                        ),
                                        geometrySpacing
                                    ) - geometry.placeholderHeight
                                ) <= 0.5f
                    }
                if (includeDisplayCache && geometryRequired && display == null) {
                    displayCacheReady = false
                }
                display?.let(validDisplaySections::add)
            }
        }
        if (geometryRequired) {
            val bucketGeometry = bucketGeometryRowsByBucket[timeBucket]
            val expectedHeight = timelineGeometrySnapshotBucketHeight(
                groupSize,
                validatedBucketSections,
                geometrySpacing
            )
            if (validatedBucketSections.size != expectedSections.size ||
                bucketGeometry == null ||
                bucketGeometry.assetFingerprint != fingerprint ||
                abs(bucketGeometry.placeholderHeight - expectedHeight) > 0.5f
            ) {
                assignmentGeometryReady = false
                displayCacheReady = false
            }
        }
        if (assignmentGeometryReady) {
            assignmentGeometryReadyBucketIds.add(timeBucket)
        } else {
            missingBucketIds.add(timeBucket)
        }
        if (displayCacheReady) {
            displayCacheReadyBucketIds.add(timeBucket)
        }
        val complete = if (includeDisplayCache && geometryRequired) {
            displayCacheReady
        } else {
            assignmentGeometryReady
        }
        if (complete) {
            completeBucketIds.add(timeBucket)
        }
    }
    return TimelineMosaicCacheStatus(
        assignments = validAssignments,
        geometrySummaries = validGeometry,
        displaySections = validDisplaySections,
        completeBucketIds = completeBucketIds,
        missingBucketIds = missingBucketIds,
        assignmentGeometryReadyBucketIds = assignmentGeometryReadyBucketIds,
        displayCacheReadyBucketIds = displayCacheReadyBucketIds,
        missingSectionKeysByBucket = missingSectionKeysByBucket.mapValues { it.value.toSet() }
    )
}

internal fun validatedTimelineMosaicGeometrySummaries(
    rows: List<TimelineMosaicGeometryEntity>,
    assetsByBucket: Map<String, List<AssetEntity>>,
    requestedTimeBuckets: Set<String>,
    groupSize: TimelineGroupSize,
    maxRowHeightKey: Int,
    spacingKey: Int,
    baseUrl: String
): List<TimelineMosaicGeometrySummary> {
    val rowsByBucketSection = rows
        .filter { it.maxRowHeightKey == maxRowHeightKey && it.spacingKey == spacingKey }
        .associateBy { it.timeBucket to it.sectionKey }
    return buildList {
        requestedTimeBuckets.forEach { timeBucket ->
            val entities = assetsByBucket[timeBucket].orEmpty()
            val fingerprint = orderedAssetsFingerprint(entities)
            val assets = entities.map { it.toDomain(baseUrl) }
            timelineMosaicSections(timeBucket, groupSize, assets).forEach { section ->
                rowsByBucketSection[timeBucket to section.sectionKey]
                    ?.takeIf { it.assetFingerprint == fingerprint }
                    ?.toDomain()
                    ?.takeIf {
                        validateMosaicSectionGeometry(
                            ranges = it.ranges,
                            assetCount = section.assets.size,
                            placeholderHeight = it.placeholderHeight,
                            spacing = spacingKey / 100f
                        )
                    }
                    ?.let(::add)
            }
        }
    }
}

private fun TimelineMosaicGeometryEntity.toDomain(): TimelineMosaicGeometrySummary =
    TimelineMosaicGeometrySummary(
        timeBucket = timeBucket,
        sectionKey = sectionKey,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount,
        ranges = if (geometryRangesJson.isEmpty()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<MosaicSectionGeometryRange>>(geometryRangesJson)
            } catch (_: Exception) {
                emptyList()
            }
        }
    )

private fun TimelineBucketGeometryEntity.toDomain(): TimelineBucketGeometrySummary =
    TimelineBucketGeometrySummary(
        timeBucket = timeBucket,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount
    )

private fun TimelineMosaicBucketGeometryArtifact.toDomainSummary(): TimelineBucketGeometrySummary =
    TimelineBucketGeometrySummary(
        timeBucket = timeBucket,
        placeholderHeight = placeholderHeight,
        displayItemCount = displayItemCount
    )

private fun timelineGeometrySnapshotBucketHeight(
    groupSize: TimelineGroupSize,
    sections: List<TimelineMosaicGeometrySummary>,
    spacing: Float
): Float {
    if (sections.isEmpty()) return 0f
    val sectionHeight = sections.sumOf { it.placeholderHeight.toDouble() }.toFloat()
    return if (groupSize == TimelineGroupSize.DAY) {
        sectionHeight +
                com.udnahc.immichgallery.domain.model.PHOTO_GRID_SECTION_HEADER_HEIGHT_DP * sections.size +
                spacing * (sections.size * 2 - 1).coerceAtLeast(0)
    } else {
        sections.first().placeholderHeight
    }
}

private fun TimelineMosaicDisplayCacheEntity.toDomainOrNull(): TimelineMosaicDisplaySection? =
    try {
        TimelineMosaicDisplaySection(
            timeBucket = timeBucket,
            sectionKey = sectionKey,
            displayRecords = json.decodeFromString<List<TimelineMosaicDisplayItemRecord>>(itemsJson)
        )
    } catch (_: Exception) {
        null
    }

private fun TimelineMosaicAssignmentEntity.decodeMosaicAssignments(): List<com.udnahc.immichgallery.domain.model.MosaicBandAssignment>? =
    try {
        json.decodeFromString<List<MosaicBandAssignmentDto>>(assignmentsJson)
            .map { it.toDomain() }
    } catch (_: Exception) {
        null
    }

private val json = Json {
    ignoreUnknownKeys = true
}

private const val TIMELINE_MOSAIC_GEOMETRY_VERSION = 3
private const val TIMELINE_MOSAIC_DISPLAY_CACHE_VERSION = 1

internal fun timelineMosaicGeometryWidthKey(width: Float): Int =
    width.roundToInt()

internal fun timelineMosaicGeometryDimensionKey(value: Float): Int =
    (value * 100f).roundToInt()
