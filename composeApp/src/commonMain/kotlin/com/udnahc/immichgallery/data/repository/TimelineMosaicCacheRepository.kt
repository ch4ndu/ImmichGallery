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
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketArtifacts
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketGeometryArtifact
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicAssignment
import com.udnahc.immichgallery.domain.model.TimelineMosaicCacheStatus
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplayItemRecord
import com.udnahc.immichgallery.domain.model.TimelineMosaicDisplaySection
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import com.udnahc.immichgallery.domain.model.displayRecordsCoverOrderedAssets
import com.udnahc.immichgallery.domain.model.normalizedMosaicFamilies
import com.udnahc.immichgallery.domain.model.timelineMosaicFamiliesKey
import com.udnahc.immichgallery.domain.model.timelineMosaicSections
import com.udnahc.immichgallery.domain.model.toDomain
import com.udnahc.immichgallery.domain.model.toDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
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

    suspend fun getPersistedSectionGeometry(
        timeBuckets: Set<String>,
        groupSize: TimelineGroupSize,
        columnCount: Int,
        families: Set<MosaicTemplateFamily>,
        geometryRequest: TimelineMosaicGeometryRequest
    ): Result<List<TimelineMosaicGeometrySummary>> {
        if (timeBuckets.isEmpty()) return Result.success(emptyList())
        return try {
            val familiesKey = timelineMosaicFamiliesKey(families.normalizedMosaicFamilies())
            val widthKey = timelineMosaicGeometryWidthKey(geometryRequest.availableWidth)
            val maxRowHeightKey = timelineMosaicGeometryDimensionKey(geometryRequest.maxRowHeight)
            val spacingKey = timelineMosaicGeometryDimensionKey(geometryRequest.spacing)
            val rows = withContext(Dispatchers.IO) {
                timelineDao.getMosaicGeometry(
                    timeBuckets = timeBuckets.toList(),
                    groupMode = groupSize.apiValue,
                    columnCount = columnCount,
                    familiesKey = familiesKey,
                    availableWidthKey = widthKey,
                    geometryVersion = TIMELINE_MOSAIC_GEOMETRY_VERSION
                )
            }
            val assetsByBucket = withContext(Dispatchers.IO) {
                timeBuckets.associateWith { timeBucket ->
                    assetDao.getTimelineAssets(timeBucket)
                }
            }
            Result.success(
                validatedTimelineMosaicGeometrySummaries(
                    rows = rows,
                    assetsByBucket = assetsByBucket,
                    requestedTimeBuckets = timeBuckets,
                    groupSize = groupSize,
                    maxRowHeightKey = maxRowHeightKey,
                    spacingKey = spacingKey,
                    baseUrl = baseUrl()
                )
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
                    var complete = geometryRequest != null
                    if (geometryRequest != null &&
                        bucketGeometryRowsByBucket[timeBucket]?.assetFingerprint != fingerprint
                    ) {
                        complete = false
                    }
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
                                ?.toDomain()
                            if (geometryRequest != null && geometry == null) {
                                complete = false
                            }
                            geometry?.let(validGeometry::add)
                            val display = displayRowsByBucketSection[timeBucket to section.sectionKey]
                                ?.takeIf { it.assetFingerprint == fingerprint }
                                ?.toDomain()
                                ?.takeIf { it.displayRecords.displayRecordsCoverOrderedAssets(section.assets) }
                            if (includeDisplayCache && geometryRequest != null && display == null) {
                                complete = false
                            }
                            display?.let(validDisplaySections::add)
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

    suspend fun replaceTimelineMosaicArtifactsForBucketConfig(
        artifacts: TimelineMosaicBucketArtifacts
    ): Result<TimelineBucketGeometrySummary> =
        try {
            val bucketGeometry = artifacts.bucketGeometry ?: throw IllegalArgumentException(
                "Timeline Mosaic artifacts missing bucket geometry bucket=${artifacts.timeBucket}"
            )
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
            log.d {
                "Timeline Mosaic artifacts persisted bucket=${artifacts.timeBucket} " +
                    "assignments=${artifacts.assignments.size} display=${artifacts.displayCache.size} " +
                    "geometry=${artifacts.sectionGeometry.size} bucketGeometry=true"
            }
            Result.success(bucketGeometry.toDomainSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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

private fun TimelineMosaicDisplayCacheEntity.toDomain(): TimelineMosaicDisplaySection =
    TimelineMosaicDisplaySection(
        timeBucket = timeBucket,
        sectionKey = sectionKey,
        displayRecords = json.decodeFromString<List<TimelineMosaicDisplayItemRecord>>(itemsJson)
    )

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
